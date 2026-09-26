package com.lj.meteorfuck.vpark_1_0_0;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;

import com.lj.meteorfuck.MeteorfuckMod;

/**
 * 反副手崩溃（OffhandCrash）检测器 —— 纯服务端实现。
 *
 * 本类只提供函数，不注册任何事件监听。由调度器在
 * {@code PlayerSwapHandItemsEvent}、{@code InventoryClickEvent}、{@code InventoryDragEvent}
 * 中转发，并每秒调用 {@link #tick()} 即可。
 *
 * 注意：这是唯一会要求调度器取消事件的模块（各入口返回 {@code true}
 * 表示「请取消这次事件」）。原因是它防的是服务器崩溃，
 * 必须在事件阶段就掐断，而不是等违规等级攒够。
 *
 * 原理
 *
 * 副手槽位属于「实体装备」，每次变化服务端都要重新走一遍交换 + 广播流程。
 * 彗星端（Meteor Client）「杂项」分类里的「副手崩溃」模块就是滥用这一点：
 * 它拦截并狂发 {@code PlayerActionC2SPacket}（动作 {@code SWAP_ITEM_WITH_OFFHAND}），
 * 每个游戏刻循环发送 {@code speed} 次 —— 默认 2000 次、最高 10000 次，
 * 也就是每秒数万到数十万个数据包，靠榨干服务端（以及收到海量广播的周边玩家）的处理能力
 * 来让别人掉线甚至崩溃。
 *
 * 所以本模块按「数据包洪水」而不是「手感异常」来设计：只看副手切换与背包点击的
 * 真实时间频率，越过洪水门槛就立刻取消事件并踢人 —— 取消事件会直接掐断那次交换
 * 与随之而来的广播，攻击效果当场失效，这是本模块最大的价值。
 *
 * 检测项
 *
 * 1. 副手切换洪水 —— 100 毫秒内切换 ≥ 64 次（洪水级，直接踢），
 *    或 1 秒内切换 ≥ 30 次并连续两个窗口都超（持续洪水）；
 *    容器里的「副手交换」点击（{@code SWAP_OFFHAND} / {@code HOTBAR_SWAP}）同样计入；
 * 2. 背包点击洪水 —— 100 毫秒内点击 ≥ 64 次（直接踢），
 *    或 1 秒内点击 ≥ 40 次并连续两个窗口都超；
 * 3. 原版不可能发出的点击包 —— 槽位越界、{@code ClickType/InventoryAction.UNKNOWN}
 *    （客户端发来的非法模式会被映射为 UNKNOWN）、热键栏按钮不在 0-8 之间；
 * 4. 拖动槽位数越界 —— 一次拖动覆盖的槽位数量超过整个界面本身。
 *
 * 如何区分正常行为
 *
 * - 洪水阈值取的是「每秒数百次」级别，远高于人类极限（按 F 最快也就 10 次 / 秒左右），
 *   正常玩家无论怎么连点、整理背包都碰不到；
 * - 速率类判定还要求连续两个窗口都超：网络抖动或服务端卡顿把玩家几次正常操作
 *   合并送达时只是一次性突发，下一个窗口就会掉回去，因此不会被误判；
 * - 两手是否拿着物品不影响统计：这个漏洞空手也照样刷，服务端每个包都要走一遍
 *   交换与广播流程，所以不能像其它检测那样要求「必须手持物品」；
 * - 槽位越界判定使用「整个界面槽位数」，因此所有合法槽位（含窗口外 -1、光标 -999）都放行；
 * - 只有 {@code meteorfuck.bypass}（OP 默认拥有）与死亡状态免检 ——
 *   本模块刻意不按游戏模式放行：创造模式玩家同样能把副手刷爆，
 *   而崩溃一次是全服一起承担，所以这里不做创造 / 旁观豁免；
 * - 没有宽限期：崩溃防护不能等玩家进服几秒后才生效；
 * - 洪水级突发与原版不可能出现的包直接踢出，普通速率异常按加权累计（VL）处理。
 */
