package com.lj.meteorfuck.vpark_1_0_0;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import com.lj.meteorfuck.MeteorfuckMod;

/**
 * 反摔落免疫（NoFall）检测器 —— 纯服务端实现。
 *
 * 本类只提供函数，不注册任何事件监听。由调度器调用
 * {@link #onMove(Player, Location, Location, boolean)}（建议每次 PlayerMoveEvent 都调用）
 * 与 {@link #tick()}（建议每秒一次）即可。
 *
 * 原理
 *
 * 摔落伤害完全由服务端根据自己累积的落距（{@code fallDistance}）计算，
 * 客户端唯一能作弊的地方是谎报「我已落地」（移动包里的 {@code onGround} 字段）：
 * 服务端只要认为玩家落地就会把落距清零，于是多高的坠落都不掉血。
 * 因此本检测完全建立在「玩家自称落地」与「服务端观测到的物理事实」之间的矛盾上。
 *
 * 检测项
 *
 * 1. 谎报落地 —— 玩家声称自己站在地上，身体却仍在下降，
 *    并且脚下（收窄薄片包围盒探测）没有任何碰撞方块；
 * 2. 落距与位移不符 —— 在「声称落地 + 仍在下降」的过程中，累计实际下降量已达 5 格以上，
 *    而服务端记录的落距比它少 2 格以上（落距被非法清零，正是 NoFall 的核心目的）。
 *
 * 如何区分正常行为
 *
 * - 只在「声称落地且正在下降」时才看脚下：正常站立、走下楼梯、跳下台阶时脚下都有碰撞方块，
 *   真正的落地瞬间也一定踩在方块上，不会误判；
 * - 落距检查要求累计下降 ≥ 5 格且落距缺失 ≥ 2 格，并排除水中 / 岩浆 / 攀爬 / 滑翔 / 激流 /
 *   缓降与漂浮药水 / 蜘蛛网 / 细雪等「原版本来就会清零落距」的情况；
 * - 站在船、矿车等实体上方时脚下没有方块，因此额外做了实体支撑探测，避免误判；
 * - 谎报落地需要连续命中 2 次才计违规；
 * - 创造 / 旁观 / 死亡 / 骑乘 / 拥有 {@code meteorfuck.bypass}（OP 默认拥有）完全免检；
 * - 加入、重生、传送后给予免检期；服务器 TPS 过低时暂停检测；
 * - 多信号加权累计（VL）后才踢出，单一信号绝不会误杀。
 */
public final class No_NoFall {

	/* ==================== 可调参数 ==================== */

	/** 绕过检测的权限节点（OP 默认拥有该权限）。 */
	private static final String BYPASS_PERMISSION = "meteorfuck.bypass";

	/** 输出调试日志。 */
	private static final boolean DEBUG = false;

	/* --- 脚下碰撞探测 --- */
	/** 探测薄片向下延伸的深度（格）。 */
	private static final double GROUND_PROBE_DEPTH = 0.08D;
	/** 探测薄片向上延伸的高度（格）。 */
	private static final double GROUND_PROBE_HEIGHT = 0.05D;
	/** 探测薄片在 X / Z 方向的内缩量（格），避免贴着墙时误判。 */
	private static final double GROUND_PROBE_INSET = 0.03D;

	/* --- 谎报落地 --- */
	/** 单 tick 下降超过该值才算「还在下落」（格 / tick）。 */
	private static final double FALLING_MIN_DESCENT = 0.01D;
	/** 谎报落地需要连续命中的次数。 */
	private static final int GROUND_LIE_BUFFER_THRESHOLD = 2;
	/** 实体支撑探测的水平半径与高度（格）。 */
	private static final double SUPPORT_PROBE_RADIUS = 1.2D;
	/** 实体支撑探测的高度（格）。 */
	private static final double SUPPORT_PROBE_HEIGHT = 1.0D;

	/* --- 落距与位移不符 --- */
	/** 参与判定的最小累计下降量（格）。 */
	private static final double FALL_DISTANCE_MIN = 5.0D;
	/** 落距允许比实际下降量少的最大格数，超过即视为被清零。 */
	private static final double FALL_DISTANCE_TOLERANCE = 2.0D;

