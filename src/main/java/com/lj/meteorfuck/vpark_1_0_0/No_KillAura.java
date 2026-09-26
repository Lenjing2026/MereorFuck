package com.lj.meteorfuck.vpark_1_0_0;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import com.lj.meteorfuck.MeteorfuckMod;

/**
 * KillAura（杀戮光环）检测器 —— 纯服务端实现。
 *
 * 本类只提供函数，不注册任何事件监听。后续由监听器 / 调度器调用
 * {@link #onAttack(Player, Entity, EntityDamageEvent.DamageCause)}、
 * {@link #onMove(Player, Location, Location)}、{@link #tick()} 等入口即可。
 *
 * 检测项
 *
 * 1. 非法俯仰角 / NaN 旋转 —— 正常客户端不可能产生，直接判定；
 * 2. 攻击角度 —— 视线与「目标包围盒上离眼睛最近的点」的夹角超过阈值；
 * 3. 多目标 —— 极短时间窗口内切换攻击多个不同实体；
 * 4. 攻击间隔规律性 —— 间隔标准差过小（机械式连击）；
 * 5. 自瞄 GCD —— 角度增量不存在真实鼠标应有的公约数（静默瞄准 / 瞬瞄）；
 * 6. 攻击距离 —— 超过原版合法攻击距离；
 * 7. 穿墙攻击 —— 视线被方块阻挡却仍然命中。
 *
 * 如何避免误伤正常辅助模组
 *
 * - 多信号加权累计（VL）后才踢出，单一信号绝不会误杀；
 * - 创造 / 旁观 / 濒死 / 骑乘 / 拥有 {@code meteorfuck.bypass} 权限的玩家完全免检；
 * - 加入、重生、传送后给予免检期；服务器 TPS 过低时暂停几何类检测；
 * - 视角判定使用「包围盒最近点」，避免抬头 / 低头 / 高低差造成的误差；
 * - 攻击距离按玩家延迟补偿；
 * - 视角 GCD 采用滚动窗口并在脱战后清空，避免一次异常采样永久污染结果；
 * - 昂贵的射线追踪只在玩家已有可疑积累时才执行。
 */
public final class No_KillAura {

	/* ==================== 可调参数 ==================== */

	/** 绕过检测的权限节点（OP 默认拥有该权限）。 */
	private static final String BYPASS_PERMISSION = "meteorfuck.bypass";

	/** 输出调试日志。 */
	private static final boolean DEBUG = false;

	/* --- 攻击角度 --- */
	/** 视线与目标允许的最大夹角（度）。 */
	private static final double MAX_ATTACK_ANGLE = 75.0D;
	/** 夹角余弦阈值（预计算，避免热路径反复调用三角函数）。 */
	private static final double COS_MAX_ATTACK_ANGLE = Math.cos(Math.toRadians(MAX_ATTACK_ANGLE));
	/** 低于该距离时不判定角度（贴脸攻击角度无意义）。 */
	private static final double MIN_ANGLE_CHECK_DISTANCE = 1.0D;
	/** 角度异常需要连续命中的次数。 */
	private static final int ANGLE_BUFFER_THRESHOLD = 2;

	/* --- 攻击距离 --- */
	/** 生存模式最大攻击距离（格）。 */
	private static final double MAX_REACH_SURVIVAL = 3.0D;
	/** 创造模式最大攻击距离（格）。 */
	private static final double MAX_REACH_CREATIVE = 5.0D;
	/** 距离判定的固定宽限（格）。 */
	private static final double REACH_TOLERANCE = 0.5D;
	/** 每毫秒延迟换算的距离补偿（格 / ms）。 */
	private static final double PING_REACH_FACTOR = 0.002D;
	/** 延迟补偿的封顶值（ms）。 */
	private static final int MAX_PING_COMPENSATION = 500;
	/** 距离异常需要连续命中的次数。 */
	private static final int REACH_BUFFER_THRESHOLD = 3;

