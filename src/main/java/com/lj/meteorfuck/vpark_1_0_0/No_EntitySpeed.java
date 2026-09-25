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
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Strider;

import com.lj.meteorfuck.MeteorfuckMod;

/**
 * 反载具加速（EntitySpeed）检测器 —— 纯服务端实现。
 *
 * <p>本类<b>只提供函数</b>，不注册任何事件监听。由调度器在 {@code VehicleMoveEvent} 中调用
 * {@link #onVehicleMove(Player, Entity, Location, Location)}（把载具上的玩家一并传入），
 * 并每秒调用 {@link #tick()} 即可。</p>
 *
 * <h3>原理</h3>
 * <p>骑乘中的船、马、猪、炽足兽等载具的移动由<b>客户端上报</b>，服务端校验很宽松，
 * 因此 EntitySpeed 外挂可以随意提高坐骑速度。原版每种载具都有明确的物理上限
 * （船约 8 米 / 秒、马最快约 14 米 / 秒、猪约 5 米 / 秒），超过上限即是外挂。</p>
 *
 * <h3>检测项</h3>
 * <ol>
 *   <li><b>超过该载具类型的原版上限</b> —— 单 tick 水平位移超过该类型的上限并持续 10 tick
 *       （原版加速受限于属性与摩擦力，不可能长时间超速）；</li>
 *   <li><b>超过绝对上限</b> —— 单 tick 水平位移超过 1.25 格（25 米 / 秒）并持续 5 tick，
 *       这已经超出任何原版载具在绝大多数环境下的可能。</li>
 * </ol>
 *
 * <h3>如何区分正常行为</h3>
 * <ul>
 *   <li><b>矿车完全不判定</b>：活塞发射、下坡铁轨等原版技巧本来就能把矿车加速到很高；</li>
 *   <li><b>冰面船放行</b>：船在冰 / 浮冰 / 蓝冰上滑行是原版机制，速度远超普通水面；</li>
 *   <li>刚被爆炸 / 攻击推过的载具（{@code getNoDamageTicks() > 0}）放行，冲量属于原版物理；</li>
 *   <li>只有乘客是玩家时才统计；单次瞬移（载具被传送）不足以填满连续缓冲，不会误判；</li>
 *   <li>创造 / 旁观 / 死亡 / 拥有 {@code meteorfuck.bypass}（OP 默认拥有）的乘客完全免检；</li>
 *   <li>加入、重生、传送后给予免检期；服务器 TPS 过低或乘客延迟过高时暂停判定；</li>
 *   <li>多信号<b>加权累计</b>（VL）后才踢出，单一信号绝不会误杀。</li>
 * </ul>
 */
public final class No_EntitySpeed {

	/* ==================== 可调参数 ==================== */

	/** 绕过检测的权限节点（OP 默认拥有该权限）。 */
	private static final String BYPASS_PERMISSION = "meteorfuck.bypass";

	/** 输出调试日志。 */
	private static final boolean DEBUG = false;

	/* --- 各载具类型的水平速度上限（格 / tick，1 格 / tick = 20 米 / 秒） --- */
	/** 船 / 运输船：原版水面约 0.4，留出充足余量（冰面另外放行）。 */
	private static final double BOAT_MAX_SPEED = 0.55D;
	/** 马 / 驴 / 骡 / 骷髅马 / 骆驼：原版最快约 0.73。 */
	private static final double HORSE_MAX_SPEED = 0.85D;
	/** 猪 / 炽足兽：原版约 0.25。 */
	private static final double PIG_MAX_SPEED = 0.5D;
	/** 羊驼：原版约 0.25。 */
	private static final double LLAMA_MAX_SPEED = 0.5D;
	/** 其它可骑乘载具的兜底上限。 */
	private static final double DEFAULT_MAX_SPEED = 0.7D;
	/** 绝对上限（格 / tick），超过它已无任何原版解释。 */
	private static final double HARD_MAX_SPEED = 1.25D;