	/* --- 违规等级 --- */
	private static final int WEIGHT_GROUND_LIE = 3;
	private static final int WEIGHT_FALL_RESET = 3;
	/** 达到该违规等级即踢出。 */
	private static final int VL_KICK = 12;
	/** 违规等级上限。 */
	private static final int VL_MAX = 20;
	/** 无违规时每隔该时间衰减 1 点违规等级（毫秒）。 */
	private static final long VL_DECAY_INTERVAL_MS = 5000L;
	/** 数据空闲多久后被清理（毫秒）。 */
	private static final long DATA_IDLE_TIMEOUT_MS = 300_000L;

	/* --- 其它 --- */
	/** 加入 / 重生 / 传送后的免检时间（毫秒）。 */
	private static final long GRACE_MS = 3000L;
	/** 服务器 TPS 低于该值时暂停检测。 */
	private static final double MIN_TPS = 15.0D;

	private static final String LOG_PREFIX = "[No_NoFall] ";
	private static final String DEBUG_LOG_FORMAT = LOG_PREFIX + "{0} vl={1} [{2}]";
	private static final String PUNISH_LOG_FORMAT = LOG_PREFIX + "检测到 {0} 疑似使用摔落免疫（{1}），已踢出";

	private static final No_NoFall INSTANCE = new No_NoFall();

	/** 每个玩家的检测状态。 */
	private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

	/** 缓存的服务器 TPS，由 {@link #tick()} 更新，避免每次调用都分配数组。 */
	private volatile double cachedTps = 20.0D;

	private No_NoFall() {
	}

	/**
	 * 获取检测器单例。
	 */
	public static No_NoFall get() {
		return INSTANCE;
	}

	/* ==================== 对外函数（由调度器调用） ==================== */

	/**
	 * 玩家加入时调用，建立检测状态。
	 */
	public void onJoin(Player player) {
		if (player == null || isExempt(player)) {
			return;
		}
		players.computeIfAbsent(player.getUniqueId(), key -> new PlayerData());
	}

	/**
	 * 玩家退出时调用，释放检测状态。
	 */
	public void onQuit(Player player) {
		if (player == null) {
			return;
		}
		players.remove(player.getUniqueId());
	}

	/**
	 * 玩家重生时调用，重置免检期与相关状态。
	 */
	public void onRespawn(Player player) {
		restart(player);
	}

	/**
	 * 玩家被传送时调用，重置免检期与相关状态。
	 */
	public void onTeleport(Player player) {
		restart(player);
	}

	/**
	 * 玩家移动时调用（对应 {@code PlayerMoveEvent}），本检测的唯一入口。
	 *
	 * @param player   玩家
	 * @param from     移动前的位置
	 * @param to       移动后的位置
	 * @param onGround 客户端上报的离地标记，直接传 {@code player.isOnGround()} 即可
	 */
	public void onMove(Player player, Location from, Location to, boolean onGround) {
		if (player == null || from == null || to == null || isExempt(player)) {
			return;
		}
		long now = System.currentTimeMillis();
		PlayerData data = players.computeIfAbsent(player.getUniqueId(), key -> new PlayerData());
		data.lastSeenTime = now;
		if (now - data.graceStart < GRACE_MS) {
			return;
		}

		double deltaY = to.getY() - from.getY();
		if (deltaY >= -FALLING_MIN_DESCENT) {
			// 没有在下落（站立 / 行走 / 上升 / 被击退）：累计与缓冲全部清零
			data.descent = 0.0D;
			data.groundLieBuffer = 0;
			return;
		}
		double descent = -deltaY;

		if (!onGround) {
			// 老实上报「我在空中」：只累计下降量，不做任何判定
			data.descent += descent;
			data.groundLieBuffer = 0;
			return;
		}

		// ==== 声称已落地，却仍在下降：NoFall 唯一能作弊的地方 ====
		double previous = data.descent;
		data.descent += descent;
		if (cachedTps < MIN_TPS) {
			return;
		}

		// ---- 检测 2：服务端记录的落距被非法清零 ----
		if (previous >= FALL_DISTANCE_MIN
				&& player.getFallDistance() < previous - FALL_DISTANCE_TOLERANCE
				&& !isFallDistanceExempt(player)) {
			data.descent = 0.0D;
			data.groundLieBuffer = 0;
			addViolation(player, data, WEIGHT_FALL_RESET, "fall-reset", now);
			return;
		}

		// ---- 检测 1：脚下确实什么都没有 ----
		if (hasCollisionBelow(player)) {
			data.groundLieBuffer = 0;
			return;
		}
		if (++data.groundLieBuffer >= GROUND_LIE_BUFFER_THRESHOLD) {
			data.groundLieBuffer = 0;
			// 站在船 / 矿车等实体上方时脚下也没有方块，需要单独探一次
			if (!isSupportedByEntity(player)) {
				addViolation(player, data, WEIGHT_GROUND_LIE, "ground-lie", now);
			}
		}
	}

