package com.lj.meteorfuck.vpark_1_0_0;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import com.lj.meteorfuck.MeteorfuckMod;

/**
 * 反透视（X-Ray）检测器 —— 纯服务端实现。
 *
 * <p>本类<b>只提供函数</b>，不注册任何监听。由调度器在 {@code BlockBreakEvent} 中调用
 * {@link #onBlockBreak(Player, Block, Material)}，并每秒调用 {@link #tick()} 即可。</p>
 *
 * <h3>服务端能看到的信号</h3>
 * <p>透视客户端不会向服务端暴露任何额外信息，服务端唯一能观察到的是「破坏了哪些方块」，
 * 因此检测只能建立在行为统计上：</p>
 * <ol>
 *   <li><b>挖到未暴露的稀有矿</b> —— 被破坏的矿石六个面全是实体方块，没有任何空气 / 水可达的面。
 *       原版玩家只能挖到自己看得见的方块，这种矿石正常情况下根本挖不到，
 *       只有透视外挂才会「隔墙直挖」（最不容易误判的信号）；</li>
 *   <li><b>稀有矿命中率异常</b> —— 按固定采样窗口统计「挖到的方块」中稀有矿（钻石 / 绿宝石 / 远古残骸）
 *       的占比。正常深板岩层挖矿约 1%～3%，透视外挂普遍在 20% 以上。</li>
 * </ol>
 *
 * <h3>如何区分正常行为</h3>
 * <ul>
 *   <li>只统计玩家主动破坏的方块：爆炸、活塞、命令、自然破坏都不会进入统计；</li>
 *   <li>只把钻石、绿宝石、远古残骸算作稀有矿；金 / 铁 / 铜 / 下界金矿等常见矿不计入，
 *       避免下界挖金、山地挖铁被误判；</li>
 *   <li>命中率检查必须累计满一整个采样窗口（默认 120 个方块）且稀有矿数量达到下限才判定，
 *       偶尔撞上一条大矿脉不会触发；</li>
 *   <li>创造 / 旁观模式、{@code meteorfuck.bypass}（OP 默认拥有）完全免检；</li>
 *   <li>换世界 / 加入 / 重生后清空统计窗口，避免跨环境数据串味；
 *       长时间不挖矿也会重置窗口并衰减违规等级；</li>
 *   <li>挖到未暴露矿石时若相邻区块尚未加载、或位于世界边界，一律保守放行；</li>
 *   <li>只有加权累计（VL）达到阈值才调用主类踢出，单一信号不会误杀。</li>
 * </ul>
 */
public final class No_XRay {

	/* ==================== 可调参数 ==================== */

	/** 绕过检测的权限节点（OP 默认拥有该权限）。 */
	private static final String BYPASS_PERMISSION = "meteorfuck.bypass";

	/** 输出调试日志。 */
	private static final boolean DEBUG = false;

	/** 采样窗口大小（多少个方块结算一次命中率）。 */
	private static final int SAMPLE_SIZE = 120;
	/** 窗口内稀有矿的绝对数量下限。 */
	private static final int MIN_RARE_COUNT = 8;
	/** 窗口内稀有矿占比阈值。 */
	private static final double RARE_RATIO = 0.05D;
	/** 超过该时间没有挖矿就重置统计窗口（毫秒）。 */
	private static final long WINDOW_IDLE_RESET_MS = 300_000L;

	/* --- 违规等级 --- */
	/** 挖到一块未暴露稀有矿的权重。 */
	private static final int WEIGHT_UNEXPOSED = 3;
	/** 一个命中率异常窗口的权重。 */
	private static final int WEIGHT_RATIO = 5;
	/** 达到该违规等级即踢出。 */
	private static final int VL_KICK = 12;
	/** 违规等级上限。 */
	private static final int VL_MAX = 20;
	/** 无违规时每隔该时间衰减 1 点违规等级（毫秒）。 */
	private static final long VL_DECAY_INTERVAL_MS = 10_000L;

	/** 六个方向，静态复用，热路径不产生数组分配。 */
	private static final BlockFace[] FACES = { BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
			BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST };

	private static final String LOG_PREFIX = "[No_XRay] ";
	private static final String DEBUG_LOG_FORMAT = LOG_PREFIX + "{0} vl={1} [{2}]";
	private static final String PUNISH_LOG_FORMAT = LOG_PREFIX + "检测到 {0} 疑似使用透视（{1}），已踢出";

	private static final No_XRay INSTANCE = new No_XRay();

	/** 每个玩家的检测状态。 */
	private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

	private No_XRay() {
	}

