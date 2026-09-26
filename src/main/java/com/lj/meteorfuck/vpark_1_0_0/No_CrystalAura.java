package com.lj.meteorfuck.vpark_1_0_0;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.lj.meteorfuck.MeteorfuckMod;

/**
 * 反水晶光环（CrystalAura）检测器 —— 纯服务端实现。
 *
 * 本类只提供函数，不注册任何监听。由调度器在事件中转发：
 * 放置末地水晶时调用 {@link #onPlaceCrystal(Player, Entity)}（{@code EntityPlaceEvent}），
 * 攻击末地水晶时调用 {@link #onAttackCrystal(Player, Entity)}
 * （{@code EntityDamageByEntityEvent} 中目标为末地水晶），并每秒调用 {@link #tick()}。
 *
 * 服务端能看到的信号
 *
 * 水晶光环的原理是「自动放置末地水晶 + 立即攻击引爆」，服务端能看到的是放置与攻击两个事件，
 *
 * 1. 秒放秒爆 —— 从放下水晶到攻击它之间的间隔短到人类无法完成（默认 < 120 毫秒），
 *    并且连续出现。这是外挂最本质的特征；
 * 2. 放置速率异常 —— 1 秒内放下超过 12 个末地水晶（每个都要选中、瞄准、右键），
 *    正常人手速与「场上有效落点数量」都达不到；
 * 3. 引爆时没有看向水晶 —— 攻击瞬间视线方向与「眼睛 → 水晶」方向的夹角超过 70°。
 *    原版玩家必须先瞄准才能攻击，外挂则直接发包；
 * 4. 超距离引爆 —— 引爆时与水晶的距离超过 6 格，超出服务端允许的攻击距离。
 *
 * 如何区分正常行为
 *
 * - 只追踪玩家自己放置的水晶：打别人放的水晶属于正常水晶 PvP，一律不参与判定；
 * - 判定的所有信号都必须建立在「自放自爆」上，因此单纯的防守拆水晶不会被误判；
 * - 每条信号都需要连续命中缓冲区（2～3 次）才计违规，偶尔一次手快不算；
 * - 攻击距离与视角检查在高延迟（> 400ms）时整段跳过，避免网络抖动误伤；
 * - 放置速率检查在服务器 TPS 低于 15 时跳过，因为积压事件会在同一瞬间集中处理；
 * - 创造 / 旁观模式、{@code meteorfuck.bypass}（OP 默认拥有）完全免检；
 * - 加入 / 重生 / 传送后有一段宽限期，避免登录瞬间的补发包造成误判；
 * - 只有加权累计（VL）达到阈值才调用主类踢出，单一信号不会误杀。
 */
public final class No_CrystalAura {

	/* ==================== 可调参数 ==================== */

	/** 绕过检测的权限节点（OP 默认拥有该权限）。 */
	private static final String BYPASS_PERMISSION = "meteorfuck.bypass";

	/** 输出调试日志。 */
	private static final boolean DEBUG = false;

	/** 「秒放秒爆」的时间阈值（毫秒）：放下水晶到攻击它的间隔小于该值即视为异常。 */
	private static final long INSTANT_DETONATE_MS = 120L;
	/** 「秒放秒爆」需要连续命中的次数。 */
	private static final int INSTANT_BUFFER = 2;

	/** 放置速率统计窗口（毫秒）。 */
	private static final long PLACE_WINDOW_MS = 1000L;
	/** 统计窗口内允许的最大放置次数，超过即视为异常。 */
	private static final int PLACE_WINDOW_MAX = 12;

	/** 允许的最大攻击距离（格），超过即为超距离引爆。 */
	private static final double MAX_ATTACK_DISTANCE = 6.0D;
	/** 超距离引爆需要连续命中的次数。 */
	private static final int REACH_BUFFER = 2;

	/** 视线夹角余弦阈值，低于该值即视为「没有看向水晶」（cos 70° ≈ 0.34）。 */
	private static final double AIM_COS = 0.34D;
	/** 视角异常需要连续命中的次数。 */
	private static final int AIM_BUFFER = 3;

	/** 水晶追踪信息的存活时间（毫秒），超过即丢弃，避免长期占内存。 */
	private static final long TRACE_TTL_MS = 5000L;
	/** 允许忽略的最高延迟（毫秒）。 */
	private static final int MAX_PING = 400;
	/** 低于该 TPS 时跳过速率类判定。 */
	private static final double MIN_TPS = 15.0D;

