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
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import com.lj.meteorfuck.MeteorfuckMod;

/**
 * 反减速免疫（NoSlow）检测器 —— 纯服务端实现。
 *
 * <p>本类<b>只提供函数</b>，不注册任何事件监听。由调度器调用
 * {@link #onMove(Player, Location, Location)}（建议每次 PlayerMoveEvent 都调用）
 * 与 {@link #tick()}（建议每秒一次）即可。</p>
 *
 * <h3>原理</h3>
 * <p>原版在使用物品（吃东西 / 喝药水 / 拉弓 / 蓄力弩 / 举盾 / 望远镜等）时会把移动速度压到约 20%，
 * 并且客户端会强制取消疾跑。NoSlow 外挂保留使用物品时的<b>原有速度</b>甚至疾跑，
 * 于是「拿着弓跑得和空手一样快」就成了服务端唯一能观测到的破绽。</p>
 *
 * <h3>检测项</h3>
 * <ol>
 *   <li><b>使用物品时仍在疾跑</b> —— 原版客户端在使用物品时会取消疾跑，两者同时为真即异常；</li>
 *   <li><b>使用物品时地面速度过高</b> —— 已经贴地并使用物品十余 tick（惯性早已衰减完）之后，
 *       单 tick 水平位移仍持续超过「使用物品」应有限速的两倍以上。</li>
 * </ol>
 *
 * <h3>如何区分正常行为</h3>
 * <ul>
 *   <li>速度判定只在<b>持续贴地</b>时进行：跳跃 / 被击退 / 空中机动时的水平动量本来就不受「使用物品」限制；</li>
 *   <li>速度判定要在使用物品 10 tick、贴地 10 tick 之后才开始，并排除冰面滑行（原版冰上会保留速度）、
 *       水中 / 岩浆 / 滑翔 / 激流 / 骑乘 / 刚受伤被击退等情况；</li>
 *   <li>阈值取 0.10 格 / tick（原版步行约 0.216、使用物品约 0.043），
 *       相当于给了「使用物品」两倍以上余量，也能容忍插件把移速提高到约 5 倍；</li>
 *   <li>同一 tick 内的多次移动事件会先合并成一次完整位移，客户端合并发包（丢 tick）的那一次直接丢弃，
 *       不会因为拆包或网络抖动误判；</li>
 *   <li>两种信号都需要连续命中多个 tick 才计违规；</li>
 *   <li>创造 / 旁观 / 死亡 / 骑乘 / 插件给予的飞行权限 / 拥有 {@code meteorfuck.bypass}（OP 默认拥有）完全免检；</li>
 *   <li>加入、重生、传送后给予免检期；服务器 TPS 过低或延迟过高时暂停检测；</li>
 *   <li>多信号<b>加权累计</b>（VL）后才踢出，单一信号绝不会误杀。</li>
 * </ul>
 */
public final class No_NoSlow {

	/* ==================== 可调参数 ==================== */

	/** 绕过检测的权限节点（OP 默认拥有该权限）。 */
	private static final String BYPASS_PERMISSION = "meteorfuck.bypass";

	/** 输出调试日志。 */
	private static final boolean DEBUG = false;

	/* --- 速度 --- */
	/**
	 * 地面水平位移上限（格 / tick）。
	 *
	 * <p>原版步行约 0.216、疾跑约 0.28、使用物品时约 0.043；
	 * 取 0.10 既给「使用物品」留了两倍以上余量，也能容忍插件把移速提高到约 5 倍。</p>
	 */
	private static final double FAST_MOVE_MIN_SPEED = 0.10D;
	/** 开始使用物品后需要经过的 tick 数（约 0.5 秒），用于吸收起手瞬间的惯性。 */
	private static final int MIN_USE_TICKS = 10;
	/** 需要连续贴地的 tick 数，用于排除空中惯性。 */
	private static final int MIN_GROUND_TICKS = 10;
	/** 超速需要连续命中的 tick 数。 */
	private static final int FAST_MOVE_TICKS = 5;

	/* --- 疾跑标记 --- */
	/** 使用物品时疾跑需要连续命中的 tick 数（吸收起手那一 tick 的时序竞争）。 */
	private static final int USING_SPRINT_BUFFER = 3;

	/* --- 冰面 --- */
	/** 脚下冰面探测深度（格）。 */
	private static final int SLIPPERY_PROBE_DEPTH = 4;

	/* --- 违规等级 --- */
	private static final int WEIGHT_FAST_MOVE = 4;
	private static final int WEIGHT_USING_SPRINT = 3;
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
	/** 延迟高于该值（毫秒）时跳过速度判定。 */
	private static final int MAX_PING = 250;
	/** 服务器 TPS 低于该值时暂停速度判定。 */
	private static final double MIN_TPS = 15.0D;

	private static final String LOG_PREFIX = "[No_NoSlow] ";
	private static final String DEBUG_LOG_FORMAT = LOG_PREFIX + "{0} vl={1} [{2}]"; 
	private static final String PUNISH_LOG_FORMAT = LOG_PREFIX + "检测到 {0} 疑似使用减速免疫（{1}），已踢出";

	private static final No_NoSlow INSTANCE = new No_NoSlow();

	/** 每个玩家的检测状态。 */
	private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

	/** 缓存的服务器 TPS，由 {@link #tick()} 更新，避免每次调用都分配数组。 */
	private volatile double cachedTps = 20.0D;

	private No_NoSlow() {
	}

