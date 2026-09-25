package com.lj.meteorfuck;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import com.lj.meteorfuck.vpark_1_0_0.No_Criticals;
import com.lj.meteorfuck.vpark_1_0_0.No_CrystalAura;
import com.lj.meteorfuck.vpark_1_0_0.No_ElytraFly;
import com.lj.meteorfuck.vpark_1_0_0.No_EntitySpeed;
import com.lj.meteorfuck.vpark_1_0_0.No_Fly;
import com.lj.meteorfuck.vpark_1_0_0.No_KillAura;
import com.lj.meteorfuck.vpark_1_0_0.No_NoFall;
import com.lj.meteorfuck.vpark_1_0_0.No_NoSlow;
import com.lj.meteorfuck.vpark_1_0_0.No_OffhandCrash;
import com.lj.meteorfuck.vpark_1_0_0.No_VelocityBoost;
import com.lj.meteorfuck.vpark_1_0_0.No_XRay;

/**
 * 反外挂调度器：统一注册事件监听，并把事件分发给各检测模块。
 *
 * <p>{@code No_KillAura}、{@code No_Criticals} 都是纯函数文件，自身不注册任何监听；
 * 所有的事件注册与定时任务都集中在本类，便于统一启停。</p>
 */
public final class MeteorfuckCheck implements Listener {

	/** 检测模块 tick 间隔（单位：tick，20 tick = 1 秒）。 */
	private static final long TICK_INTERVAL = 20L;

	/** 可开关的功能名：杀戮光环检测。 */
	public static final String TOOL_KILL_AURA = "No_KillAura";
	/** 可开关的功能名：刀暴检测。 */
	public static final String TOOL_CRITICALS = "No_Criticals";
	/** 可开关的功能名：反飞行检测。 */
	public static final String TOOL_FLY = "No_Fly";
	/** 可开关的功能名：反鞘翅飞行（平飞 / 悬停）检测。 */
	public static final String TOOL_ELYTRA_FLY = "No_ElytraFly";
	/** 可开关的功能名：反透视检测。 */
	public static final String TOOL_XRAY = "No_XRay";
	/** 可开关的功能名：反水晶光环检测。 */
	public static final String TOOL_CRYSTAL_AURA = "No_CrystalAura";
	/** 可开关的功能名：反摔落免疫检测。 */
	public static final String TOOL_NO_FALL = "No_NoFall";
	/** 可开关的功能名：反减速免疫检测。 */
	public static final String TOOL_NO_SLOW = "No_NoSlow";
	/** 可开关的功能名：反载具加速检测。 */
	public static final String TOOL_ENTITY_SPEED = "No_EntitySpeed";
	/** 可开关的功能名：反击退修改检测。 */
	public static final String TOOL_VELOCITY_BOOST = "No_VelocityBoost";
	/** 可开关的功能名：反副手崩溃检测。 */
	public static final String TOOL_OFFHAND_CRASH = "No_OffhandCrash";
	/** 全部可开关的功能名，供指令补全与 tool.yml 同步使用。 */
	public static final List<String> TOOL_NAMES = List.of(TOOL_KILL_AURA, TOOL_CRITICALS, TOOL_FLY,
			TOOL_ELYTRA_FLY, TOOL_XRAY, TOOL_CRYSTAL_AURA, TOOL_NO_FALL, TOOL_NO_SLOW,
			TOOL_ENTITY_SPEED, TOOL_VELOCITY_BOOST, TOOL_OFFHAND_CRASH);

	/**
	 * 功能开关缓存：由指令写入、由 tool.yml 恢复。
	 * 使用静态字段是为了与 {@link #start(Plugin)} 的调用顺序解耦。
	 */
	private static volatile boolean killAuraEnabled = true;
	private static volatile boolean criticalsEnabled = true;
	private static volatile boolean flyEnabled = true;
	private static volatile boolean elytraFlyEnabled = true;
	private static volatile boolean xrayEnabled = true;
	private static volatile boolean crystalAuraEnabled = true;
	private static volatile boolean noFallEnabled = true;
	private static volatile boolean noSlowEnabled = true;
	private static volatile boolean entitySpeedEnabled = true;
	private static volatile boolean velocityBoostEnabled = true;
	private static volatile boolean offhandCrashEnabled = true;