	/** 超过类型上限需要连续命中的 tick 数。 */
	private static final int SPEED_BUFFER_THRESHOLD = 10;
	/** 超过绝对上限需要连续命中的 tick 数。 */
	private static final int HARD_BUFFER_THRESHOLD = 5;

	/** 冰面探测深度（格）。 */
	private static final int ICE_PROBE_DEPTH = 4;

	/** 相邻两 tick 速度跳变超过该值（格 / tick）即视为受外部冲量驱动。 */
	private static final double IMPULSE_JUMP = 0.3D;
	/** 判定受到冲量后暂停判定的时间（毫秒）。 */
	private static final long IMPULSE_COOLDOWN_MS = 1500L;

	/* --- 违规等级 --- */
	private static final int WEIGHT_SPEED = 3;
	private static final int WEIGHT_HARD = 4;
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
	/** 乘客延迟高于该值（毫秒）时暂停判定。 */
	private static final int MAX_PING = 300;
	/** 服务器 TPS 低于该值时暂停判定。 */
	private static final double MIN_TPS = 15.0D;

	private static final String LOG_PREFIX = "[No_EntitySpeed] ";
	private static final String DEBUG_LOG_FORMAT = LOG_PREFIX + "{0} vl={1} [{2}]";
	private static final String PUNISH_LOG_FORMAT = LOG_PREFIX + "检测到 {0} 疑似使用载具加速（{1}），已踢出";

	private static final No_EntitySpeed INSTANCE = new No_EntitySpeed();

	/** 每个乘客的检测状态。 */
	private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

	/** 缓存的服务器 TPS，由 {@link #tick()} 更新，避免每次调用都分配数组。 */
	private volatile double cachedTps = 20.0D;

	private No_EntitySpeed() {
	}

