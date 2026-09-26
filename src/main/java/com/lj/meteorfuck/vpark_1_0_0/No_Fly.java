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
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import com.lj.meteorfuck.MeteorfuckMod;

/**
 * 反飞行（Fly / Glide / Hover）检测器 —— 纯服务端实现。
 *
 * 本类只提供函数，不注册任何事件监听。后续由调度器调用
 * {@link #onMove(Player, Location, Location, boolean)}、{@link #tick()} 等入口即可。
 *
 * 检测项
 *
 * 1. 垂直速度超限 —— 单 tick 上升速度超过原版跳跃（含跳跃提升）允许的上限；
 * 2. 无重力悬停 —— 连续离地 1.5 秒以上、Y 坐标几乎不变，且脚下确实没有任何碰撞方块；
 * 3. 重力异常 —— 下落过程中每 tick 的实际位移远小于物理预测值（缓降 / 滑翔）。
 *
 * 如何区分「插件给的飞行权限」（重点）
 *
 * - {@link Player#getAllowFlight()} 或 {@link Player#isFlying()} 为 true 时完全放行，
 *   因此 Essentials / CMI 等插件的 {@code /fly}、OP 手动开飞行、WorldGuard 的区域飞行都不会被误判；
 * - 创造 / 旁观模式、{@code meteorfuck.bypass} 权限（OP 默认拥有）同样完全免检；
 * - 鞘翅滑翔、游泳、水中、气泡柱、攀爬、三叉戟激流、
 *   漂浮（Levitation）与缓降（Slow Falling）药水效果一律跳过；
 * - 骑乘中的玩家不检测；蜘蛛网 / 蜂蜜块 / 细雪旁的下落会被识别为「合法减速」；
 * - 加入、重生、传送后给予免检期；服务器 TPS 过低时暂停检测；
 * - 高延迟玩家的重力判定会因位置插值失真，因此延迟过高时直接跳过该检测；
 * - 多信号加权累计（VL）后才踢出，单一信号绝不会误杀。
 */
public final class No_Fly {

	/* ==================== 可调参数 ==================== */

	/** 绕过检测的权限节点（OP 默认拥有该权限）。 */
	private static final String BYPASS_PERMISSION = "meteorfuck.bypass";

	/** 输出调试日志。 */
	private static final boolean DEBUG = false;

	/* --- 垂直速度 --- */
	/** 原版跳跃初速度（格 / tick）。 */
	private static final double VANILLA_JUMP_DY = 0.42D;
	/** 每级跳跃提升额外提供的初速度（格 / tick）。 */
	private static final double JUMP_BOOST_DY_PER_LEVEL = 0.1D;
	/** 垂直速度判定的宽限（格 / tick），吸收浮点误差与击退差异。 */
	private static final double VERTICAL_SPEED_TOLERANCE = 0.08D;
	/** 垂直速度异常需要连续命中的次数。 */
	private static final int VERTICAL_SPEED_BUFFER_THRESHOLD = 2;

	/* --- 无重力悬停 --- */
	/** 连续离地达到该 tick 数（30 tick = 1.5 秒）才判定悬停。 */
	private static final int HOVER_MIN_TICKS = 30;
	/** 离地期间 Y 坐标允许的波动范围（格）。 */
	private static final double HOVER_MAX_BAND = 0.6D;

	/* --- 重力异常 --- */
	/** 是否启用重力异常检测（该检测误报敏感，不需要时可关闭）。 */
	private static final boolean ENABLE_GRAVITY_CHECK = true;
	/** 单 tick 重力加速度（格 / tick²）。 */
	private static final double GRAVITY = 0.08D;
	/** 空气阻力系数。 */
	private static final double DRAG = 0.98D;
	/** 实际下落速度比物理预测「慢」超过该值即视为异常（格 / tick）。 */
	private static final double GRAVITY_DEVIATION = 0.06D;
	/** 重力异常需要连续命中的次数。 */
	private static final int GRAVITY_BUFFER_THRESHOLD = 10;
	/** 低于该下落速度时不判定（避开跳跃顶点附近）。 */
	private static final double GRAVITY_MIN_FALL_SPEED = -0.3D;
	/** 延迟高于该值（毫秒）时跳过重力判定，避免位置插值造成误判。 */
	private static final int MAX_PING_FOR_GRAVITY = 200;

	/* --- 脚下碰撞探测 --- */
	/** 探测薄片向下延伸的深度（格）。 */
	private static final double GROUND_PROBE_DEPTH = 0.08D;
	/** 探测薄片向上延伸的高度（格）。 */
	private static final double GROUND_PROBE_HEIGHT = 0.05D;
	/** 探测薄片在 X / Z 方向的内缩量（格），避免贴着墙时误判。 */
	private static final double GROUND_PROBE_INSET = 0.03D;

	/* --- 违规等级 --- */
	private static final int WEIGHT_VERTICAL_SPEED = 3;
	private static final int WEIGHT_HOVER = 4;
	private static final int WEIGHT_GRAVITY = 3;
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