	/**
	 * 定期调用（建议每秒一次）：刷新 TPS 缓存、衰减违规等级、清理过期数据。
	 */
	public void tick() {
		double[] tps = Bukkit.getTPS();
		if (tps.length > 0) {
			cachedTps = tps[0];
		}
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
			PlayerData data = entry.getValue();
			// 长时间没有新增违规时逐步衰减，避免历史误报累积
			if (data.vl > 0 && now - data.lastViolationTime > VL_DECAY_INTERVAL_MS) {
				data.vl--;
				data.lastViolationTime = now;
			}
			// 长时间无动作的数据直接回收
			if (now - data.lastSeenTime > DATA_IDLE_TIMEOUT_MS
					|| Bukkit.getPlayer(entry.getKey()) == null) {
				players.remove(entry.getKey(), data);
			}
		}
	}

	/**
	 * 清空指定玩家的全部检测数据（可用于管理指令）。
	 */
	public void reset(Player player) {
		if (player != null) {
			players.remove(player.getUniqueId());
		}
	}

	/**
	 * 获取指定玩家当前的违规等级，便于调试与展示。
	 */
	public int getViolationLevel(Player player) {
		if (player == null) {
			return 0;
		}
		PlayerData data = players.get(player.getUniqueId());
		return data == null ? 0 : data.vl;
	}

	/* ==================== 内部函数 ==================== */

	/**
	 * 判断玩家脚下（薄片范围内）是否存在实体碰撞方块。
	 *
	 * 使用收窄的薄片包围盒与方块的碰撞形状做相交测试，
	 * 因此半砖 / 栅栏 / 楼梯都能正确识别，同时不会因为贴着墙而产生误判。
	 */
	private static boolean hasCollisionBelow(Player player) {
		BoundingBox box = player.getBoundingBox();
		double minX = box.getMinX() + GROUND_PROBE_INSET;
		double maxX = box.getMaxX() - GROUND_PROBE_INSET;
		double minZ = box.getMinZ() + GROUND_PROBE_INSET;
		double maxZ = box.getMaxZ() - GROUND_PROBE_INSET;
		if (minX >= maxX || minZ >= maxZ) {
			return false;
		}
		BoundingBox probe = new BoundingBox(minX, box.getMinY() - GROUND_PROBE_DEPTH, minZ, maxX,
				box.getMinY() + GROUND_PROBE_HEIGHT, maxZ);

		World world = player.getWorld();
		int fromX = (int) Math.floor(probe.getMinX());
		int toX = (int) Math.floor(probe.getMaxX());
		int fromY = (int) Math.floor(probe.getMinY());
		int toY = (int) Math.floor(probe.getMaxY());
		int fromZ = (int) Math.floor(probe.getMinZ());
		int toZ = (int) Math.floor(probe.getMaxZ());
		for (int x = fromX; x <= toX; x++) {
			for (int y = fromY; y <= toY; y++) {
				for (int z = fromZ; z <= toZ; z++) {
					Block block = world.getBlockAt(x, y, z);
					if (block.isPassable()) {
						continue;
					}
					if (block.getBoundingBox().overlaps(probe)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * 判断玩家是否站在实体（船、矿车、其它生物）上方。
	 *
	 * 只在「谎报落地」缓冲已经命中、即将处罚时才调用，避免给每个移动事件增加开销。
	 */
	private static boolean isSupportedByEntity(Player player) {
		double feetY = player.getLocation().getY();
		for (Entity entity : player.getNearbyEntities(SUPPORT_PROBE_RADIUS, SUPPORT_PROBE_HEIGHT,
				SUPPORT_PROBE_RADIUS)) {
			double top = entity.getBoundingBox().getMaxY();
			if (top <= feetY + GROUND_PROBE_HEIGHT && top >= feetY - SUPPORT_PROBE_HEIGHT) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 判断当前状态是否会让「落距被清零」成为原版行为，需要跳过落距检查。
	 */
	private static boolean isFallDistanceExempt(Player player) {
		if (player.isInWater() || player.isInLava() || player.isClimbing() || player.isGliding()
				|| player.isRiptiding() || player.isInsideVehicle()) {
			return true;
		}
		if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
				|| player.hasPotionEffect(PotionEffectType.LEVITATION)) {
			return true;
		}
		// 蜘蛛网 / 细雪同样会合法清零落距
		Location location = player.getLocation();
		World world = player.getWorld();
		int x = location.getBlockX();
		int y = location.getBlockY();
		int z = location.getBlockZ();
		return isResetBlock(world.getBlockAt(x, y, z)) || isResetBlock(world.getBlockAt(x, y - 1, z))
				|| isResetBlock(world.getBlockAt(x, y + 1, z));
	}

	/**
	 * 判断方块是否属于「会让玩家停下并清零落距」的方块。
	 */
	private static boolean isResetBlock(Block block) {
		Material type = block.getType();
		return type == Material.COBWEB || type == Material.POWDER_SNOW;
	}

	/**
	 * 重置免检期与该玩家的相关状态。
	 */
	private void restart(Player player) {
		if (player == null) {
			return;
		}
		PlayerData data = players.get(player.getUniqueId());
		if (data == null) {
			return;
		}
		data.graceStart = System.currentTimeMillis();
		data.descent = 0.0D;
		data.groundLieBuffer = 0;
	}

	/**
	 * 判断玩家是否完全免检。
	 */
	private static boolean isExempt(Player player) {
		if (player.isDead() || player.isInsideVehicle()) {
			return true;
		}
		GameMode mode = player.getGameMode();
		if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
			return true;
		}
		// OP 默认拥有全部权限，因此权限判断同时覆盖了 OP
		return player.hasPermission(BYPASS_PERMISSION);
	}

	/**
	 * 结算违规等级，超限则踢出。
	 */
	private void addViolation(Player player, PlayerData data, int weight, String reason, long now) {
		data.vl = Math.min(data.vl + weight, VL_MAX);
		data.lastViolationTime = now;
		if (DEBUG) {
			log().log(Level.INFO, DEBUG_LOG_FORMAT, new Object[] { player.getName(), data.vl, reason });
		}
		if (data.vl >= VL_KICK) {
			punish(player, data);
		}
	}

	/**
	 * 处罚：记录日志并踢出玩家。非主线程时自动调度回主线程。
	 */
	private void punish(Player player, PlayerData data) {
		data.vl = 0; // 重置，避免重复处罚
		data.descent = 0.0D;
		data.groundLieBuffer = 0;
		MeteorfuckMod plugin = MeteorfuckMod.getInstance();
		if (plugin == null) {
			return;
		}
		log().log(Level.WARNING, PUNISH_LOG_FORMAT,
				new Object[] { player.getName(), CheckType.NO_FALL.getDisplayName() });
		if (Bukkit.isPrimaryThread()) {
			// 踢出信息由主类从 main.yml 的 kick-message 读取
			plugin.KickMassage(player);
		} else {
			Bukkit.getScheduler().runTask(plugin, () -> plugin.KickMassage(player));
		}
	}

	private static Logger log() {
		MeteorfuckMod plugin = MeteorfuckMod.getInstance();
		return plugin == null ? Logger.getGlobal() : plugin.getLogger();
	}

	/* ==================== 内部类型 ==================== */

	/**
	 * 检测类型，仅用于日志区分触发原因。
	 */
	public enum CheckType {

		/** 摔落免疫（NoFall）。 */
		NO_FALL("摔落免疫");

		private final String displayName;

		CheckType(String displayName) {
			this.displayName = displayName;
		}

		public String getDisplayName() {
			return displayName;
		}
	}

	/**
	 * 单个玩家的检测状态。全部为基本类型字段，热路径不产生额外分配。
	 */
	private static final class PlayerData {

		/** 免检期起点。 */
		long graceStart = System.currentTimeMillis();
		/** 最近一次违规时间。 */
		long lastViolationTime = System.currentTimeMillis();
		/** 最近一次收到动作的时间。 */
		long lastSeenTime = System.currentTimeMillis();

		/** 本次连续下降过程累计的下降量（格）。 */
		double descent;

		/** 谎报落地的连续计数。 */
		int groundLieBuffer;

		/** 违规等级。 */
		int vl;
	}
}
