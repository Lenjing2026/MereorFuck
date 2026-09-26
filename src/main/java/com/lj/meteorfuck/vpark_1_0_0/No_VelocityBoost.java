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

import com.lj.meteorfuck.MeteorfuckMod;

/**
 * 反击退修改（VelocityBoost / Velocity）检测器 —— 纯服务端实现。
 *
 * 本类只提供函数，不注册任何事件监听。由调度器调用
 * {@link #onDamage(Player, Entity, boolean)}（任何伤害事件都调用一次，近战传入攻击者）
 * 与 {@link #onMove(Player, Location, Location)}（建议每次 PlayerMoveEvent 都调用），
 * 并每秒调用 {@link #tick()} 即可。
 *
 * 原理
 *
 * 服务端对玩家施加击退时，是把速度通过数据包发给客户端、由客户端自己移动的，
 * 玩家的位置上报完全由客户端掌握，所以「减少击退 / 增强击退」类外挂可以随意改动实际位移。
 * 但有一个原版数据包无法回避的事实：玩家站在地面上被冲刺近战命中时，
 * 服务端会把竖直速度设为 0.4，客户端会因此被弹起约 0.5～0.75 格。
 *
 * 本检测只在「原版必定弹起」的场合测量：受害者贴地、攻击者处于冲刺近战状态
 * （疾跑 + 攻击冷却已恢复），然后对比受伤后一段时间内受害者实际上升的高度。
 * 完全没有上升 → 击退被削弱 / 免疫；上升得离谱 → 击退被放大。
 *
 * 检测项
 *
 * 1. 击退被削弱 / 免疫 —— 测量窗口内上升量不足 0.12 格（原版应约 0.5 格以上）；
 * 2. 击退被放大 —— 测量窗口内上升量超过 3 格（原版「跳跃 + 击退」叠加约 2 格，
 *    只有明显放大（数倍）才会触发，避免与跳跃混在一起误判）。
 *
 * 如何区分正常行为
 *
 * - 只统计玩家冲刺近战造成的击退：攻击者必须疾跑且攻击冷却已恢复
 *   （{@code getAttackCooldown() >= 0.9}），否则原版本来就不给竖直弹起；
 * - 受害者必须贴地：跳跃中 / 被击退中（{@code getNoDamageTicks() > 10}，此时伤害被原版忽略）
 *   都不会进入测量；
 * - 测量窗口内出现任何其它来源的伤害（爆炸、投射物、摔落…）会直接作废本次测量；
 * - 窗口内没有拿到任何移动样本（客户端没发包）时保守放行，宁可漏判；
 * - 头顶净空不足（隧道 / 天花板）、蜘蛛网 / 细雪、水中 / 岩浆 / 攀爬 / 滑翔 / 激流 / 骑乘 /
 *   漂浮与缓降药水，以及举盾 / 吃食物 / 拉弓等使用物品状态，全部放行；
 * - 窗口长度按受害者延迟自动延长，高延迟玩家不会因为数据包迟到而误判；
 * - 创造 / 旁观 / 死亡 / 拥有 {@code meteorfuck.bypass}（OP 默认拥有）完全免检；
 * - 加入、重生、传送后给予免检期；服务器 TPS 过低时暂停判定；
 * - 多信号加权累计（VL）后才踢出，单一信号绝不会误杀；
 * - 注意：若服务器上还有其它插件会修改击退（自定义击退、竞技场 / 反作弊插件），
 *   或者玩家通过 Geyser 等基岩版代理进入，请直接关闭本模块。
 */
public final class No_VelocityBoost {

	/* ==================== 可调参数 ==================== */

	/** 绕过检测的权限节点（OP 默认拥有该权限）。 */
	private static final String BYPASS_PERMISSION = "meteorfuck.bypass";

	/** 输出调试日志。 */
	private static final boolean DEBUG = false;

	/* --- 测量窗口 --- */
	/** 基础测量窗口（tick）：原版弹起的上升段约 3 tick，8 tick 足够覆盖完整过程。 */
	private static final int WINDOW_TICKS = 8;
	/** 每多少毫秒延迟补 1 tick，用于等待击退数据包与位置包往返。 */
	private static final int PING_TICK_DIVISOR = 50;