	private static final String LOG_PREFIX = "[No_Fly] ";
	private static final String DEBUG_LOG_FORMAT = LOG_PREFIX + "{0} vl={1} [{2}]";
	private static final String PUNISH_LOG_FORMAT = LOG_PREFIX + "检测到 {0} 疑似使用飞行（{1}），已踢出";

	private static final No_Fly INSTANCE = new No_Fly();

	/** 每个玩家的检测状态。 */
	private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

	/** 缓存的服务器 TPS，由 {@link #tick()} 更新，避免每次调用都分配数组。 */
	private volatile double cachedTps = 20.0D;

	private No_Fly() {
	}

	/**
	 * 获取检测器单例。
	 */
	public static No_Fly get() {
		return INSTANCE;
	}

	/* ==================== 对外函数（后续由调度器调用） ==================== */

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
	 * 玩家移动时调用（对应 {@code PlayerMoveEvent}），反飞行的主检测入口。
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

		// 飞行 / 滑翔 / 游泳 / 攀爬 / 药水效果等合法状态：重置跟踪并直接放行
		if (isMovementExempt(player)) {
			data.resetAirborne(to.getY());
			data.verticalSpeedBuffer = 0;
			data.gravityBuffer = 0;
			data.lastDeltaY = 0.0D;
			data.wasAirborne = false;
			return;
		}

		double deltaY = to.getY() - from.getY();

		if (onGround) {
			// 落地：结束本次离地，清空所有与滞空相关的缓冲
			data.resetAirborne(to.getY());
			data.verticalSpeedBuffer = 0;
			data.gravityBuffer = 0;
			data.lastDeltaY = deltaY;
			data.wasAirborne = false;
			return;
		}

		// ---- 累加离地状态（同时维护 Y 坐标波动范围）----
		boolean wasAirborne = data.wasAirborne;
		double previousDeltaY = data.lastDeltaY;
		data.addAirborne(to.getY());

		if (cachedTps < MIN_TPS) {
			data.lastDeltaY = deltaY;
			data.wasAirborne = true;
			return;
		}

		int weight = 0;
		StringBuilder debug = DEBUG ? new StringBuilder(48) : null;

		// ---- 检测 1：垂直上升速度超过原版上限 ----
		// 先用常量做一次短路判断，避免每次移动都去查询跳跃提升药水
		if (deltaY > VANILLA_JUMP_DY && deltaY > maxRiseSpeed(player)) {
			if (++data.verticalSpeedBuffer >= VERTICAL_SPEED_BUFFER_THRESHOLD) {
				data.verticalSpeedBuffer = 0;
				weight += WEIGHT_VERTICAL_SPEED;
				appendDebug(debug, "vertical-speed");
			}
		} else {
			data.verticalSpeedBuffer = Math.max(0, data.verticalSpeedBuffer - 1);
		}

		// ---- 检测 2：无重力悬停 ----
		// 每次离地只上报一次；脚下确实没有碰撞方块才算，排除站在栅栏 / 半砖边缘等情况
		if (!data.hoverReported && data.airborneTicks >= HOVER_MIN_TICKS
				&& data.airborneBand() <= HOVER_MAX_BAND && !hasCollisionBelow(player)) {
			data.hoverReported = true;
			weight += WEIGHT_HOVER;
			appendDebug(debug, "hover");
		}

		// ---- 检测 3：下落速度低于物理预测（缓降 / 滑翔）----
		if (ENABLE_GRAVITY_CHECK && wasAirborne && previousDeltaY < GRAVITY_MIN_FALL_SPEED
				&& player.getPing() <= MAX_PING_FOR_GRAVITY) {
			double predicted = (previousDeltaY - GRAVITY) * DRAG;
			if (deltaY - predicted > GRAVITY_DEVIATION && !hasFallModifier(player)) {
				if (++data.gravityBuffer >= GRAVITY_BUFFER_THRESHOLD) {
					weight += WEIGHT_GRAVITY;
					appendDebug(debug, "gravity");
				}
			} else {
				data.gravityBuffer = Math.max(0, data.gravityBuffer - 1);
			}
		} else {
			data.gravityBuffer = 0;
		}

		data.lastDeltaY = deltaY;
		data.wasAirborne = true;

