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
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import com.lj.meteorfuck.MeteorfuckMod;

/**
 * 反鞘翅飞行（ElytraFly / 平飞 / 悬停）检测器 —— 纯服务端实现。
 *
 * <p>本类<b>只提供函数</b>，不注册任何事件监听。由调度器调用
 * {@link #onMove(Player, Location, Location, boolean)}（建议每次 PlayerMoveEvent 都调用）
 * 与 {@link #tick()}（建议每秒一次）即可。</p>
 *
 * <h3>原版鞘翅的物理事实（判定基准）</h3>
 * <ul>
 *   <li>滑翔时只有重力与「沿视线方向」的速度重分配，<b>没有任何向上推力</b>；
 *       速度重分配只会让下降变缓，无法把垂直速度变成正值；</li>
 *   <li>因此原版滑翔<b>无法自行爬升</b>：想上升只能靠烟花火箭、三叉戟激流、
 *       漂浮药水、爆炸击退等外部推力；</li>
 *   <li>平视滑翔的稳定下降速度约 3 格 / 秒，<b>不可能长期保持高度或悬停</b>；</li>
 *   <li>高速飞行只能靠俯冲换速度，所以「不下降却很快」在原版不存在。</li>
 * </ul>
 *
 * <h3>检测项</h3>
 * <ol>
 *   <li><b>无推进爬升</b> —— 滑翔中垂直位移持续为正，或单 tick 爬升幅度超出原版可能；</li>
 *   <li><b>无重力悬停</b> —— 每秒采样一次 Y 坐标，连续两次几乎不动
 *       （外挂悬停时客户端往往根本不发移动包，因此这一检查放在 {@link #tick()} 中）；</li>
 *   <li><b>平飞</b> —— 不下降却保持高速（原版必须先俯冲才能加速）；</li>
 *   <li><b>无鞘翅滑翔</b> —— 服务端认为玩家在滑翔，胸甲却不是鞘翅（伪造滑翔包）。</li>
 * </ol>
 *
 * <h3>如何区分正常行为（重点）</h3>
 * <ul>
 *   <li>{@link Player#getAllowFlight()} / {@link Player#isFlying()}、创造与旁观模式、
 *       {@code meteorfuck.bypass}（OP 默认拥有）一律完全免检，插件给的飞行权限不会被误判；</li>
 *   <li>激流、水中、漂浮与缓降药水、受伤击退（{@code getNoDamageTicks()}）全部放行；</li>
 *   <li>附近存在烟花火箭实体时视为合法推进；该实体查询只在「即将判定」时才执行，
 *       不会给每个移动事件增加开销；</li>
 *   <li>爬升的同时水平速度在明显衰减时，视为「俯冲储能后拉起」这一原版合法操作，不判定；</li>
 *   <li>滑翔刚起步、加入 / 重生 / 传送之后、TPS 过低、延迟过高时都不检测；</li>
 *   <li>贴墙或卡在方块间的滑翔（Y 不变但被阻挡）会先做碰撞探测再决定是否判定；</li>
 *   <li>多信号<b>加权累计</b>（VL）后才踢出，单一信号绝不会误杀。</li>
 * </ul>
 */
public final class No_ElytraFly {

	/* ==================== 可调参数 ==================== */

	/** 绕过检测的权限节点（OP 默认拥有该权限）。 */
	private static final String BYPASS_PERMISSION = "meteorfuck.bypass";

	/** 输出调试日志。 */
	private static final boolean DEBUG = false;