	/* --- 进入测量的前提 --- */
	/** 攻击冷却恢复程度下限（低于它原版不会触发冲刺击退）。 */
	private static final float MIN_ATTACK_COOLDOWN = 0.9F;
	/** 受害者被原版忽略伤害的无敌时间下限（tick），超过说明这次命中本来就不会产生击退。 */
	private static final int IGNORE_DAMAGE_TICKS = 10;

	/* --- 判定阈值 --- */
	/** 上升量合格下限（格）：原版地面击退会把玩家弹起 0.5 格以上，留足余量。 */
	private static final double MIN_RISE = 0.12D;
	/** 判定「被放大」的上升量（格）：「跳跃 + 击退」叠加约 2 格。 */
	private static final double BOOST_RISE = 3.0D;
	/** 击退被削弱需要连续命中的次数。 */
	private static final int MISSING_BUFFER_THRESHOLD = 2;
	/** 击退被放大需要连续命中的次数。 */
	private static final int BOOST_BUFFER_THRESHOLD = 2;

	/* --- 违规等级 --- */
	private static final int WEIGHT_MISSING = 4;
	private static final int WEIGHT_BOOST = 3;
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
	/** 延迟高于该值（毫秒）时暂停判定。 */
	private static final int MAX_PING = 300;
	/** 服务器 TPS 低于该值时暂停判定。 */
	private static final double MIN_TPS = 15.0D;

	private static final String LOG_PREFIX = "[No_VelocityBoost] ";
	private static final String DEBUG_LOG_FORMAT = LOG_PREFIX + "{0} vl={1} [{2}] rise={3}";
	private static final String PUNISH_LOG_FORMAT = LOG_PREFIX + "检测到 {0} 疑似使用击退修改（{1}），已踢出";

	private static final No_VelocityBoost INSTANCE = new No_VelocityBoost();

	/** 每个玩家的检测状态。 */
	private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

	/** 缓存的服务器 TPS，由 {@link #tick()} 更新，避免每次调用都分配数组。 */
	private volatile double cachedTps = 20.0D;

	private No_VelocityBoost() {
	}