public final class No_OffhandCrash {

	/* ==================== 可调参数 ==================== */

	/** 绕过检测的权限节点（OP 默认拥有该权限）。 */
	private static final String BYPASS_PERMISSION = "meteorfuck.bypass";

	/** 输出调试日志。 */
	private static final boolean DEBUG = false;

	/* --- 槽位取值 --- */
	/** 槽位取值：窗口之外（丢弃物品）。 */
	private static final int SLOT_OUTSIDE = -1;
	/** 槽位取值：光标所在位置（与光标交互）。 */
	private static final int SLOT_CURSOR = -999;
	/** 原版热键栏按钮的合法上限（0-8）。 */
	private static final int MAX_HOTBAR_BUTTON = 8;

	/* --- 副手切换频率 --- */
	/** 突发统计窗口（毫秒）：彗星端的洪水会在几毫秒内填满它。 */
	private static final long BURST_WINDOW_MS = 100L;
	/** 突发窗口内允许的最大次数（洪水级，超过即直接踢出）。 */
	private static final int BURST_MAX = 64;
	/** 速率统计窗口（毫秒）。 */
	private static final long RATE_WINDOW_MS = 1000L;
	/** 速率窗口内允许的最大副手切换次数。 */
	private static final int SWAP_RATE_MAX = 30;
	/** 速率窗口内允许的最大背包点击次数。 */
	private static final int CLICK_RATE_MAX = 40;
	/** 需要连续超速多少个窗口才计一次违规（一次突发不算）。 */
	private static final int RATE_BUFFER_THRESHOLD = 2;

	/* --- 违规等级 --- */
	/** 频率类异常的权重。 */
	private static final int WEIGHT_RATE = 8;
	/** 原版不可能出现的包的权重（等于 VL_KICK，直接踢出）。 */
	private static final int WEIGHT_HARD = 12;
	/** 达到该违规等级即踢出。 */
	private static final int VL_KICK = 12;
	/** 违规等级上限。 */
	private static final int VL_MAX = 20;
	/** 无违规时每隔该时间衰减 1 点违规等级（毫秒）。 */
	private static final long VL_DECAY_INTERVAL_MS = 5000L;
	/** 数据空闲多久后被清理（毫秒）。 */
	private static final long DATA_IDLE_TIMEOUT_MS = 300_000L;

	private static final String LOG_PREFIX = "[No_OffhandCrash] ";
	private static final String DEBUG_LOG_FORMAT = LOG_PREFIX + "{0} vl={1} [{2}]";
	private static final String PUNISH_LOG_FORMAT = LOG_PREFIX + "检测到 {0} 疑似利用副手崩溃漏洞（{1}），已踢出";

	private static final No_OffhandCrash INSTANCE = new No_OffhandCrash();

	/** 每个玩家的检测状态。 */
	private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

	private No_OffhandCrash() {
	}