	/* --- 违规等级 --- */
	/** 一次「秒放秒爆」的权重。 */
	private static final int WEIGHT_INSTANT = 4;
	/** 一次放置速率异常的权重。 */
	private static final int WEIGHT_RATE = 4;
	/** 一次「引爆未看水晶」的权重。 */
	private static final int WEIGHT_AIM = 3;
	/** 一次超距离引爆的权重。 */
	private static final int WEIGHT_REACH = 3;

	/** 达到该违规等级即踢出。 */
	private static final int VL_KICK = 12;
	/** 违规等级上限。 */
	private static final int VL_MAX = 20;
	/** 无违规时每隔该时间衰减 1 点违规等级（毫秒）。 */
	private static final long VL_DECAY_INTERVAL_MS = 5000L;
	/** 加入 / 重生 / 传送后的宽限期（毫秒）。 */
	private static final long GRACE_MS = 2000L;

	private static final String LOG_PREFIX = "[No_CrystalAura] ";
	private static final String DEBUG_LOG_FORMAT = LOG_PREFIX + "{0} vl={1} [{2}]";
	private static final String PUNISH_LOG_FORMAT = LOG_PREFIX + "检测到 {0} 疑似使用水晶光环（{1}），已踢出";

	private static final No_CrystalAura INSTANCE = new No_CrystalAura();

	/** 每个玩家的检测状态。 */
	private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
	/** 水晶追踪信息：水晶实体 UUID → 放置者与放置时间。 */
	private final Map<UUID, CrystalTrace> traces = new ConcurrentHashMap<>();

	private No_CrystalAura() {
	}

	/**
	 * 获取检测器单例。
	 */
	public static No_CrystalAura get() {
		return INSTANCE;
	}

	/* ==================== 对外函数（由调度器调用） ==================== */