	/**
	 * 获取检测器单例。
	 */
	public static No_EntitySpeed get() {
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
	 * 载具移动时调用（对应 {@code VehicleMoveEvent}）。
	 *
	 * @param player  载具上的玩家（由调度器从载具乘客中取出）
	 * @param vehicle 载具实体
	 * @param from    移动前的位置
	 * @param to      移动后的位置
	 */
	public void onVehicleMove(Player player, Entity vehicle, Location from, Location to) {
		if (player == null || vehicle == null || from == null || to == null || isExempt(player)) {
			return;
		}
		long now = System.currentTimeMillis();
		PlayerData data = players.computeIfAbsent(player.getUniqueId(), key -> new PlayerData());
		data.lastSeenTime = now;
		if (now - data.graceStart < GRACE_MS || !from.getWorld().equals(to.getWorld())) {
			data.resetBuffers();
			return;
		}
		// 矿车不判定：活塞发射 / 下坡铁轨等原版技巧本来就能把它加速到很高
		if (vehicle instanceof Minecart) {
			data.resetBuffers();
			return;
		}

		double dx = to.getX() - from.getX();
		double dz = to.getZ() - from.getZ();
		double stepDh = Math.sqrt(dx * dx + dz * dz);

		// 速度突然跳变（爆炸、活塞、被击退）说明载具正在受外部冲量驱动，一段时间内不判定
		if (stepDh - data.lastDh > IMPULSE_JUMP) {
			data.impulseUntil = now + IMPULSE_COOLDOWN_MS;
		}
		data.lastDh = stepDh;
		if (now < data.impulseUntil) {
			data.resetBuffers();
			return;
		}

		boolean overType = stepDh > maxSpeedOf(vehicle);
		boolean overHard = stepDh > HARD_MAX_SPEED;
		data.speedBuffer = overType ? data.speedBuffer + 1 : 0;
		data.hardBuffer = overHard ? data.hardBuffer + 1 : 0;
		if (!overType) {
			return;
		}

		if (cachedTps < MIN_TPS || player.getPing() > MAX_PING) {
			return;
		}
		if (vehicle instanceof LivingEntity living && living.getNoDamageTicks() > 0) {
			return; // 坐骑刚被攻击 / 爆炸推过，冲量带来的速度是原版的
		}
		if (vehicle instanceof Boat && hasIceBelow(vehicle)) {
			return; // 冰面滑行：原版机制，速度本来就远超水面
		}

		if (data.hardBuffer >= HARD_BUFFER_THRESHOLD) {
			data.resetBuffers();
			addViolation(player, data, WEIGHT_HARD, "hard-speed", now);
			return;
		}
		if (data.speedBuffer >= SPEED_BUFFER_THRESHOLD) {
			data.speedBuffer = 0;
			addViolation(player, data, WEIGHT_SPEED, "speed", now);
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
	 * 返回该载具类型的原版水平速度上限（格 / tick）。
	 */
	private static double maxSpeedOf(Entity vehicle) {
		if (vehicle instanceof Boat) {
			return BOAT_MAX_SPEED;
		}
		if (vehicle instanceof AbstractHorse) {
			return HORSE_MAX_SPEED;
		}
		if (vehicle instanceof Pig || vehicle instanceof Strider) {
			return PIG_MAX_SPEED;
		}
		if (vehicle instanceof Llama) {
			return LLAMA_MAX_SPEED;
		}
		return DEFAULT_MAX_SPEED;
	}

	/**
	 * 判断载具下方是否存在冰（冰面船放行）。
	 */
	private static boolean hasIceBelow(Entity vehicle) {
		Location location = vehicle.getLocation();
		World world = location.getWorld();
		int x = location.getBlockX();
		int z = location.getBlockZ();
		int y = location.getBlockY();
		for (int i = 1; i <= ICE_PROBE_DEPTH; i++) {
			if (isIce(world.getBlockAt(x, y - i, z).getType())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isIce(Material type) {
		return type == Material.ICE || type == Material.PACKED_ICE || type == Material.BLUE_ICE
				|| type == Material.FROSTED_ICE;
	}

	/**
	 * 重置免检期与该玩家的缓冲。
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
		data.lastDh = 0.0D;
		data.impulseUntil = 0L;
		data.resetBuffers();
	}

	/**
	 * 判断玩家是否完全免检。
	 *
	 * <p>注意：这里<b>不能</b>把「骑乘坐骑」当作免检条件，否则本模块永远不会工作。</p>
	 */
	private static boolean isExempt(Player player) {
		if (player.isDead()) {
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
	 * 处罚：记录日志并踢出玩家（连带把载具上的乘客请下车，避免继续加速）。
	 * 非主线程时自动调度回主线程。
	 */
	private void punish(Player player, PlayerData data) {
		data.vl = 0; // 重置，避免重复处罚
		data.resetBuffers();
		MeteorfuckMod plugin = MeteorfuckMod.getInstance();
		if (plugin == null) {
			return;
		}
		log().log(Level.WARNING, PUNISH_LOG_FORMAT,
				new Object[] { player.getName(), CheckType.ENTITY_SPEED.getDisplayName() });
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

		/** 载具加速（EntitySpeed）。 */
		ENTITY_SPEED("载具加速");

		private final String displayName;

		CheckType(String displayName) {
			this.displayName = displayName;
		}

		public String getDisplayName() {
			return displayName;
		}
	}

	/**
	 * 单个乘客的检测状态。全部为基本类型字段，热路径不产生额外分配。
	 */
	private static final class PlayerData {

		/** 免检期起点。 */
		long graceStart = System.currentTimeMillis();
		/** 最近一次违规时间。 */
		long lastViolationTime = System.currentTimeMillis();
		/** 最近一次收到动作的时间。 */
		long lastSeenTime = System.currentTimeMillis();

		/** 超过类型上限的连续计数。 */
		int speedBuffer;
		/** 超过绝对上限的连续计数。 */
		int hardBuffer;

		/** 上一个 tick 的水平位移，用于识别外部冲量。 */
		double lastDh;
		/** 冲量冷却截止时间。 */
		long impulseUntil;

		/** 违规等级。 */
		int vl;

		void resetBuffers() {
			speedBuffer = 0;
			hardBuffer = 0;
		}
	}
}