	/* --- 多目标 --- */
	/** 多目标统计的时间窗口（毫秒）。 */
	private static final long MULTI_TARGET_WINDOW_MS = 250L;
	/** 窗口内允许出现的最大不同目标数。 */
	private static final int MULTI_TARGET_LIMIT = 2;

	/* --- 攻击间隔规律性 --- */
	/** 参与统计的最少间隔样本数。 */
	private static final int INTERVAL_MIN_SAMPLES = 8;
	/** 平均间隔超过该值时不做规律性判定（排除挂机 / 慢速点击）。 */
	private static final double INTERVAL_MAX_MEAN_MS = 500.0D;
	/** 间隔标准差低于该值（毫秒）视为机械式连击。 */
	private static final double INTERVAL_MIN_STDDEV = 8.0D;

	/* --- 自瞄 GCD --- */
	/** 是否启用自瞄 GCD 检测（该检测属于辅助信号，误报敏感的场景可关闭）。 */
	private static final boolean ENABLE_AIM_GCD = true;
	/** 视角采样滚动窗口大小。 */
	private static final int ROTATION_WINDOW = 40;
	/** 判定所需的最少采样数。 */
	private static final int AIM_MIN_SAMPLES = 25;
	/** 小于该值的公约数视为非真实鼠标输入。 */
	private static final double AIM_MIN_GCD = 1.0E-3D;
	/** 浮点 GCD 的收敛精度。 */
	private static final double GCD_EPSILON = 1.0E-5D;
	/** 单次转视角超过该角度视为不可信（传送、视角重置等），清空采样窗口。 */
	private static final float MAX_TRUSTED_ROTATION_DELTA = 90.0F;
	/** 小于该角度视为本 tick 没有转动。 */
	private static final float MIN_ROTATION_DELTA = 1.0E-3F;
	/** 脱战超过该时间后清空视角采样窗口（毫秒）。 */
	private static final long ROTATION_RESET_MS = 3000L;

	/* --- 非法旋转 --- */
	/** 俯仰角合法范围（含少量浮点容差）。 */
	private static final float MIN_VALID_PITCH = -90.05F;
	/** 俯仰角合法范围（含少量浮点容差）。 */
	private static final float MAX_VALID_PITCH = 90.05F;

	/* --- 穿墙 --- */
	/** 是否对每次攻击都做射线追踪（false 时仅在已有可疑度时执行，更省性能）。 */
	private static final boolean ALWAYS_CHECK_THROUGH_WALL = false;

	/* --- 违规等级 --- */
	/** 各类检测的权重。 */
	private static final int WEIGHT_ANGLE = 3;
	private static final int WEIGHT_MULTI_TARGET = 3;
	private static final int WEIGHT_THROUGH_WALL = 3;
	private static final int WEIGHT_AIM_GCD = 2;
	private static final int WEIGHT_REACH = 2;
	private static final int WEIGHT_INTERVAL = 1;
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
	/** 服务器 TPS 低于该值时暂停几何类检测。 */
	private static final double MIN_TPS = 15.0D;
	/** 攻击历史长度（多目标 / 间隔统计共用）。 */
	private static final int ATTACK_HISTORY = 12;

	private static final No_KillAura INSTANCE = new No_KillAura();

	/** 每个玩家的检测状态。 */
	private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

	/** 缓存的服务器 TPS，由 {@link #tick()} 更新，避免每次攻击都分配数组。 */
	private volatile double cachedTps = 20.0D;

	private No_KillAura() {
	}

