package com.lj.meteorfuck.vpark_1_0_0;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import com.lj.meteorfuck.MeteorfuckMod;

/**
 * Criticals（刀暴 / 暴击外挂）检测器 —— 纯服务端实现。
 *
 * <p>本类<b>只提供函数</b>，不注册任何事件监听。后续由监听器 / 调度器调用
 * {@link #onMove(Player, Location, Location, boolean)} 与
 * {@link #onAttack(Player, boolean)} 即可。</p>
 *
 * <h3>原理</h3>
 * <p>原版暴击要求玩家「真的在空中下落」（服务端 {@code fallDistance > 0} 且
 * {@code !onGround}）。刀暴外挂的做法是<b>谎报离地</b>：客户端持续发送
 * {@code onGround = false}，或每次攻击前塞一个极小的上下抖动来刷 {@code fallDistance}，
 * 从而在不跳跃的情况下每次都打出暴击。</p>
 *
 * <h3>检测项</h3>
 * <ol>
 *   <li><b>谎报离地</b> —— 客户端声称在空中，但脚下就有碰撞方块且垂直位移几乎为 0；</li>
 *   <li><b>长期悬浮</b> —— 连续离地 40 tick（2 秒）以上，期间 Y 坐标几乎不变；</li>
 *   <li><b>悬浮暴击</b> —— 暴击时处于「长时间离地 + Y 几乎不变 + 近期没有向上跳」的状态；</li>
 *   <li><b>地面暴击</b> —— 暴击时声称在空中、原地不动，但脚下确实有碰撞方块；</li>
 *   <li><b>暴击率异常</b> —— 统计窗口内暴击率极高，且几乎没有跳跃动作。</li>
 * </ol>
 *
 * <h3>如何避免误伤正常玩家</h3>
 * <ul>
 *   <li>多信号<b>加权累计</b>（VL）后才踢出，单一信号绝不会误杀；</li>
 *   <li>创造 / 旁观 / 濒死 / 骑乘 / 拥有 {@code meteorfuck.bypass} 权限的玩家完全免检；</li>
 *   <li>飞行（含插件 /fly）、滑翔、游泳、水中、气泡柱、攀爬的 tick 一律跳过，
 *       并会重置悬浮计数；</li>
 *   <li>加入、重生、传送后给予免检期；服务器 TPS 过低时暂停检测；</li>
 *   <li>「长期悬浮」只在玩家处于战斗状态时才计入，避免站在船上 / 其它实体上的挂机误判；</li>
 *   <li>脚下碰撞检测使用<b>收窄的薄片包围盒</b>做形状相交，
 *       既能识别半砖 / 栅栏 / 楼梯，也不会因为贴着墙而被误判；</li>
 *   <li>「悬浮暴击」要求离地已有多个 tick，真实跳劈 / 走落悬崖的暴击会因 Y 快速变化而排除。</li>
 * </ul>
 */
public final class No_Criticals {

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

	/* --- 谎报离地 --- */
	/** 单 tick 垂直位移小于该值时视为「原地不动」。 */
	private static final double GROUND_LIE_MAX_DY = 0.01D;
	/** 谎报离地需要连续命中的次数。 */
	private static final int GROUND_LIE_BUFFER_THRESHOLD = 2;

	/* --- 长期悬浮 --- */
	/** 连续离地达到该 tick 数（2 秒）才判定。 */
	private static final int FAKE_AIRBORNE_TICKS = 40;
	/** 离地期间 Y 坐标允许的波动范围（格）。 */
	private static final double FAKE_AIRBORNE_MAX_BAND = 0.5D;
	/** 判定「向上跳」所需的单 tick 向上位移（格）。 */
	private static final double UPWARD_DY_THRESHOLD = 0.05D;
	/** 只有近期攻击过的玩家才统计悬浮，避免非战斗场景误判。 */
	private static final long COMBAT_WINDOW_MS = 5000L;

	/* --- 悬浮暴击 --- */
	/** 暴击时至少已经离地的 tick 数。 */
	private static final int CRIT_HOVER_MIN_TICKS = 6;
	/** 暴击时离地期间 Y 坐标允许的波动范围（格）。 */
	private static final double CRIT_HOVER_MAX_BAND = 0.25D;
	/** 暴击前必须出现过向上位移的时间窗（毫秒）。 */
	private static final long CRIT_UPWARD_WINDOW_MS = 1200L;
	/** 悬浮暴击需要连续命中的次数。 */
	private static final int CRIT_HOVER_BUFFER_THRESHOLD = 2;

	/* --- 暴击率 --- */
	/** 暴击率统计窗口内的攻击次数。 */
	private static final int CRIT_SAMPLE_SIZE = 30;
	/** 允许的最高暴击率。 */
	private static final double CRIT_RATE_THRESHOLD = 0.85D;
	/** 向上位移次数 / 暴击次数 的下限，低于该比例才判定异常。 */
	private static final double CRIT_JUMP_RATIO = 0.5D;