	/* --- 无推进爬升 --- */
	/** 垂直位移超过该值即视为「在爬升」（格 / tick）。 */
	private static final double CLIMB_STEP_MIN = 0.02D;
	/** 单 tick 爬升超过该值（原版滑翔的任何操作都达不到），格 / tick。 */
	private static final double HARD_CLIMB_STEP = 0.15D;
	/** 快速爬升需要累积的 tick 数。 */
	private static final int HARD_CLIMB_BUFFER_THRESHOLD = 3;
	/** 缓慢但持续的爬升需要持续的时间（毫秒）。 */
	private static final long CLIMB_SUSTAIN_MS = 1000L;
	/** 爬升时水平速度每 tick 衰减超过该值，视为合法的俯冲储能拉起。 */
	private static final double MOMENTUM_DECAY_MIN = 0.01D;
	/** 滑翔刚开始时的免检时间（毫秒），用于吸收起跳时的向上初速度。 */
	private static final long GLIDE_START_GRACE_MS = 1000L;

	/* --- 无重力悬停 --- */
	/** 相邻两次采样之间 Y 坐标允许的变化（格 / 秒），原版平视滑翔约 3 格 / 秒。 */
	private static final double HOVER_TICK_BAND = 0.15D;
	/** 需要连续满足的采样次数（每次约 1 秒）。 */
	private static final int HOVER_TICK_THRESHOLD = 2;

	/* --- 平飞 --- */
	/** 水平位移超过该值（格 / tick，即 20 米 / 秒）才算「高速」。 */
	private static final double FLAT_SPEED_MIN = 1.0D;
	/** 允许的下降幅度（格 / tick），超过即说明在正常下降，不算平飞。 */
	private static final double FLAT_DESCENT_ALLOW = 0.015D;
	/** 平飞需要持续的时间（毫秒）。 */
	private static final long FLAT_SUSTAIN_MS = 700L;

	/* --- 无鞘翅滑翔 --- */
	/** 连续多少次采样（每次约 1 秒）滑翔却无鞘翅才判定。 */
	private static final int FAKE_GLIDE_THRESHOLD = 2;

	/* --- 推进物识别 --- */
	/** 探测到火箭后，多久内不再重复查询（毫秒）。 */
	private static final long BOOST_CACHE_MS = 1500L;
	/** 附近实体查询的半径（格）。 */
	private static final double FIREWORK_PROBE_RADIUS = 4.0D;

	/* --- 碰撞探测 --- */
	/** 碰撞探测包围盒向外扩张量（格）。 */
	private static final double OBSTRUCT_PROBE_INFLATE = 0.12D;

	/* --- 违规等级 --- */
	private static final int WEIGHT_CLIMB = 4;
	private static final int WEIGHT_HOVER = 4;
	private static final int WEIGHT_FLAT = 3;
	private static final int WEIGHT_FAKE_GLIDE = 3;
	/** 达到该违规等级即踢出。 */
	private static final int VL_KICK = 12;
	/** 违规等级上限。 */
	private static final int VL_MAX = 20;
	/** 无违规时每隔该时间衰减 1 点违规等级（毫秒）。 */
	private static final long VL_DECAY_INTERVAL_MS = 5000L;

	/* --- 其它 --- */
	/** 加入 / 重生 / 传送后的免检时间（毫秒）。 */
	private static final long GRACE_MS = 3000L;
	/** 延迟高于该值（毫秒）时跳过判定，避免位置插值失真造成误判。 */
	private static final int MAX_PING = 400;
	/** 服务器 TPS 低于该值时暂停检测。 */
	private static final double MIN_TPS = 15.0D;

	private static final String LOG_PREFIX = "[No_ElytraFly] ";
	private static final String DEBUG_LOG_FORMAT = LOG_PREFIX + "{0} vl={1} [{2}]";
	private static final String PUNISH_LOG_FORMAT = LOG_PREFIX + "检测到 {0} 疑似使用鞘翅飞行外挂（{1}），已踢出";

	private static final No_ElytraFly INSTANCE = new No_ElytraFly();

	/** 每个玩家的检测状态。 */
	private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

	/** 缓存的服务器 TPS，由 {@link #tick()} 更新，避免每次调用都分配数组。 */
	private volatile double cachedTps = 20.0D;

	private No_ElytraFly() {
	}