	/**
	 * 获取检测器单例。
	 */
	public static No_KillAura get() {
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
	 * 玩家重生时调用，重置免检期与相关缓冲。
	 */
	public void onRespawn(Player player) {
		restart(player);
	}

	/**
	 * 玩家被传送时调用，重置免检期与相关缓冲。
	 */
	public void onTeleport(Player player) {
		restart(player);
	}

	/**
	 * 玩家移动 / 转头时调用（对应 {@code PlayerMoveEvent}），用于视角采样。
	 */
	public void onMove(Player player, Location from, Location to) {
		if (player == null || from == null || to == null) {
			return;
		}
		PlayerData data = players.get(player.getUniqueId());
		if (data == null) {
			// 未跟踪的玩家（免检 / 从未攻击）直接跳过，避免无谓开销
			return;
		}
		data.lastSeenTime = System.currentTimeMillis();

		float deltaYaw = Math.abs(to.getYaw() - from.getYaw());
		float deltaPitch = Math.abs(to.getPitch() - from.getPitch());
		if (deltaYaw > 180.0F) {
			deltaYaw = 360.0F - deltaYaw; // 处理 -180 / 180 环绕
		}
		// 大跳变不可信（传送、视角被插件重置等），清空采样窗口
		if (deltaYaw > MAX_TRUSTED_ROTATION_DELTA || deltaPitch > MAX_TRUSTED_ROTATION_DELTA) {
			data.clearRotation();
			return;
		}
		if (deltaYaw >= MIN_ROTATION_DELTA) {
			data.addRotation(deltaYaw);
		}
		if (deltaPitch >= MIN_ROTATION_DELTA) {
			data.addRotation(deltaPitch);
		}
	}

	/**
	 * 玩家攻击实体时调用（对应 {@code EntityDamageByEntityEvent}）。
	 *
	 * @param attacker 攻击者
	 * @param damaged  被攻击的实体
	 * @param cause    伤害原因，直接传 {@code event.getCause()}
	 */
	public void onAttack(Player attacker, Entity damaged, EntityDamageEvent.DamageCause cause) {
		if (attacker == null || damaged == null || isExempt(attacker)) {
			return;
		}
		// 横扫之刃会同时伤害多个实体，属于原版机制，不参与检测
		if (cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
			return;
		}

		long now = System.currentTimeMillis();
		PlayerData data = players.computeIfAbsent(attacker.getUniqueId(), key -> new PlayerData());
		data.lastSeenTime = now;
		if (now - data.graceStart < GRACE_MS) {
			return;
		}

		// ---- 1. 必检项：非法 / NaN 旋转，正常客户端不可能产生 ----
		float yaw = attacker.getYaw();
		float pitch = attacker.getPitch();
		if (Float.isNaN(yaw) || Float.isNaN(pitch) || pitch < MIN_VALID_PITCH || pitch > MAX_VALID_PITCH) {
			punish(attacker, CheckType.INVALID_ROTATION);
			return;
		}

		// ---- 2. 脱战后清空视角采样窗口，保证 GCD 只反映当前战斗 ----
		if (now - data.lastAttackTime > ROTATION_RESET_MS) {
			data.clearRotation();
		}
		data.lastAttackTime = now;

		// ---- 3. 记录攻击历史（环形缓冲，零分配）----
		data.attackTimes[data.attackCursor] = now;
		data.attackTargets[data.attackCursor] = damaged.getEntityId();
		data.attackCursor = (data.attackCursor + 1) % ATTACK_HISTORY;

		// ---- 4. 几何量只计算一次，供角度 / 距离 / 穿墙共用 ----
		Location eye = attacker.getEyeLocation();
		double eyeX = eye.getX();
		double eyeY = eye.getY();
		double eyeZ = eye.getZ();
		BoundingBox box = damaged.getBoundingBox();
		// 目标包围盒上离眼睛最近的点：比实体中心更宽容，可避免抬头 / 低头误判
		double nearX = clamp(eyeX, box.getMinX(), box.getMaxX());
		double nearY = clamp(eyeY, box.getMinY(), box.getMaxY());
		double nearZ = clamp(eyeZ, box.getMinZ(), box.getMaxZ());
		double dx = nearX - eyeX;
		double dy = nearY - eyeY;
		double dz = nearZ - eyeZ;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

		boolean lagging = cachedTps < MIN_TPS;
		int weight = 0;
		StringBuilder debug = DEBUG ? new StringBuilder(64) : null;

		// ---- 5. 逐项检测 ----
		if (!lagging && checkAttackAngle(data, yaw, pitch, dx, dy, dz, distance)) {
			weight += WEIGHT_ANGLE;
			appendDebug(debug, "angle");
		}
		if (!lagging && checkReach(attacker, data, distance)) {
			weight += WEIGHT_REACH;
			appendDebug(debug, "reach");
		}
		if (checkMultiTarget(data, now)) {
			weight += WEIGHT_MULTI_TARGET;
			appendDebug(debug, "multi-target");
		}
		if (!lagging && checkAttackInterval(data)) {
			weight += WEIGHT_INTERVAL;
			appendDebug(debug, "interval");
		}
		if (!lagging && checkAimGcd(data)) {
			weight += WEIGHT_AIM_GCD;
			appendDebug(debug, "aim-gcd");
		}
		// 射线追踪较贵：默认只在玩家已有可疑积累时才执行
		if ((ALWAYS_CHECK_THROUGH_WALL || data.vl + weight > 0) && !lagging
				&& checkThroughWall(attacker, eye, dx, dy, dz, distance)) {
			weight += WEIGHT_THROUGH_WALL;
			appendDebug(debug, "through-wall");
		}

		// ---- 6. 结算 ----
		if (weight <= 0) {
			// 干净的攻击：缓慢衰减，避免历史误报不断累积
			if (data.vl > 0) {
				data.vl -= VL_DECAY_PER_CLEAN_HIT;
			}
			return;
		}
		data.vl = Math.min(data.vl + weight, VL_MAX);
		data.lastViolationTime = now;
		if (DEBUG) {
			log().log(Level.INFO, DEBUG_LOG_FORMAT,
					new Object[] { attacker.getName(), data.vl, debug, distance });
		}
		if (data.vl >= VL_KICK) {
			punish(attacker, CheckType.KILL_AURA);
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

	/* ==================== 检测函数 ==================== */

	/**
	 * 攻击角度检测：视线方向与「目标包围盒最近点」方向的夹角是否超过阈值。
	 *
	 * 朝向向量按 {@code Location#getDirection()} 的公式直接由 yaw / pitch 计算，
	 * 省去每次攻击额外创建 {@link Location} 与 {@link Vector} 的开销。
	 */
	private static boolean checkAttackAngle(PlayerData data, float yaw, float pitch,
			double dx, double dy, double dz, double distance) {
		if (distance < MIN_ANGLE_CHECK_DISTANCE) {
			return false;
		}
		double radiansPitch = Math.toRadians(pitch);
		double radiansYaw = Math.toRadians(yaw);
		double cosPitch = Math.cos(radiansPitch);
		double lookX = -cosPitch * Math.sin(radiansYaw);
		double lookY = -Math.sin(radiansPitch);
		double lookZ = cosPitch * Math.cos(radiansYaw);

		// dx / dy / dz 已是指向最近点的向量，除以长度即得单位向量
		double dot = (dx * lookX + dy * lookY + dz * lookZ) / distance;
		if (dot >= COS_MAX_ATTACK_ANGLE) {
			data.angleBuffer = Math.max(0, data.angleBuffer - 1);
			return false;
		}
		if (++data.angleBuffer >= ANGLE_BUFFER_THRESHOLD) {
			data.angleBuffer = 0;
			return true;
		}
		return false;
	}

	/**
	 * 攻击距离检测：超出原版距离并按延迟补偿后仍然过大。
	 */
	private static boolean checkReach(Player player, PlayerData data, double distance) {
		double max = player.getGameMode() == GameMode.CREATIVE ? MAX_REACH_CREATIVE : MAX_REACH_SURVIVAL;
		max += REACH_TOLERANCE
				+ Math.min(player.getPing(), MAX_PING_COMPENSATION) * PING_REACH_FACTOR;
		if (distance <= max) {
			data.reachBuffer = Math.max(0, data.reachBuffer - 1);
			return false;
		}
		if (++data.reachBuffer >= REACH_BUFFER_THRESHOLD) {
			data.reachBuffer = 0;
			return true;
		}
		return false;
	}

	/**
	 * 多目标检测：统计时间窗口内出现过的不同目标数量。
	 * 使用环形缓冲两两比对，避免创建集合对象。
	 */
	private static boolean checkMultiTarget(PlayerData data, long now) {
		int distinct = 0;
		for (int i = 0; i < ATTACK_HISTORY; i++) {
			long time = data.attackTimes[i];
			if (time == 0L || now - time > MULTI_TARGET_WINDOW_MS) {
				continue;
			}
			int target = data.attackTargets[i];
			boolean duplicated = false;
			for (int j = 0; j < i; j++) {
				long other = data.attackTimes[j];
				if (other != 0L && now - other <= MULTI_TARGET_WINDOW_MS
						&& data.attackTargets[j] == target) {
					duplicated = true;
					break;
				}
			}
			if (!duplicated) {
				distinct++;
			}
		}
		return distinct > MULTI_TARGET_LIMIT;
	}

	/**
	 * 攻击间隔规律性检测：连续多次攻击的间隔标准差过小，说明是机械式连击。
	 * 从环形缓冲的最旧位置开始遍历，保证间隔按时间顺序计算。
	 */
	private static boolean checkAttackInterval(PlayerData data) {
		int sampleCount = 0;
		double sum = 0.0D;
		double sumSquares = 0.0D;
		long previous = -1L;
		for (int i = 0; i < ATTACK_HISTORY; i++) {
			long time = data.attackTimes[(data.attackCursor + i) % ATTACK_HISTORY];
			if (time == 0L) {
				continue;
			}
			if (previous >= 0L) {
				double interval = time - previous;
				sum += interval;
				sumSquares += interval * interval;
				sampleCount++;
			}
			previous = time;
		}
		if (sampleCount < INTERVAL_MIN_SAMPLES) {
			return false;
		}
		double mean = sum / sampleCount;
		// 只有高频连续攻击才做规律性判定，排除挂机 / 慢速点击
		if (mean > INTERVAL_MAX_MEAN_MS) {
			return false;
		}
		double variance = (sumSquares / sampleCount) - (mean * mean);
		double standardDeviation = variance <= 0.0D ? 0.0D : Math.sqrt(variance);
		return standardDeviation < INTERVAL_MIN_STDDEV;
	}

	/**
	 * 自瞄 GCD 检测：真实鼠标产生的角度增量必然存在一个公共步长，
	 * 静默瞄准 / 瞬瞄产生的增量几乎不存在公约数。
	 * 使用滚动窗口，避免单次异常采样永久污染结果。
	 */
	private static boolean checkAimGcd(PlayerData data) {
		if (!ENABLE_AIM_GCD || data.rotationCount < AIM_MIN_SAMPLES) {
			return false;
		}
		int samples = Math.min(data.rotationCount, ROTATION_WINDOW);
		double gcd = 0.0D;
		for (int i = 0; i < samples; i++) {
			gcd = gcdOf(gcd, data.rotationRing[i]);
		}
		return gcd < AIM_MIN_GCD;
	}

	/**
	 * 穿墙检测：从眼睛向目标最近的包围盒点做射线追踪，
	 * {@code ignorePassableBlocks = true} 可忽略草、火把等可穿过方块。
	 */
	private static boolean checkThroughWall(Player attacker, Location eye,
			double dx, double dy, double dz, double distance) {
		if (distance < 0.5D) {
			return false;
		}
		// 留出一点余量，避免目标紧贴墙面时误判
		double maxDistance = distance - 0.15D;
		if (maxDistance <= 0.0D) {
			return false;
		}
		Vector direction = new Vector(dx / distance, dy / distance, dz / distance);
		RayTraceResult result = attacker.getWorld().rayTraceBlocks(eye, direction, maxDistance,
				FluidCollisionMode.NEVER, true);
		return result != null && result.getHitBlock() != null;
	}

	/* ==================== 工具函数 ==================== */

	/**
	 * 处罚：记录日志并踢出玩家。攻击事件在主线程，其它情况自动调度回主线程。
	 */
	private void punish(Player player, CheckType type) {
		PlayerData data = players.get(player.getUniqueId());
		if (data != null) {
			data.vl = 0; // 重置，避免重复处罚
			data.angleBuffer = 0;
			data.reachBuffer = 0;
		}
		MeteorfuckMod plugin = MeteorfuckMod.getInstance();
		if (plugin == null) {
			return;
		}
		log().log(Level.WARNING, PUNISH_LOG_FORMAT,
				new Object[] { player.getName(), type.getDisplayName() });
		if (Bukkit.isPrimaryThread()) {
			// 踢出信息由主类从 main.yml 的 kick-message 读取
			plugin.KickMassage(player);
		} else {
			Bukkit.getScheduler().runTask(plugin, () -> plugin.KickMassage(player));
		}
	}

	/**
	 * 重置免检期与该玩家的相关缓冲。
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
		data.angleBuffer = 0;
		data.reachBuffer = 0;
		data.clearRotation();
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
	 * 浮点近似最大公约数，带迭代上限防止极端输入导致死循环。
	 */
	private static double gcdOf(double a, double b) {
		if (a < b) {
			double swap = a;
			a = b;
			b = swap;
		}
		for (int i = 0; i < 64 && b > GCD_EPSILON; i++) {
			double remainder = a % b;
			a = b;
			b = remainder;
		}
		return a;
	}

	private static double clamp(double value, double min, double max) {
		if (value < min) {
			return min;
		}
		return value > max ? max : value;
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

	private static final String LOG_PREFIX = "[No_KillAura] ";
	private static final String DEBUG_LOG_FORMAT = LOG_PREFIX + "{0} vl={1} [{2}] dist={3}";
	private static final String PUNISH_LOG_FORMAT = LOG_PREFIX + "检测到 {0} 疑似使用杀戮光环（{1}），已踢出";

	/**
	 * 检测类型，仅用于日志区分触发原因。
	 */
	public enum CheckType {

		/** 非法 / NaN 旋转。 */
		INVALID_ROTATION("非法旋转"),
		/** 综合违规等级超限。 */
		KILL_AURA("杀戮光环");

		private final String displayName;

		CheckType(String displayName) {
			this.displayName = displayName;
		}

		public String getDisplayName() {
			return displayName;
		}
	}

	/**
	 * 单个玩家的检测状态。所有缓冲均为定长数组，热路径不产生额外分配。
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

		/** 攻击历史环形缓冲。 */
		final long[] attackTimes = new long[ATTACK_HISTORY];
		/** 攻击目标（实体 ID）环形缓冲。 */
		final int[] attackTargets = new int[ATTACK_HISTORY];
		/** 攻击历史的写入游标（同时也是最旧元素下标）。 */
		int attackCursor;

		/** 视角增量采样环形缓冲。 */
		final float[] rotationRing = new float[ROTATION_WINDOW];
		/** 视角采样写入游标。 */
		int rotationCursor;
		/** 视角采样有效数量。 */
		int rotationCount;

		/** 违规等级。 */
		int vl;
		/** 角度异常连续计数。 */
		int angleBuffer;
		/** 距离异常连续计数。 */
		int reachBuffer;

		void addRotation(float delta) {
			rotationRing[rotationCursor] = delta;
			rotationCursor = (rotationCursor + 1) % ROTATION_WINDOW;
			if (rotationCount < ROTATION_WINDOW) {
				rotationCount++;
			}
		}

		void clearRotation() {
			rotationCursor = 0;
			rotationCount = 0;
		}
	}
}