		if (weight > 0) {
			addViolation(player, data, weight, debug, now);
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
			if (now - data.lastSeenTime > DATA_IDLE_TIMEOUT_MS) {
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
	 * 计算该玩家当前允许的最大上升速度：原版跳跃初速度 + 跳跃提升加成 + 宽限。
	 */
	private static double maxRiseSpeed(Player player) {
		PotionEffect jumpBoost = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
		int level = jumpBoost == null ? 0 : jumpBoost.getAmplifier() + 1;
		return VANILLA_JUMP_DY + JUMP_BOOST_DY_PER_LEVEL * level + VERTICAL_SPEED_TOLERANCE;
	}

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
	 * 判断玩家附近是否存在会「合法减速」的方块：蜘蛛网、蜂蜜块、细雪。
	 * 蜂蜜块需要额外检查身体侧面，因为贴墙下滑时脚下是空气。
	 */
	private static boolean hasFallModifier(Player player) {
		Location location = player.getLocation();
		World world = player.getWorld();
		int x = location.getBlockX();
		int y = location.getBlockY();
		int z = location.getBlockZ();
		if (isSlowFallBlock(world.getBlockAt(x, y, z)) || isSlowFallBlock(world.getBlockAt(x, y - 1, z))) {
			return true;
		}
		return isSlowFallBlock(world.getBlockAt(x + 1, y, z)) || isSlowFallBlock(world.getBlockAt(x - 1, y, z))
				|| isSlowFallBlock(world.getBlockAt(x, y, z + 1)) || isSlowFallBlock(world.getBlockAt(x, y, z - 1));
	}

	private static boolean isSlowFallBlock(Block block) {
		Material type = block.getType();
		return type == Material.COBWEB || type == Material.HONEY_BLOCK || type == Material.POWDER_SNOW;
	}

	/**
	 * 结算违规等级，超限则踢出。
	 */
	private void addViolation(Player player, PlayerData data, int weight, StringBuilder debug, long now) {
		data.vl = Math.min(data.vl + weight, VL_MAX);
		data.lastViolationTime = now;
		if (DEBUG) {
			log().log(Level.INFO, DEBUG_LOG_FORMAT,
					new Object[] { player.getName(), data.vl, debug });
		}
		if (data.vl >= VL_KICK) {
			punish(player);
		}
	}

	/**
	 * 处罚：记录日志并踢出玩家。非主线程时自动调度回主线程。
	 */
	private void punish(Player player) {
		PlayerData data = players.get(player.getUniqueId());
		if (data != null) {
			data.vl = 0; // 重置，避免重复处罚
			data.verticalSpeedBuffer = 0;
			data.gravityBuffer = 0;
			data.resetAirborne(player.getLocation().getY());
		}
		MeteorfuckMod plugin = MeteorfuckMod.getInstance();
		if (plugin == null) {
			return;
		}
		log().log(Level.WARNING, PUNISH_LOG_FORMAT,
				new Object[] { player.getName(), CheckType.FLY.getDisplayName() });
		if (Bukkit.isPrimaryThread()) {
			// 踢出信息由主类从 main.yml 的 kick-message 读取
			plugin.KickMassage(player);
		} else {
			Bukkit.getScheduler().runTask(plugin, () -> plugin.KickMassage(player));
		}
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
		data.verticalSpeedBuffer = 0;
		data.gravityBuffer = 0;
		data.lastDeltaY = 0.0D;
		data.wasAirborne = false;
		data.resetAirborne(player.getLocation().getY());
	}

	/**
	 * 判断玩家是否完全免检。
	 *
	 * 这里是「区分插件给的飞行权限」的关键：只要 {@code allowFlight} 或 {@code isFlying}
	 * 为 true（Essentials 等插件的 /fly、OP 开飞行、区域飞行），就一律放行。
	 */
	private static boolean isExempt(Player player) {
		if (player.isDead() || player.isInsideVehicle()) {
			return true;
		}
		if (player.getAllowFlight() || player.isFlying()) {
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
	 * 判断当前 tick 是否属于合法物理状态，需要跳过检测。
	 *
	 * 注意：气泡柱在 1.21.5 之后已并入 {@code isInWater()}，无需单独判断。
	 */
	private static boolean isMovementExempt(Player player) {
		return player.isGliding() || player.isSwimming() || player.isInWater() || player.isClimbing()
				|| player.isRiptiding() || player.hasPotionEffect(PotionEffectType.LEVITATION)
				|| player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
	}

	private static void appendDebug(StringBuilder builder, String name) {
		if (builder == null) {
			return;
		}
		if (builder.length() > 0) {
			builder.append(',');
		}
		builder.append(name);
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

		/** 飞行外挂（Fly / Glide / Hover）。 */
		FLY("飞行");

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

		/** 最近一次移动的垂直位移。 */
		double lastDeltaY;
		/** 当前离地状态的 Y 坐标下界。 */
		double airborneMinY;
		/** 当前离地状态的 Y 坐标上界。 */
		double airborneMaxY;
		/** 连续离地的 tick 数。 */
		int airborneTicks;
		/** 上一次采样时是否已经离地。 */
		boolean wasAirborne;
		/** 本次离地期间是否已经上报过悬停。 */
		boolean hoverReported;

		/** 垂直速度异常的连续计数。 */
		int verticalSpeedBuffer;
		/** 重力异常的连续计数。 */
		int gravityBuffer;

		/** 违规等级。 */
		int vl;

		void addAirborne(double y) {
			if (airborneTicks == 0) {
				airborneMinY = y;
				airborneMaxY = y;
			} else if (y < airborneMinY) {
				airborneMinY = y;
			} else if (y > airborneMaxY) {
				airborneMaxY = y;
			}
			airborneTicks++;
		}

		void resetAirborne(double y) {
			airborneTicks = 0;
			airborneMinY = y;
			airborneMaxY = y;
			hoverReported = false;
		}

		double airborneBand() {
			return airborneMaxY - airborneMinY;
		}
	}
}