	/**
	 * 获取检测器单例。
	 */
	public static No_NoSlow get() {
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
	 * @param player 玩家
	 * @param from   移动前的位置
	 * @param to     移动后的位置
	 */
	public void onMove(Player player, Location from, Location to) {
		if (player == null || from == null || to == null || isExempt(player)) {
			return;
		}
		long now = System.currentTimeMillis();
		PlayerData data = players.computeIfAbsent(player.getUniqueId(), key -> new PlayerData());
		data.lastSeenTime = now;
		if (now - data.graceStart < GRACE_MS) {
			return;
		}

		// ---- 把同一 tick 内的多次事件合并为一次完整位移 ----
		int tick = player.getTicksLived();
		double dx = to.getX() - from.getX();
		double dz = to.getZ() - from.getZ();
		double stepDh;
		if (tick == data.lastTick) {
			data.tickDh += Math.sqrt(dx * dx + dz * dz);
			return;
		}
		int gap = tick - data.lastTick;
		stepDh = data.tickDh;
		data.tickDh = Math.sqrt(dx * dx + dz * dz);
		data.lastTick = tick;
		if (gap != 1) {
			// 两次采样之间丢了 tick（客户端合并发包 / 服务器卡顿），这一次位移不可信
			data.fastMoveTicks = 0;
			return;
		}

		boolean using = player.hasActiveItem();

		// ---- 检测 1：使用物品时仍在疾跑 ----
		if (using && player.isSprinting()) {
			if (++data.sprintBuffer >= USING_SPRINT_BUFFER) {
				data.sprintBuffer = 0;
				addViolation(player, data, WEIGHT_USING_SPRINT, "using-sprint", now);
			}
		} else {
			data.sprintBuffer = Math.max(0, data.sprintBuffer - 1);
		}

		// ---- 检测 2：使用物品时的地面速度 ----
		if (!using) {
			data.useTicks = 0;
			data.groundTicks = 0;
			data.fastMoveTicks = 0;
			return;
		}
		data.useTicks++;
		if (!player.isOnGround()) {
			// 空中：水平动量不受「使用物品」限制
			data.groundTicks = 0;
			data.fastMoveTicks = 0;
			return;
		}
		data.groundTicks++;
		if (data.useTicks < MIN_USE_TICKS || data.groundTicks < MIN_GROUND_TICKS) {
			return;
		}
		if (cachedTps < MIN_TPS || player.getPing() > MAX_PING || isSpeedExempt(player)) {
			data.fastMoveTicks = 0;
			return;
		}
		if (stepDh > FAST_MOVE_MIN_SPEED) {
			if (++data.fastMoveTicks >= FAST_MOVE_TICKS) {
				data.fastMoveTicks = 0;
				addViolation(player, data, WEIGHT_FAST_MOVE, "fast-move", now);
			}
		} else {
			data.fastMoveTicks = Math.max(0, data.fastMoveTicks - 1);
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
	 * 判断当前状态是否会让「高速」成为原版合法行为，需要跳过速度判定。
	 */
	private static boolean isSpeedExempt(Player player) {
		if (player.isInWater() || player.isInLava() || player.isGliding() || player.isRiptiding()
				|| player.isInsideVehicle() || player.isClimbing()) {
			return true;
		}
		if (player.hasPotionEffect(PotionEffectType.LEVITATION)
				|| player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
			return true;
		}
		if (player.getNoDamageTicks() > 0) {
			return true; // 刚受伤 / 被击退，冲量带来的速度是原版的
		}
		return hasSlipperyGround(player);
	}

	/**
	 * 判断玩家脚下的方块是否是冰（冰面上滑行会保留速度，原版即如此）。
	 */
	private static boolean hasSlipperyGround(Player player) {
		Location location = player.getLocation();
		World world = player.getWorld();
		int x = location.getBlockX();
		int z = location.getBlockZ();
		int y = location.getBlockY();
		for (int i = 1; i <= SLIPPERY_PROBE_DEPTH; i++) {
			if (isSlippery(world.getBlockAt(x, y - i, z).getType())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isSlippery(Material type) {
		return type == Material.ICE || type == Material.PACKED_ICE || type == Material.BLUE_ICE
				|| type == Material.FROSTED_ICE;
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
		data.tickDh = 0.0D;
		data.lastTick = 0;
		data.useTicks = 0;
		data.groundTicks = 0;
		data.fastMoveTicks = 0;
		data.sprintBuffer = 0;
	}

	/**
	 * 判断玩家是否完全免检。
	 *
	 * <p>这里是「区分插件给的飞行权限」的关键：只要 {@code allowFlight} 或 {@code isFlying}
	 * 为 true（Essentials 等插件的 /fly、OP 开飞行、区域飞行），就一律放行。</p>
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
		data.tickDh = 0.0D;
		data.useTicks = 0;
		data.groundTicks = 0;
		data.fastMoveTicks = 0;
		data.sprintBuffer = 0;
		MeteorfuckMod plugin = MeteorfuckMod.getInstance();
		if (plugin == null) {
			return;
		}
		log().log(Level.WARNING, PUNISH_LOG_FORMAT,
				new Object[] { player.getName(), CheckType.NO_SLOW.getDisplayName() });
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

		/** 减速免疫（NoSlow）。 */
		NO_SLOW("减速免疫");

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

		/** 最近处理过的 ticksLived，用于合并同一 tick 内的多次事件。 */
		int lastTick;
		/** 当前 tick 已累计的水平位移。 */
		double tickDh;

		/** 连续使用物品的 tick 数。 */
		int useTicks;
		/** 连续贴地的 tick 数。 */
		int groundTicks;
		/** 超速的连续计数。 */
		int fastMoveTicks;
		/** 使用物品时疾跑的连续计数。 */
		int sprintBuffer;

		/** 违规等级。 */
		int vl;
	}
}