	/* --- 违规等级 --- */
	private static final int WEIGHT_GROUND_LIE = 3;
	private static final int WEIGHT_FAKE_AIRBORNE = 2;
	private static final int WEIGHT_CRIT_HOVER = 3;
	private static final int WEIGHT_CRIT_GROUND = 3;
	private static final int WEIGHT_CRIT_RATE = 2;
	/** 达到该违规等级即踢出。 */
	private static final int VL_KICK = 12;
	/** 违规等级上限。 */
	private static final int VL_MAX = 20;
	/** 每次干净攻击衰减的违规等级。 */
	private static final int VL_DECAY_PER_CLEAN_HIT = 1;
	/** 无违规时每隔该时间衰减 1 点违规等级（毫秒）。 */
	private static final long VL_DECAY_INTERVAL_MS = 5000L;
	/** 数据空闲多久后被清理（毫秒）。 */
	private static final long DATA_IDLE_TIMEOUT_MS = 300_000L;

	/* --- 其它 --- */
	/** 加入 / 重生 / 传送后的免检时间（毫秒）。 */
	private static final long GRACE_MS = 3000L;
	/** 服务器 TPS 低于该值时暂停检测。 */
	private static final double MIN_TPS = 15.0D;

	private static final String LOG_PREFIX = "[No_Criticals] ";
	private static final String DEBUG_LOG_FORMAT = LOG_PREFIX + "{0} vl={1} [{2}]";
	private static final String PUNISH_LOG_FORMAT = LOG_PREFIX + "检测到 {0} 疑似使用刀暴（{1}），已踢出";

	private static final No_Criticals INSTANCE = new No_Criticals();

	/** 每个玩家的检测状态。 */
	private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

	/** 缓存的服务器 TPS，由 {@link #tick()} 更新，避免每次调用都分配数组。 */
	private volatile double cachedTps = 20.0D;

	private No_Criticals() {
	}

	/**
	 * 获取检测器单例。
	 */
	public static No_Criticals get() {
		return INSTANCE;
	}

	/* ==================== 对外函数（后续由监听器调用） ==================== */

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
		if (player != null) {
			players.remove(player.getUniqueId());
		}
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
	 * 玩家移动时调用（对应 {@code PlayerMoveEvent}），用于跟踪离地状态。
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

		// 飞行 / 滑翔 / 水中 / 攀爬等状态下的离地是合法的，重置悬浮跟踪
		if (isMovementExempt(player)) {
			data.resetAirborne(to.getY());
			data.groundLieBuffer = 0;
			return;
		}

		double deltaY = to.getY() - from.getY();
		data.lastDeltaY = deltaY;

		if (onGround) {
			data.resetAirborne(to.getY());
			data.groundLieBuffer = 0;
			return;
		}

		// ---- 累加离地状态（同时维护 Y 波动范围）----
		data.addAirborne(to.getY());
		if (deltaY > UPWARD_DY_THRESHOLD) {
			data.lastUpwardTime = now;
			data.sampleUpward++;
		}

		if (cachedTps < MIN_TPS) {
			return;
		}
		int weight = 0;
		StringBuilder debug = DEBUG ? new StringBuilder(32) : null;

		// ---- 检测 1：谎报离地 ----
		if (Math.abs(deltaY) < GROUND_LIE_MAX_DY && hasCollisionBelow(player)) {
			if (++data.groundLieBuffer >= GROUND_LIE_BUFFER_THRESHOLD) {
				data.groundLieBuffer = 0;
				weight += WEIGHT_GROUND_LIE;
				appendDebug(debug, "ground-lie");
			}
		} else {
			data.groundLieBuffer = 0;
		}

		// ---- 检测 2：长期悬浮（仅在战斗状态下统计）----
		if (!data.fakeAirborneReported && data.airborneTicks >= FAKE_AIRBORNE_TICKS
				&& now - data.lastAttackTime < COMBAT_WINDOW_MS
				&& data.airborneBand() < FAKE_AIRBORNE_MAX_BAND) {
			data.fakeAirborneReported = true;
			weight += WEIGHT_FAKE_AIRBORNE;
			appendDebug(debug, "fake-airborne");
		}