	/**
	 * 获取检测器单例。
	 */
	public static No_OffhandCrash get() {
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
	 * 玩家切换主手 / 副手物品时调用（对应 {@code PlayerSwapHandItemsEvent}）。
	 *
	 * 彗星端的「副手崩溃」正是靠狂刷这个数据包实现的，因此这里是本模块的主战场：
	 * 只要频率越界就返回 {@code true} 让调度器取消事件 —— 取消会直接掐断这次交换
	 * 与随之而来的广播。
	 *
	 * @param player 玩家
	 * @return {@code true} 表示本次切换应被取消
	 */
	public boolean onSwapHandItems(Player player) {
		if (player == null || isExempt(player)) {
			return false;
		}
		long now = System.currentTimeMillis();
		PlayerData data = players.computeIfAbsent(player.getUniqueId(), key -> new PlayerData());
		data.lastSeenTime = now;
		countSwap(data, now);
		return checkSwapFlood(player, data, now);
	}

	/**
	 * 玩家点击背包时调用（对应 {@code InventoryClickEvent}）。
	 *
	 * @param player       玩家
	 * @param rawSlot      被点击的槽位（{@code event.getSlot()}）
	 * @param totalSlots   整个界面的槽位总数（上半 + 下半）
	 * @param clickType    点击类型（{@code event.getClick()}）
	 * @param action       点击动作（{@code event.getAction()}）
	 * @param hotbarButton 热键栏按钮（{@code event.getHotbarButton()}）
	 * @return {@code true} 表示本次点击应被取消
	 */
	public boolean onInventoryClick(Player player, int rawSlot, int totalSlots, ClickType clickType,
			InventoryAction action, int hotbarButton) {
		if (player == null || clickType == null || action == null || isExempt(player)) {
			return false;
		}
		long now = System.currentTimeMillis();
		PlayerData data = players.computeIfAbsent(player.getUniqueId(), key -> new PlayerData());
		data.lastSeenTime = now;

		// ---- 检测 3：原版客户端不可能发出的点击包 ----
		String reason = null;
		if (clickType == ClickType.UNKNOWN || action == InventoryAction.UNKNOWN) {
			reason = "unknown-packet";
		} else if (rawSlot != SLOT_OUTSIDE && rawSlot != SLOT_CURSOR
				&& (rawSlot < 0 || rawSlot >= totalSlots)) {
			reason = "slot-range";
		} else if (clickType == ClickType.NUMBER_KEY
				&& (hotbarButton < 0 || hotbarButton > MAX_HOTBAR_BUTTON)) {
			reason = "hotbar-button";
		}
		if (reason != null) {
			addViolation(player, data, WEIGHT_HARD, reason, now);
			return true;
		}

		// ---- 容器里的「副手交换」点击同样会走一遍交换 + 广播，计入副手切换统计 ----
		if (clickType == ClickType.SWAP_OFFHAND || action == InventoryAction.HOTBAR_SWAP) {
			countSwap(data, now);
			if (checkSwapFlood(player, data, now)) {
				return true;
			}
		}

		// ---- 检测 2：背包点击洪水 ----
		if (now - data.clickBurstStart > BURST_WINDOW_MS) {
			data.clickBurstStart = now;
			data.clickBurstCount = 0;
		}
		if (++data.clickBurstCount >= BURST_MAX) {
			data.resetClickCounters();
			addViolation(player, data, WEIGHT_HARD, "click-flood", now);
			return true;
		}

		if (now - data.clickRateStart > RATE_WINDOW_MS) {
			// 上一个窗口结束时没超速：衰减连续计数（一次性突发不会累积成违规）
			if (data.clickRateBuffer > 0) {
				data.clickRateBuffer--;
			}
			data.clickRateStart = now;
			data.clickRateCount = 0;
		}
		if (++data.clickRateCount >= CLICK_RATE_MAX) {
			data.clickRateCount = 0;
			data.clickRateStart = now;
			if (++data.clickRateBuffer >= RATE_BUFFER_THRESHOLD) {
				data.resetClickCounters();
				addViolation(player, data, WEIGHT_RATE, "click-rate", now);
				return true;
			}
		}
		return false;
	}

	/**
	 * 玩家拖动物品时调用（对应 {@code InventoryDragEvent}）。
	 *
	 * @param player        玩家
	 * @param rawSlotCount  本次拖动覆盖的槽位数量
	 * @param totalSlots    整个界面的槽位总数
	 * @return {@code true} 表示本次拖动应被取消
	 */
	public boolean onInventoryDrag(Player player, int rawSlotCount, int totalSlots) {
		if (player == null || rawSlotCount <= 0 || isExempt(player)) {
			return false;
		}
		long now = System.currentTimeMillis();
		PlayerData data = players.computeIfAbsent(player.getUniqueId(), key -> new PlayerData());
		data.lastSeenTime = now;
		if (totalSlots <= 0 || rawSlotCount <= totalSlots) {
			return false;
		}
		// ---- 检测 4：一次拖动覆盖的槽位不可能超过界面本身 ----
		addViolation(player, data, WEIGHT_HARD, "drag-overflow", now);
		return true;
	}

	/**
	 * 定期调用（建议每秒一次）：刷新 TPS 缓存、衰减违规等级、清理过期数据。
	 */
	public void tick() {
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
	 * 累计一次副手切换（突发窗口 + 速率窗口，全部按真实时间计时）。
	 */
	private static void countSwap(PlayerData data, long now) {
		if (now - data.swapBurstStart > BURST_WINDOW_MS) {
			data.swapBurstStart = now;
			data.swapBurstCount = 0;
		}
		data.swapBurstCount++;

		if (now - data.swapRateStart > RATE_WINDOW_MS) {
			// 上一个窗口结束时没超速：衰减连续计数（一次性突发不会累积成违规）
			if (data.swapRateBuffer > 0) {
				data.swapRateBuffer--;
			}
			data.swapRateStart = now;
			data.swapRateCount = 0;
		}
		data.swapRateCount++;
	}

	/**
	 * 副手切换洪水判定。
	 *
	 * @return {@code true} 表示本次事件应被取消
	 */
	private boolean checkSwapFlood(Player player, PlayerData data, long now) {
		if (data.swapBurstCount >= BURST_MAX) {
			data.resetSwapCounters();
			addViolation(player, data, WEIGHT_HARD, "swap-flood", now);
			return true;
		}
		if (data.swapRateCount >= SWAP_RATE_MAX) {
			data.swapRateCount = 0;
			data.swapRateStart = now;
			if (++data.swapRateBuffer >= RATE_BUFFER_THRESHOLD) {
				data.resetSwapCounters();
				addViolation(player, data, WEIGHT_RATE, "swap-rate", now);
				return true;
			}
		}
		return false;
	}

	/**
	 * 判断玩家是否完全免检。
	 *
	 * 这里刻意不按游戏模式放行：创造模式同样能把副手刷爆，
	 * 而崩溃是全服一起承担，所以只放行权限豁免与死亡状态。
	 */
	private static boolean isExempt(Player player) {
		if (player.isDead() || !player.isOnline()) {
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
		data.resetSwapCounters();
		data.resetClickCounters();
		data.vl = 0; // 重置，避免重复处罚
		MeteorfuckMod plugin = MeteorfuckMod.getInstance();
		if (plugin == null) {
			return;
		}
		log().log(Level.WARNING, PUNISH_LOG_FORMAT,
				new Object[] { player.getName(), CheckType.OFFHAND_CRASH.getDisplayName() });
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

		/** 副手崩溃（OffhandCrash）。 */
		OFFHAND_CRASH("副手崩溃");

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
		/** 最近一次收到动作的时间。 */
		long lastSeenTime = System.currentTimeMillis();

		/** 副手切换：突发窗口起点。 */
		long swapBurstStart;
		/** 副手切换：突发窗口内次数。 */
		int swapBurstCount;
		/** 副手切换：速率窗口起点。 */
		long swapRateStart;
		/** 副手切换：速率窗口内次数。 */
		int swapRateCount;
		/** 副手切换：连续超速的窗口数。 */
		int swapRateBuffer;

		/** 点击：突发窗口起点。 */
		long clickBurstStart;
		/** 点击：突发窗口内次数。 */
		int clickBurstCount;
		/** 点击：速率窗口起点。 */
		long clickRateStart;
		/** 点击：速率窗口内次数。 */
		int clickRateCount;
		/** 点击：连续超速的窗口数。 */
		int clickRateBuffer;

		/** 违规等级。 */
		int vl;

		void resetSwapCounters() {
			swapBurstStart = 0L;
			swapBurstCount = 0;
			swapRateStart = 0L;
			swapRateCount = 0;
			swapRateBuffer = 0;
		}

		void resetClickCounters() {
			clickBurstStart = 0L;
			clickBurstCount = 0;
			clickRateStart = 0L;
			clickRateCount = 0;
			clickRateBuffer = 0;
		}
	}
}