	/**
	 * 玩家加入时调用，建立检测状态。
	 */
	public void onJoin(Player player) {
		if (isExempt(player)) {
			return;
		}
		PlayerData data = players.computeIfAbsent(player.getUniqueId(), key -> new PlayerData());
		data.graceUntil = System.currentTimeMillis() + GRACE_MS;
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
	 * 玩家重生时调用，进入宽限期。
	 */
	public void onRespawn(Player player) {
		grace(player);
	}

	/**
	 * 玩家传送时调用，进入宽限期（传送后位置与视角会出现跳变）。
	 */
	public void onTeleport(Player player) {
		grace(player);
	}

	/**
	 * 玩家切换世界时调用，进入宽限期并丢弃旧世界的水晶追踪信息。
	 */
	public void onChangedWorld(Player player) {
		grace(player);
	}

	/**
	 * 玩家放置末地水晶时调用（对应 {@code EntityPlaceEvent}）。
	 *
	 * @param player  放置者
	 * @param crystal 被放置的水晶实体
	 */
	public void onPlaceCrystal(Player player, Entity crystal) {
		if (player == null || crystal == null || isExempt(player)) {
			return;
		}
		long now = System.currentTimeMillis();
		traces.put(crystal.getUniqueId(), new CrystalTrace(player.getUniqueId(), now));

		PlayerData data = players.computeIfAbsent(player.getUniqueId(), key -> new PlayerData());
		if (now < data.graceUntil) {
			return;
		}

		// ---- 检测 2：一秒内放置量远超人类操作上限 ----
		if (now - data.placeWindowStart > PLACE_WINDOW_MS) {
			data.placeWindowStart = now;
			data.placeWindowCount = 0;
		}
		data.placeWindowCount++;
		if (data.placeWindowCount >= PLACE_WINDOW_MAX) {
			data.placeWindowCount = 0;
			data.placeWindowStart = now;
			if (Bukkit.getTPS()[0] >= MIN_TPS) {
				addViolation(player, data, WEIGHT_RATE, "rate", now);
			}
		}
	}

	/**
	 * 玩家攻击末地水晶时调用（对应 {@code EntityDamageByEntityEvent} 且目标为末地水晶）。
	 *
	 * @param player  攻击者
	 * @param crystal 被攻击的水晶实体
	 */
	public void onAttackCrystal(Player player, Entity crystal) {
		if (player == null || crystal == null || isExempt(player)) {
			return;
		}
		UUID crystalId = crystal.getUniqueId();
		CrystalTrace trace = traces.remove(crystalId);
		// 只检测「自放自爆」：打别人放的水晶属于正常水晶 PvP
		if (trace == null || !trace.placer.equals(player.getUniqueId())) {
			return;
		}

		long now = System.currentTimeMillis();
		PlayerData data = players.computeIfAbsent(player.getUniqueId(), key -> new PlayerData());
		if (now < data.graceUntil) {
			return;
		}

		// ---- 检测 1：秒放秒爆 ----
		if (now - trace.placeTime < INSTANT_DETONATE_MS) {
			if (++data.instantBuffer >= INSTANT_BUFFER) {
				data.instantBuffer = 0;
				addViolation(player, data, WEIGHT_INSTANT, "instant", now);
			}
		} else {
			data.instantBuffer = 0;
		}

		// ---- 检测 3 / 4：位置与视角（高延迟时整段跳过，宁可漏判） ----
		if (player.getPing() <= MAX_PING) {
			checkAimAndReach(player, crystal, data, now);
		}
	}

	/**
	 * 定期调用（建议每秒一次）：衰减违规等级、回收离线玩家与过期追踪信息。
	 */
	public void tick() {
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
			PlayerData data = entry.getValue();
			if (Bukkit.getPlayer(entry.getKey()) == null) {
				players.remove(entry.getKey(), data);
				continue;
			}
			// 长时间没有新增违规时逐步衰减，避免历史误报累积
			if (data.vl > 0 && now - data.lastViolationTime > VL_DECAY_INTERVAL_MS) {
				data.vl--;
				data.lastViolationTime = now;
			}
		}
		for (Map.Entry<UUID, CrystalTrace> entry : traces.entrySet()) {
			if (now - entry.getValue().placeTime > TRACE_TTL_MS) {
				traces.remove(entry.getKey(), entry.getValue());
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
	 * 视角与距离检查。
	 *
	 * 只有「自放自爆」的水晶才会走到这里。距离超限与视角异常互斥判断，
	 * 超距离时不再看视角，避免同一次操作叠加两条信号。
	 */
	private void checkAimAndReach(Player player, Entity crystal, PlayerData data, long now) {
		Location eye = player.getEyeLocation();
		Vector to = crystal.getLocation().toVector().subtract(eye.toVector());
		double distance = to.length();

		if (distance > MAX_ATTACK_DISTANCE) {
			data.aimBuffer = 0;
			if (++data.reachBuffer >= REACH_BUFFER) {
				data.reachBuffer = 0;
				addViolation(player, data, WEIGHT_REACH, "reach", now);
			}
			return;
		}
		data.reachBuffer = 0;

		if (distance < 0.05D) {
			return; // 几乎重合，方向无意义
		}
		double dot = to.multiply(1.0D / distance).dot(eye.getDirection());
		if (dot < AIM_COS) {
			if (++data.aimBuffer >= AIM_BUFFER) {
				data.aimBuffer = 0;
				addViolation(player, data, WEIGHT_AIM, "aim", now);
			}
		} else {
			data.aimBuffer = 0;
		}
	}

	/**
	 * 进入宽限期，重置连续命中缓冲区。
	 */
	private void grace(Player player) {
		if (isExempt(player)) {
			return;
		}
		PlayerData data = players.computeIfAbsent(player.getUniqueId(), key -> new PlayerData());
		data.graceUntil = System.currentTimeMillis() + GRACE_MS;
		data.instantBuffer = 0;
		data.aimBuffer = 0;
		data.reachBuffer = 0;
	}

	/**
	 * 判断玩家是否完全免检（创造 / 旁观 / 死亡 / 权限）。
	 */
	private static boolean isExempt(Player player) {
		if (player == null || player.isDead()) {
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
		data.instantBuffer = 0;
		data.aimBuffer = 0;
		data.reachBuffer = 0;
		MeteorfuckMod plugin = MeteorfuckMod.getInstance();
		if (plugin == null) {
			return;
		}
		log().log(Level.WARNING, PUNISH_LOG_FORMAT,
				new Object[] { player.getName(), CheckType.CRYSTAL_AURA.getDisplayName() });
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
	 * 检测项名称。
	 */
	public enum CheckType {

		/** 水晶光环。 */
		CRYSTAL_AURA("水晶光环");

		private final String displayName;

		CheckType(String displayName) {
			this.displayName = displayName;
		}

		public String getDisplayName() {
			return displayName;
		}
	}

	/**
	 * 单个水晶的放置追踪信息。
	 */
	private static final class CrystalTrace {

		private final UUID placer;
		private final long placeTime;

		private CrystalTrace(UUID placer, long placeTime) {
			this.placer = placer;
			this.placeTime = placeTime;
		}
	}

	/**
	 * 单个玩家的检测状态（只使用基本类型，热路径不产生多余对象）。
	 */
	private static final class PlayerData {

		private long lastViolationTime;
		private long graceUntil;

		/** 放置速率统计窗口起点。 */
		private long placeWindowStart;
		/** 放置速率统计窗口内的次数。 */
		private int placeWindowCount;

		private int instantBuffer;
		private int aimBuffer;
		private int reachBuffer;

		private int vl;
	}
}