	/**
	 * 获取检测器单例。
	 */
	public static No_XRay get() {
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
	 * 玩家切换世界时调用，清空统计窗口（不同世界矿脉分布完全不同）。
	 */
	public void onChangedWorld(Player player) {
		if (player == null) {
			return;
		}
		PlayerData data = players.get(player.getUniqueId());
		if (data != null) {
			data.windowTotal = 0;
			data.windowRare = 0;
		}
	}

	/**
	 * 玩家破坏方块时调用（对应 {@code BlockBreakEvent}，事件未被取消时）。
	 *
	 * <p>建议在 {@code EventPriority.MONITOR} 下调用，此时方块尚未被移除，
	 * 可以正确判断它是否「有暴露面」。</p>
	 *
	 * @param player 玩家
	 * @param block  被破坏的方块
	 * @param type   该方块的类型（由事件读取，避免重复查询）
	 */
	public void onBlockBreak(Player player, Block block, Material type) {
		if (player == null || block == null || type == null || isExempt(player)) {
			return;
		}
		long now = System.currentTimeMillis();
		PlayerData data = players.computeIfAbsent(player.getUniqueId(), key -> new PlayerData());
		if (now - data.lastBreakTime > WINDOW_IDLE_RESET_MS) {
			// 长时间没挖矿，上一轮统计作废
			data.windowTotal = 0;
			data.windowRare = 0;
		}
		data.lastBreakTime = now;
		data.windowTotal++;

		if (isRareOre(type)) {
			data.windowRare++;

			// ---- 检测 1：挖到一块没有任何暴露面的稀有矿 ----
			// 原版玩家只能挖到看得见的方块，隔墙直挖是透视外挂的典型特征
			if (!isExposed(block)) {
				addViolation(player, data, WEIGHT_UNEXPOSED, "unexposed", now);
			}
		}

		// ---- 检测 2：一个采样窗口挖完后结算稀有矿命中率 ----
		if (data.windowTotal >= SAMPLE_SIZE) {
			int total = data.windowTotal;
			int rare = data.windowRare;
			data.windowTotal = 0;
			data.windowRare = 0;
			if (rare >= MIN_RARE_COUNT && (double) rare / total >= RARE_RATIO) {
				addViolation(player, data, WEIGHT_RATIO, "ratio", now);
			}
		}
	}

	/**
	 * 定期调用（建议每秒一次）：衰减违规等级、回收离线玩家数据。
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
	 * 是否为需要计入统计的稀有矿。
	 *
	 * <p>刻意排除金 / 铁 / 铜 / 下界金矿等常见矿：它们在各自地形里被大量挖掘属于正常行为。</p>
	 */
	private static boolean isRareOre(Material type) {
		return type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE
				|| type == Material.EMERALD_ORE || type == Material.DEEPSLATE_EMERALD_ORE
				|| type == Material.ANCIENT_DEBRIS;
	}

	/**
	 * 判断方块是否有至少一个「暴露面」（相邻方块是空气 / 水等可通行方块）。
	 *
	 * <p>相邻区块未加载、或位于世界高度边界时一律返回 {@code true}（视为暴露），
	 * 宁可漏判也不误判。</p>
	 */
	private static boolean isExposed(Block block) {
		World world = block.getWorld();
		int x = block.getX();
		int y = block.getY();
		int z = block.getZ();
		for (BlockFace face : FACES) {
			int ny = y + face.getModY();
			if (ny < world.getMinHeight() || ny >= world.getMaxHeight()) {
				return true; // 世界边界外是空气
			}
			int nx = x + face.getModX();
			int nz = z + face.getModZ();
			if (!world.isChunkLoaded(nx >> 4, nz >> 4)) {
				return true; // 相邻区块没加载，无法判断 → 放行
			}
			if (world.getBlockAt(nx, ny, nz).isPassable()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 判断玩家是否完全免检（创造 / 旁观 / OP 与该权限）。
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
	 * 处罚：记录日志并踢出玩家。非主线程时自动调度回主线程。
	 */
	private void punish(Player player, PlayerData data) {
		data.vl = 0; // 重置，避免重复处罚
		data.windowTotal = 0;
		data.windowRare = 0;
		MeteorfuckMod plugin = MeteorfuckMod.getInstance();
		if (plugin == null) {
			return;
		}
		log().log(Level.WARNING, PUNISH_LOG_FORMAT,
				new Object[] { player.getName(), CheckType.XRAY.getDisplayName() });
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

		/** 透视（X-Ray）。 */
		XRAY("透视");

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

		/** 最近一次违规时间。 */
		long lastViolationTime = System.currentTimeMillis();
		/** 最近一次破坏方块的时间。 */
		long lastBreakTime;

		/** 当前采样窗口内破坏的方块总数。 */
		int windowTotal;
		/** 当前采样窗口内破坏的稀有矿数量。 */
		int windowRare;

		/** 违规等级。 */
		int vl;
	}
}