	private static MeteorfuckCheck instance;

	private BukkitTask tickTask;

	private MeteorfuckCheck() {
	}

	/**
	 * 注册事件监听并启动定时任务，在 {@code onEnable} 中调用一次即可（重复调用会被忽略）。
	 *
	 * @param plugin 插件主类
	 */
	public static void start(Plugin plugin) {
		if (instance != null) {
			return;
		}
		MeteorfuckCheck check = new MeteorfuckCheck();
		plugin.getServer().getPluginManager().registerEvents(check, plugin);
		check.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, check::tick,
				TICK_INTERVAL, TICK_INTERVAL);
		instance = check;

		// 兼容 /reload 场景：把已经在线的玩家补进检测状态
		for (Player player : Bukkit.getOnlinePlayers()) {
			No_KillAura.get().onJoin(player);
			No_Criticals.get().onJoin(player);
			No_Fly.get().onJoin(player);
			No_ElytraFly.get().onJoin(player);
			No_XRay.get().onJoin(player);
			No_CrystalAura.get().onJoin(player);
			No_NoFall.get().onJoin(player);
			No_NoSlow.get().onJoin(player);
			No_EntitySpeed.get().onJoin(player);
			No_VelocityBoost.get().onJoin(player);
			No_OffhandCrash.get().onJoin(player);
		}
	}

	/**
	 * 停止定时任务，在 {@code onDisable} 中调用。
	 */
	public static void stop() {
		if (instance == null) {
			return;
		}
		if (instance.tickTask != null) {
			instance.tickTask.cancel();
			instance.tickTask = null;
		}
		instance = null;
	}

	/* ==================== 功能开关 ==================== */

	/**
	 * 设置某个功能开关的状态，由 /meteorfuck tool 指令与 tool.yml 调用。
	 *
	 * @param tool    功能名，取值见 {@link #TOOL_NAMES}
	 * @param enabled 是否启用
	 * @return 功能名是否有效
	 */
	public static boolean applyToolState(String tool, boolean enabled) {
		if (TOOL_KILL_AURA.equalsIgnoreCase(tool)) {
			killAuraEnabled = enabled;
			return true;
		}
		if (TOOL_CRITICALS.equalsIgnoreCase(tool)) {
			criticalsEnabled = enabled;
			return true;
		}
		if (TOOL_FLY.equalsIgnoreCase(tool)) {
			flyEnabled = enabled;
			return true;
		}
		if (TOOL_ELYTRA_FLY.equalsIgnoreCase(tool)) {
			elytraFlyEnabled = enabled;
			return true;
		}
		if (TOOL_XRAY.equalsIgnoreCase(tool)) {
			xrayEnabled = enabled;
			return true;
		}
		if (TOOL_CRYSTAL_AURA.equalsIgnoreCase(tool)) {
			crystalAuraEnabled = enabled;
			return true;
		}
		if (TOOL_NO_FALL.equalsIgnoreCase(tool)) {
			noFallEnabled = enabled;
			return true;
		}
		if (TOOL_NO_SLOW.equalsIgnoreCase(tool)) {
			noSlowEnabled = enabled;
			return true;
		}
		if (TOOL_ENTITY_SPEED.equalsIgnoreCase(tool)) {
			entitySpeedEnabled = enabled;
			return true;
		}
		if (TOOL_VELOCITY_BOOST.equalsIgnoreCase(tool)) {
			velocityBoostEnabled = enabled;
			return true;
		}
		if (TOOL_OFFHAND_CRASH.equalsIgnoreCase(tool)) {
			offhandCrashEnabled = enabled;
			return true;
		}
		return false;
	}

	/**
	 * 在 {@link #TOOL_NAMES} 中按名字查找功能名（忽略大小写）。
	 *
	 * @return 匹配到的标准功能名，找不到时返回 {@code null}
	 */
	public static String matchTool(String input) {
		if (input == null) {
			return null;
		}
		for (String tool : TOOL_NAMES) {
			if (tool.equalsIgnoreCase(input)) {
				return tool;
			}
		}
		return null;
	}

	/** 杀戮光环检测是否启用。 */
	public static boolean isKillAuraEnabled() {
		return killAuraEnabled;
	}

	/** 刀暴检测是否启用。 */
	public static boolean isCriticalsEnabled() {
		return criticalsEnabled;
	}

	/** 反飞行检测是否启用。 */
	public static boolean isFlyEnabled() {
		return flyEnabled;
	}

	/** 反鞘翅飞行检测是否启用。 */
	public static boolean isElytraFlyEnabled() {
		return elytraFlyEnabled;
	}

	/** 反透视检测是否启用。 */
	public static boolean isXrayEnabled() {
		return xrayEnabled;
	}

	/** 反水晶光环检测是否启用。 */
	public static boolean isCrystalAuraEnabled() {
		return crystalAuraEnabled;
	}

	/** 反摔落免疫检测是否启用。 */
	public static boolean isNoFallEnabled() {
		return noFallEnabled;
	}

	/** 反减速免疫检测是否启用。 */
	public static boolean isNoSlowEnabled() {
		return noSlowEnabled;
	}

	/** 反载具加速检测是否启用。 */
	public static boolean isEntitySpeedEnabled() {
		return entitySpeedEnabled;
	}

	/** 反击退修改检测是否启用。 */
	public static boolean isVelocityBoostEnabled() {
		return velocityBoostEnabled;
	}

	/** 反副手崩溃检测是否启用。 */
	public static boolean isOffhandCrashEnabled() {
		return offhandCrashEnabled;
	}

	/* ==================== 定时任务 ==================== */

	private void tick() {
		if (killAuraEnabled) {
			No_KillAura.get().tick();
		}
		if (criticalsEnabled) {
			No_Criticals.get().tick();
		}
		if (flyEnabled) {
			No_Fly.get().tick();
		}
		if (elytraFlyEnabled) {
			No_ElytraFly.get().tick();
		}
		if (xrayEnabled) {
			No_XRay.get().tick();
		}
		if (crystalAuraEnabled) {
			No_CrystalAura.get().tick();
		}
		if (noFallEnabled) {
			No_NoFall.get().tick();
		}
		if (noSlowEnabled) {
			No_NoSlow.get().tick();
		}
		if (entitySpeedEnabled) {
			No_EntitySpeed.get().tick();
		}
		if (velocityBoostEnabled) {
			No_VelocityBoost.get().tick();
		}
		if (offhandCrashEnabled) {
			No_OffhandCrash.get().tick();
		}
	}

	/* ==================== 事件分发 ==================== */

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		if (killAuraEnabled) {
			No_KillAura.get().onJoin(player);
		}
		if (criticalsEnabled) {
			No_Criticals.get().onJoin(player);
		}
		if (flyEnabled) {
			No_Fly.get().onJoin(player);
		}
		if (elytraFlyEnabled) {
			No_ElytraFly.get().onJoin(player);
		}
		if (xrayEnabled) {
			No_XRay.get().onJoin(player);
		}
		if (crystalAuraEnabled) {
			No_CrystalAura.get().onJoin(player);
		}
		if (noFallEnabled) {
			No_NoFall.get().onJoin(player);
		}
		if (noSlowEnabled) {
			No_NoSlow.get().onJoin(player);
		}
		if (entitySpeedEnabled) {
			No_EntitySpeed.get().onJoin(player);
		}
		if (velocityBoostEnabled) {
			No_VelocityBoost.get().onJoin(player);
		}
		if (offhandCrashEnabled) {
			No_OffhandCrash.get().onJoin(player);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();
		No_KillAura.get().onQuit(player);
		No_Criticals.get().onQuit(player);
		No_Fly.get().onQuit(player);
		No_ElytraFly.get().onQuit(player);
		No_XRay.get().onQuit(player);
		No_CrystalAura.get().onQuit(player);
		No_NoFall.get().onQuit(player);
		No_NoSlow.get().onQuit(player);
		No_EntitySpeed.get().onQuit(player);
		No_VelocityBoost.get().onQuit(player);
		No_OffhandCrash.get().onQuit(player);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		if (killAuraEnabled) {
			No_KillAura.get().onRespawn(player);
		}
		if (criticalsEnabled) {
			No_Criticals.get().onRespawn(player);
		}
		if (flyEnabled) {
			No_Fly.get().onRespawn(player);
		}
		if (elytraFlyEnabled) {
			No_ElytraFly.get().onRespawn(player);
		}
		if (crystalAuraEnabled) {
			No_CrystalAura.get().onRespawn(player);
		}
		if (noFallEnabled) {
			No_NoFall.get().onRespawn(player);
		}
		if (noSlowEnabled) {
			No_NoSlow.get().onRespawn(player);
		}
		if (entitySpeedEnabled) {
			No_EntitySpeed.get().onRespawn(player);
		}
		if (velocityBoostEnabled) {
			No_VelocityBoost.get().onRespawn(player);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onTeleport(PlayerTeleportEvent event) {
		Player player = event.getPlayer();
		if (killAuraEnabled) {
			No_KillAura.get().onTeleport(player);
		}
		if (criticalsEnabled) {
			No_Criticals.get().onTeleport(player);
		}
		if (flyEnabled) {
			No_Fly.get().onTeleport(player);
		}
		if (elytraFlyEnabled) {
			No_ElytraFly.get().onTeleport(player);
		}
		if (crystalAuraEnabled) {
			No_CrystalAura.get().onTeleport(player);
		}
		if (noFallEnabled) {
			No_NoFall.get().onTeleport(player);
		}
		if (noSlowEnabled) {
			No_NoSlow.get().onTeleport(player);
		}
		if (entitySpeedEnabled) {
			No_EntitySpeed.get().onTeleport(player);
		}
		if (velocityBoostEnabled) {
			No_VelocityBoost.get().onTeleport(player);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onChangedWorld(PlayerChangedWorldEvent event) {
		Player player = event.getPlayer();
		if (killAuraEnabled) {
			No_KillAura.get().onTeleport(player);
		}
		if (criticalsEnabled) {
			No_Criticals.get().onTeleport(player);
		}
		if (flyEnabled) {
			No_Fly.get().onTeleport(player);
		}
		if (elytraFlyEnabled) {
			No_ElytraFly.get().onTeleport(player);
		}
		if (xrayEnabled) {
			No_XRay.get().onChangedWorld(player);
		}
		if (crystalAuraEnabled) {
			No_CrystalAura.get().onChangedWorld(player);
		}
		if (noFallEnabled) {
			No_NoFall.get().onTeleport(player);
		}
		if (noSlowEnabled) {
			No_NoSlow.get().onTeleport(player);
		}
		if (entitySpeedEnabled) {
			No_EntitySpeed.get().onTeleport(player);
		}
		if (velocityBoostEnabled) {
			No_VelocityBoost.get().onTeleport(player);
		}
	}

	/**
	 * 移动 / 转头分发。
	 *
	 * <p>注意：{@link PlayerTeleportEvent} 是 {@link PlayerMoveEvent} 的子类，
	 * 因此传送时本方法也会被调用，此时 {@code from} 与 {@code to} 跨度极大；
	 * 两个检测模块都内部处理了这种跳变，无需在此特判。</p>
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onMove(PlayerMoveEvent event) {
		Location from = event.getFrom();
		Location to = event.getTo();
		Player player = event.getPlayer();

		// 离地状态需要每次移动都采样
		if (criticalsEnabled) {
			No_Criticals.get().onMove(player, from, to, player.isOnGround());
		}

		// 视角采样只在真正转动时进行，纯位移事件直接跳过，省掉一次查表与浮点运算
		if (killAuraEnabled && (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch())) {
			No_KillAura.get().onMove(player, from, to);
		}

		// 反飞行依赖每次移动的垂直位移与离地标记
		if (flyEnabled) {
			No_Fly.get().onMove(player, from, to, player.isOnGround());
		}

		// 鞘翅飞行同样依赖每次移动的垂直 / 水平位移
		if (elytraFlyEnabled) {
			No_ElytraFly.get().onMove(player, from, to, player.isOnGround());
		}

		// 摔落免疫依赖客户端上报的离地标记
		if (noFallEnabled) {
			No_NoFall.get().onMove(player, from, to, player.isOnGround());
		}

		// 减速免疫依赖水平位移与「是否正在使用物品」
		if (noSlowEnabled) {
			No_NoSlow.get().onMove(player, from, to);
		}

		// 击退测量只在窗口打开时才真正计算，模块内部会先查表再决定
		if (velocityBoostEnabled) {
			No_VelocityBoost.get().onMove(player, from, to);
		}
	}

	/**
	 * 破坏方块分发（反透视统计用）。
	 *
	 * <p>用 MONITOR 优先级是为了在方块被真正移除前拿到它，
	 * 这样「是否有暴露面」的判断才准确。</p>
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event) {
		if (!xrayEnabled) {
			return;
		}
		Block block = event.getBlock();
		No_XRay.get().onBlockBreak(event.getPlayer(), block, block.getType());
	}

	/**
	 * 放置实体分发（反水晶光环统计用）。
	 *
	 * <p>只关心玩家放下的末地水晶：记录「谁在什么时候放的」，
	 * 供之后的引爆事件计算「放置 → 引爆」间隔。</p>
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEntityPlace(EntityPlaceEvent event) {
		if (!crystalAuraEnabled) {
			return;
		}
		Player player = event.getPlayer();
		if (player == null || event.getEntity().getType() != EntityType.END_CRYSTAL) {
			return;
		}
		No_CrystalAura.get().onPlaceCrystal(player, event.getEntity());
	}

	/**
	 * 载具移动分发（反载具加速检测用）。
	 *
	 * <p>只处理「乘客里有玩家」的载具：空船、野生动物等不参与统计。</p>
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onVehicleMove(VehicleMoveEvent event) {
		if (!entitySpeedEnabled) {
			return;
		}
		for (Entity passenger : event.getVehicle().getPassengers()) {
			if (passenger instanceof Player player) {
				No_EntitySpeed.get().onVehicleMove(player, event.getVehicle(), event.getFrom(),
						event.getTo());
			}
		}
	}

	/**
	 * 受伤分发（反击退修改检测用）。
	 *
	 * <p>任何伤害都会改变位移、使正在进行的击退测量作废，因此这里直接监听最上层的
	 * {@link EntityDamageEvent}；只有「玩家冲刺近战」才会开启一次新的测量。</p>
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEntityDamage(EntityDamageEvent event) {
		if (!velocityBoostEnabled || !(event.getEntity() instanceof Player victim)) {
			return;
		}
		Entity attacker = null;
		boolean melee = false;
		if (event instanceof EntityDamageByEntityEvent byEntity
				&& byEntity.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
				&& byEntity.getDamager() instanceof Player damager) {
			attacker = damager;
			melee = true;
		}
		No_VelocityBoost.get().onDamage(victim, attacker, melee);
	}

	/**
	 * 副手切换分发（反副手崩溃检测用）。
	 *
	 * <p>用 LOWEST 优先级是为了在任何处理之前就能取消：副手崩溃靠的是刷爆装备广播包,
	 * 必须在事件阶段就掐断。</p>
	 */
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
		if (!offhandCrashEnabled) {
			return;
		}
		if (No_OffhandCrash.get().onSwapHandItems(event.getPlayer())) {
			event.setCancelled(true);
		}
	}

	/**
	 * 背包点击分发（反副手崩溃检测用）。
	 *
	 * <p>槽位越界、UNKNOWN 点击、非法热键按钮这些「原版客户端不可能发出的包」
	 * 会被判定并直接取消。</p>
	 */
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onInventoryClick(InventoryClickEvent event) {
		if (!offhandCrashEnabled || !(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		InventoryView view = event.getView();
		int totalSlots = view.getTopInventory().getSize() + view.getBottomInventory().getSize();
		if (No_OffhandCrash.get().onInventoryClick(player, event.getSlot(), totalSlots,
				event.getClick(), event.getAction(), event.getHotbarButton())) {
			event.setCancelled(true);
		}
	}

	/**
	 * 背包拖动分发（反副手崩溃检测用）。
	 */
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onInventoryDrag(InventoryDragEvent event) {
		if (!offhandCrashEnabled || !(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		InventoryView view = event.getView();
		int totalSlots = view.getTopInventory().getSize() + view.getBottomInventory().getSize();
		if (No_OffhandCrash.get().onInventoryDrag(player, event.getRawSlots().size(), totalSlots)) {
			event.setCancelled(true);
		}
	}
}