		if (weight > 0) {
			addViolation(player, data, weight, debug, now);
		}
	}

	/**
	 * 玩家攻击实体时调用（对应 {@code EntityDamageByEntityEvent}）。
	 *
	 * @param attacker 攻击者
	 * @param critical 本次伤害是否为暴击，直接传 {@code event.isCritical()} 即可
	 */
	public void onAttack(Player attacker, boolean critical) {
		if (attacker == null || isExempt(attacker)) {
			return;
		}
		long now = System.currentTimeMillis();
		PlayerData data = players.computeIfAbsent(attacker.getUniqueId(), key -> new PlayerData());
		data.lastSeenTime = now;
		data.lastAttackTime = now;
		if (now - data.graceStart < GRACE_MS) {
			return;
		}

		int weight = 0;
		StringBuilder debug = DEBUG ? new StringBuilder(32) : null;

		// ---- 检测 5：暴击率异常（采样窗口填满时评估，随后清零重新统计）----
		data.sampleAttacks++;
		if (critical) {
			data.sampleCrits++;
		}
		if (data.sampleAttacks >= CRIT_SAMPLE_SIZE) {
			if ((double) data.sampleCrits / data.sampleAttacks > CRIT_RATE_THRESHOLD
					&& data.sampleUpward < data.sampleCrits * CRIT_JUMP_RATIO) {
				weight += WEIGHT_CRIT_RATE;
				appendDebug(debug, "crit-rate");
			}
			data.sampleAttacks = 0;
			data.sampleCrits = 0;
			data.sampleUpward = 0;
		}

		if (!critical) {
			// 非暴击属于正常攻击，缓慢衰减违规等级
			if (data.vl > 0) {
				data.vl -= VL_DECAY_PER_CLEAN_HIT;
			}
			if (weight > 0) {
				addViolation(attacker, data, weight, debug, now);
			}
			return;
		}

		if (cachedTps >= MIN_TPS && !isMovementExempt(attacker)) {
			boolean airborne = !attacker.isOnGround();

			// ---- 检测 3：悬浮暴击（长时间离地 + Y 几乎不变 + 近期没有向上跳）----
			if (airborne && data.airborneTicks >= CRIT_HOVER_MIN_TICKS
					&& data.airborneBand() < CRIT_HOVER_MAX_BAND
					&& now - data.lastUpwardTime > CRIT_UPWARD_WINDOW_MS) {
				if (++data.critHoverBuffer >= CRIT_HOVER_BUFFER_THRESHOLD) {
					data.critHoverBuffer = 0;
					weight += WEIGHT_CRIT_HOVER;
					appendDebug(debug, "crit-hover");
				}
			} else {
				data.critHoverBuffer = 0;
			}

			// ---- 检测 4：地面暴击（声称在空中、原地不动，脚下却有碰撞方块）----
			if (airborne && Math.abs(data.lastDeltaY) < GROUND_LIE_MAX_DY && hasCollisionBelow(attacker)) {
				weight += WEIGHT_CRIT_GROUND;
				appendDebug(debug, "crit-on-ground");
			}
		}

		if (weight > 0) {
			addViolation(attacker, data, weight, debug, now);
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
			if (data.vl > 0 && now - data.lastViolationTime > VL_DECAY_INTERVAL_MS) {
				data.vl--;
				data.lastViolationTime = now;
			}
			if (now - data.lastSeenTime > DATA_IDLE_TIMEOUT_MS) {
				players.remove(entry.getKey(), data);
			}
		}
	}

	/**
	 * 清空指定玩家的全部检测数据（可用于 /ac reset 之类的管理指令）。
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
	 * <p>使用收窄的薄片包围盒与方块的碰撞形状做相交测试，
	 * 因此半砖 / 栅栏 / 楼梯都能正确识别，同时不会因为贴着墙而产生误判。</p>
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
			data.groundLieBuffer = 0;
			data.critHoverBuffer = 0;
		}
		MeteorfuckMod plugin = MeteorfuckMod.getInstance();
		if (plugin == null) {
			return;
		}
		log().log(Level.WARNING, PUNISH_LOG_FORMAT,
				new Object[] { player.getName(), CheckType.CRITICALS.getDisplayName() });
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
		data.groundLieBuffer = 0;
		data.critHoverBuffer = 0;
		data.resetAirborne(player.getLocation().getY());
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
	 * 判断当前 tick 的离地状态是否属于合法物理状态，需要跳过检测。
	 * 注意：气泡柱在 1.21.5 之后已并入 {@code isInWater()}，无需单独判断。
	 */
	private static boolean isMovementExempt(Player player) {
		return player.getAllowFlight() || player.isGliding() || player.isSwimming()
				|| player.isInWater() || player.isClimbing();
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

		/** 刀暴 / 暴击外挂。 */
		CRITICALS("刀暴");

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
		/** 最近一次攻击时间。 */
		long lastAttackTime;
		/** 最近一次违规时间。 */
		long lastViolationTime = System.currentTimeMillis();
		/** 最近一次收到动作的时间。 */
		long lastSeenTime = System.currentTimeMillis();
		/** 最近一次向上位移的时间。 */
		long lastUpwardTime;

		/** 最近一次移动的垂直位移。 */
		double lastDeltaY;
		/** 当前离地状态的 Y 坐标下界。 */
		double airborneMinY;
		/** 当前离地状态的 Y 坐标上界。 */
		double airborneMaxY;
		/** 连续离地的 tick 数。 */
		int airborneTicks;
		/** 本次离地期间是否已经上报过「长期悬浮」。 */
		boolean fakeAirborneReported;

		/** 谎报离地的连续计数。 */
		int groundLieBuffer;
		/** 悬浮暴击的连续计数。 */
		int critHoverBuffer;

		/** 暴击率采样窗口：攻击次数。 */
		int sampleAttacks;
		/** 暴击率采样窗口：暴击次数。 */
		int sampleCrits;
		/** 暴击率采样窗口：向上位移次数。 */
		int sampleUpward;

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
			fakeAirborneReported = false;
		}

		double airborneBand() {
			return airborneMaxY - airborneMinY;
		}
	}
}