	/**
	 * 获取检测器单例。
	 */
	public static No_ElytraFly get() {
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
	 * 玩家移动时调用（对应 {@code PlayerMoveEvent}），爬升与平飞的主检测入口。
	 *
	 * <p>同一 tick 内的多次事件会先合并成一次完整位移再判定，因此结果与
	 * 事件频率无关；纯转头包（位移为 0）不会打断持续爬升的计时。</p>
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
		PlayerData data = players.computeIfAbsent(player.getUniqueId(), key -> new PlayerData());
		long now = System.currentTimeMillis();

		// 没有滑翔 → 属于普通飞行范畴，交给 No_Fly；这里只复位状态
		if (!player.isGliding() || isGlideExempt(player)) {
			data.endGlide();
			return;
		}
		if (now - data.graceStart < GRACE_MS) {
			return;
		}
		if (!data.gliding) {
			// 新的滑翔会话：清空上一次的累计值并记录起点
			data.endGlide();
			data.gliding = true;
			data.glideStartTime = now;
		}
		if (now - data.glideStartTime < GLIDE_START_GRACE_MS || player.getPing() > MAX_PING
				|| cachedTps < MIN_TPS) {
			return;
		}

		// ---- 把同一 tick 内的多次事件合并为一次完整位移 ----
		int tick = player.getTicksLived();
		double dy = to.getY() - from.getY();
		double dx = to.getX() - from.getX();
		double dz = to.getZ() - from.getZ();
		double dh = Math.sqrt(dx * dx + dz * dz);
		if (tick == data.lastTick) {
			data.tickDy += dy;
			data.tickDh += dh;
			return;
		}
		double stepDy = data.tickDy;
		double stepDh = data.tickDh;
		data.tickDy = dy;
		data.tickDh = dh;
		data.lastTick = tick;
		if (stepDy == 0.0D && stepDh == 0.0D) {
			return; // 上一个 tick 没有任何位移，无需判定
		}

		checkClimb(player, data, stepDy, stepDh, now);
		checkFlat(player, data, stepDy, stepDh, now);
		data.lastTickDh = stepDh;
	}