	/**
	 * 获取检测器单例。
	 */
	public static No_VelocityBoost get() {
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
	 * 玩家受到伤害时调用（对应 {@code EntityDamageEvent}，任何原因都调一次）。
	 *
	 * @param victim   受伤的玩家
	 * @param attacker 直接攻击者，非「玩家近战」时传 {@code null}
	 * @param melee    本次伤害是否为玩家直接近战（{@code ENTITY_ATTACK}）
	 */
	public void onDamage(Player victim, Entity attacker, boolean melee) {
		if (victim == null || isExempt(victim)) {
			return;
		}
		long now = System.currentTimeMillis();
		PlayerData data = players.computeIfAbsent(victim.getUniqueId(), key -> new PlayerData());
		data.lastSeenTime = now;

		if (!melee) {
			// 其它来源（爆炸、投射物、摔落…）都会改变位移，正在进行的测量直接作废
			data.windowTicks = 0;
			return;
		}
		data.windowTicks = 0;
		if (now - data.graceStart < GRACE_MS) {
			return;
		}
		// 只有「冲刺近战 + 受害者贴地」才必然产生竖直弹起，其余情况原版本来就不弹
		if (!(attacker instanceof Player attackerPlayer) || !attackerPlayer.isSprinting()
				|| attackerPlayer.hasActiveItem()
				|| attackerPlayer.getAttackCooldown() < MIN_ATTACK_COOLDOWN) {
			return;
		}
		if (!victim.isOnGround() || victim.hasActiveItem() || victim.isInsideVehicle()
				|| victim.isInWater() || victim.isInLava() || victim.isClimbing() || victim.isGliding()
				|| victim.isRiptiding() || victim.getNoDamageTicks() > IGNORE_DAMAGE_TICKS) {
			return;
		}

		// 开始一次测量
		data.yAtHit = victim.getLocation().getY();
		data.maxRise = Double.NEGATIVE_INFINITY;
		data.measured = false;
		data.lastTick = 0;
		data.windowTicks = WINDOW_TICKS + Math.min(victim.getPing(), MAX_PING) / PING_TICK_DIVISOR;
	}

	/**
	 * 玩家移动时调用（对应 {@code PlayerMoveEvent}），用于采样击退后的实际上升量。
	 */
	public void onMove(Player player, Location from, Location to) {
		if (player == null || from == null || to == null) {
			return;
		}
		PlayerData data = players.get(player.getUniqueId());
		if (data == null || data.windowTicks <= 0) {
			// 未跟踪的玩家 / 不在测量窗口：直接跳过，避免无谓开销
			return;
		}
		data.lastSeenTime = System.currentTimeMillis();

		double rise = to.getY() - data.yAtHit;
		if (rise > data.maxRise) {
			data.maxRise = rise;
		}
		data.measured = true;

		// 按 tick 计数：同一 tick 内的多次事件（含纯转头包）只消耗一个窗口 tick
		int tick = player.getTicksLived();
		if (tick == data.lastTick) {
			return;
		}
		data.lastTick = tick;
		if (--data.windowTicks <= 0) {
			settle(player, data);
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
	 * 窗口结束时结算这次击退的实际上升量。
	 */
	private void settle(Player player, PlayerData data) {
		data.windowTicks = 0;
		if (!data.measured || cachedTps < MIN_TPS || player.getPing() > MAX_PING) {
			return; // 没有样本 / 环境不可信 → 保守放行
		}
		long now = System.currentTimeMillis();

		if (data.maxRise < MIN_RISE) {
			if (isRiseBlocked(player)) {
				return; // 头顶被挡住 / 卡在蜘蛛网里，弹不起来属于正常
			}
			if (++data.missingBuffer >= MISSING_BUFFER_THRESHOLD) {
				data.missingBuffer = 0;
				addViolation(player, data, WEIGHT_MISSING, "no-knockback", now);
			}
			return;
		}
		data.missingBuffer = 0;

		if (data.maxRise > BOOST_RISE) {
			if (++data.boostBuffer >= BOOST_BUFFER_THRESHOLD) {
				data.boostBuffer = 0;
				addViolation(player, data, WEIGHT_BOOST, "boost", now);
			}
		} else {
			data.boostBuffer = Math.max(0, data.boostBuffer - 1);
		}
	}

	/**
	 * 判断当前状态是否会让「弹不起来」成为原版行为，需要放行。
	 */
	private static boolean isRiseBlocked(Player player) {
		if (player.isInWater() || player.isInLava() || player.isClimbing() || player.isGliding()
				|| player.isRiptiding() || player.isInsideVehicle()) {
			return true;
		}
		if (player.hasPotionEffect(PotionEffectType.LEVITATION)
				|| player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
			return true;
		}
		Location location = player.getLocation();
		World world = player.getWorld();
		int x = location.getBlockX();
		int y = location.getBlockY();
		int z = location.getBlockZ();
		if (isVelocityBlock(location.getBlock()) || isVelocityBlock(world.getBlockAt(x, y + 1, z))) {
			return true; // 蜘蛛网 / 细雪会直接抹掉速度
		}
		// 头顶净空不足（2 格高的隧道 / 天花板下方），原版也弹不起来
		return !world.getBlockAt(x, y + 2, z).isPassable();
	}

	private static boolean isVelocityBlock(Block block) {
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
		data.windowTicks = 0;
		data.measured = false;
		data.maxRise = Double.NEGATIVE_INFINITY;
		data.missingBuffer = 0;
		data.boostBuffer = 0;
	}

	/**
	 * 判断玩家是否完全免检。
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
			log().log(Level.INFO, DEBUG_LOG_FORMAT,
					new Object[] { player.getName(), data.vl, reason, data.maxRise });
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
		data.windowTicks = 0;
		data.measured = false;
		data.missingBuffer = 0;
		data.boostBuffer = 0;
		MeteorfuckMod plugin = MeteorfuckMod.getInstance();
		if (plugin == null) {
			return;
		}
		log().log(Level.WARNING, PUNISH_LOG_FORMAT,
				new Object[] { player.getName(), CheckType.VELOCITY_BOOST.getDisplayName() });
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

		/** 击退修改（VelocityBoost / Velocity）。 */
		VELOCITY_BOOST("击退修改");

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

		/** 受伤瞬间的 Y 坐标。 */
		double yAtHit;
		/** 本次窗口中观测到的最大上升量（格）。 */
		double maxRise = Double.NEGATIVE_INFINITY;

		/** 剩余测量窗口（tick），0 表示不在测量中。 */
		int windowTicks;
		/** 最近处理过的 ticksLived。 */
		int lastTick;
		/** 窗口内是否拿到过移动样本。 */
		boolean measured;

		/** 击退被削弱的连续计数。 */
		int missingBuffer;
		/** 击退被放大的连续计数。 */
		int boostBuffer;

		/** 违规等级。 */
		int vl;
	}
}