	/**
	 * 定期调用（建议每秒一次）：刷新 TPS 缓存、检测悬停与「无鞘翅滑翔」、衰减违规等级。
	 *
	 * <p>悬停只在客户端不发移动包时才难以被发现，所以必须由本方法按时间采样。</p>
	 */
	public void tick() {
		double[] tps = Bukkit.getTPS();
		if (tps.length > 0) {
			cachedTps = tps[0];
		}
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
			PlayerData data = entry.getValue();
			Player player = Bukkit.getPlayer(entry.getKey());
			if (player == null || !player.isOnline()) {
				players.remove(entry.getKey(), data);
				continue;
			}
			if (cachedTps >= MIN_TPS && now - data.graceStart >= GRACE_MS && player.getPing() <= MAX_PING) {
				checkGlideState(player, data, now);
			}
			// 长时间没有新增违规时逐步衰减，避免历史误报累积
			if (data.vl > 0 && now - data.lastViolationTime > VL_DECAY_INTERVAL_MS) {
				data.vl--;
				data.lastViolationTime = now;
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

	/* ==================== 内部检测 ==================== */

	/**
	 * 悬停与「无鞘翅滑翔」，由 {@link #tick()} 每秒采样一次。
	 */
	private void checkGlideState(Player player, PlayerData data, long now) {
		if (!player.isGliding() || isGlideExempt(player)) {
			data.endGlide();
			return;
		}

		// ---- 检测 4：服务端认为在滑翔，胸甲却不是鞘翅（伪造滑翔包）----
		if (hasElytra(player)) {
			data.fakeGlideTicks = 0;
		} else if (++data.fakeGlideTicks >= FAKE_GLIDE_THRESHOLD) {
			data.fakeGlideTicks = 0;
			addViolation(player, data, WEIGHT_FAKE_GLIDE, "no-elytra", now);
		}

		// ---- 检测 2：无重力悬停 ----
		double y = player.getLocation().getY();
		if (!data.hasSampleY) {
			data.hasSampleY = true;
			data.sampleY = y;
			return;
		}
		double moved = Math.abs(y - data.sampleY);
		data.sampleY = y;
		if (moved > HOVER_TICK_BAND) {
			data.hoverTicks = 0;
			return;
		}
		if (++data.hoverTicks >= HOVER_TICK_THRESHOLD && !isObstructed(player)
				&& !propelled(player, data, now)) {
			data.hoverTicks = 0;
			addViolation(player, data, WEIGHT_HOVER, "hover", now);
		}
	}

	/**
	 * 检测 1：无推进爬升。
	 *
	 * @param dy 该 tick 的垂直位移
	 * @param dh 该 tick 的水平位移
	 */
	private void checkClimb(Player player, PlayerData data, double dy, double dh, long now) {
		// 爬升的同时水平速度在明显衰减 → 原版「俯冲储能后拉起」，属于合法操作
		if (dy > CLIMB_STEP_MIN && dh < data.lastTickDh - MOMENTUM_DECAY_MIN) {
			data.climbSince = 0L;
			data.climbBuffer = 0;
			return;
		}

		if (dy > HARD_CLIMB_STEP) {
			data.climbSince = 0L;
			if (++data.climbBuffer >= HARD_CLIMB_BUFFER_THRESHOLD) {
				data.climbBuffer = 0;
				if (!propelled(player, data, now)) {
					addViolation(player, data, WEIGHT_CLIMB, "climb-hard", now);
				}
			}
			return;
		}
		if (dy > CLIMB_STEP_MIN) {
			if (data.climbSince == 0L) {
				data.climbSince = now;
				return;
			}
			if (now - data.climbSince >= CLIMB_SUSTAIN_MS) {
				data.climbSince = now;
				if (!propelled(player, data, now)) {
					addViolation(player, data, WEIGHT_CLIMB, "climb", now);
				}
			}
			return;
		}
		if (dy < -CLIMB_STEP_MIN) {
			// 只有确实在下降时才清零，避免纯转头包打断持续爬升的计时
			data.climbSince = 0L;
			data.climbBuffer = Math.max(0, data.climbBuffer - 1);
		}
	}

	/**
	 * 检测 3：平飞 —— 不下降却保持高速。
	 */
	private void checkFlat(Player player, PlayerData data, double dy, double dh, long now) {
		if (dh < FLAT_SPEED_MIN || dy < -FLAT_DESCENT_ALLOW) {
			data.flatSince = 0L;
			return;
		}
		if (data.flatSince == 0L) {
			data.flatSince = now;
			return;
		}
		if (now - data.flatSince < FLAT_SUSTAIN_MS) {
			return;
		}
		data.flatSince = now;
		if (!isObstructed(player) && !propelled(player, data, now)) {
			addViolation(player, data, WEIGHT_FLAT, "flat", now);
		}
	}

	/**
	 * 判断玩家是否处于合法的「被推进」状态：最近确认过烟花火箭、刚被击退、
	 * 或此刻附近确实有火箭实体。
	 *
	 * <p>附近实体查询是这里唯一较贵的操作，因此只在规则已经确认违规、
	 * 即将处罚时才会执行（{@code boostUntil} 缓存进一步避免重复查询）。</p>
	 */
	private static boolean propelled(Player player, PlayerData data, long now) {
		if (now < data.boostUntil) {
			return true; // 最近 1.5 秒内已确认存在火箭助推
		}
		if (player.getNoDamageTicks() > 0) {
			return true; // 刚受伤 / 被爆炸击退
		}
		for (Entity entity : player.getNearbyEntities(FIREWORK_PROBE_RADIUS, FIREWORK_PROBE_RADIUS,
				FIREWORK_PROBE_RADIUS)) {
			if (entity instanceof Firework) {
				data.boostUntil = now + BOOST_CACHE_MS;
				return true;
			}
		}
		return false;
	}

	/**
	 * 判断玩家是否处于合法物理状态，需要跳过检测。
	 */
	private static boolean isGlideExempt(Player player) {
		if (player.isInsideVehicle() || player.isInWater() || player.isClimbing()) {
			return true;
		}
		if (player.isRiptiding()) {
			return true;
		}
		return player.hasPotionEffect(PotionEffectType.LEVITATION)
				|| player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
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
	 * 判断胸甲槽位是否为鞘翅。
	 *
	 * <p>Paper 的 {@code getChestplate()} 标注为非空，空槽位返回 AIR，因此无需判空。</p>
	 */
	private static boolean hasElytra(Player player) {
		ItemStack chest = player.getInventory().getChestplate();
		return chest.getType() == Material.ELYTRA;
	}

	/**
	 * 判断玩家周围（含脚下一小段）是否存在实体碰撞方块。
	 *
	 * <p>用于排除「贴墙 / 卡在方块里导致 Y 不变」这种合法情况，
	 * 只在规则即将判定时才会被调用。</p>
	 */
	private static boolean isObstructed(Player player) {
		BoundingBox box = player.getBoundingBox();
		double minX = box.getMinX() - OBSTRUCT_PROBE_INFLATE;
		double maxX = box.getMaxX() + OBSTRUCT_PROBE_INFLATE;
		double minZ = box.getMinZ() - OBSTRUCT_PROBE_INFLATE;
		double maxZ = box.getMaxZ() + OBSTRUCT_PROBE_INFLATE;
		BoundingBox probe = new BoundingBox(minX, box.getMinY() - OBSTRUCT_PROBE_INFLATE, minZ, maxX,
				box.getMaxY(), maxZ);

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
		data.boostUntil = 0L;
		data.graceStart = System.currentTimeMillis();
		data.endGlide();
		MeteorfuckMod plugin = MeteorfuckMod.getInstance();
		if (plugin == null) {
			return;
		}
		log().log(Level.WARNING, PUNISH_LOG_FORMAT,
				new Object[] { player.getName(), CheckType.ELYTRA_FLY.getDisplayName() });
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
		data.boostUntil = 0L;
		data.endGlide();
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

		/** 鞘翅飞行外挂（平飞 / 悬停 / 无推进爬升）。 */
		ELYTRA_FLY("鞘翅飞行");

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
		/** 火箭助推缓存截止时间。 */
		long boostUntil;

		/** 是否处于一次滑翔会话中。 */
		boolean gliding;
		/** 本次滑翔会话的起点。 */
		long glideStartTime;

		/** 最近处理过的 ticksLived，用于合并同一 tick 内的多次事件。 */
		int lastTick;
		/** 当前 tick 已累计的垂直位移。 */
		double tickDy;
		/** 当前 tick 已累计的水平位移。 */
		double tickDh;
		/** 上一个 tick 的水平位移，用于判断爬升是否在消耗动能。 */
		double lastTickDh;

		/** 持续爬升的计时起点。 */
		long climbSince;
		/** 快速爬升的连续计数。 */
		int climbBuffer;
		/** 持续平飞的计时起点。 */
		long flatSince;

		/** 悬停采样的连续命中次数。 */
		int hoverTicks;
		/** 是否已经采集过悬停样本。 */
		boolean hasSampleY;
		/** 上一次悬停采样的 Y 坐标。 */
		double sampleY;
		/** 滑翔却无鞘翅的连续采样次数。 */
		int fakeGlideTicks;

		/** 违规等级。 */
		int vl;

		/** 结束（或清空）一次滑翔会话的全部累计状态。 */
		void endGlide() {
			gliding = false;
			climbSince = 0L;
			climbBuffer = 0;
			flatSince = 0L;
			hoverTicks = 0;
			hasSampleY = false;
			fakeGlideTicks = 0;
			tickDy = 0.0D;
			tickDh = 0.0D;
			lastTickDh = 0.0D;
		}
	}
}
