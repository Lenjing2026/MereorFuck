package com.lj.meteorfuck;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * MeteorFuck 反外挂插件主类。
 */
public class MeteorfuckMod extends JavaPlugin implements CommandExecutor, TabCompleter {

	/** main.yml 文件名（位于插件数据文件夹内）。 */
	public static final String MAIN_CONFIG_FILE = "main.yml";

	/** 功能开关配置文件名（位于插件数据文件夹内）。 */
	public static final String TOOL_CONFIG_FILE = "tool.yml";

	/** 踢出玩家的默认消息配置路径。 */
	public static final String DEFAULT_KICK_MESSAGE_PATH = "kick-message";

	/** 配置缺失时使用的内置踢出消息。 */
	public static final String DEFAULT_KICK_MESSAGE_TEXT = "&c你已被服务器踢出";

	/** 指令名，需与 paper-plugin.yml 中的声明一致。 */
	public static final String COMMAND_NAME = "meteorfuck";

	/** 使用指令所需的权限（OP 默认拥有）。 */
	public static final String ADMIN_PERMISSION = "meteorfuck.admin";

	/** 指令用法提示。 */
	private static final String USAGE = "&e用法：&f/meteorfuck tool <功能名> <true|false>";

	private static MeteorfuckMod instance;

	private FileConfiguration mainConfig;

	private FileConfiguration toolConfig;

	/**
	 * 获取插件主类实例。
	 */
	public static MeteorfuckMod getInstance() {
		return instance;
	}

	@Override
	public void onEnable() {
		instance = this;
		reloadMainConfig();
		reloadToolConfig();
		// 指令由 MeteorfuckModBootstrap 在 Commands 生命周期里注册，这里不用再注册
		// 注册事件监听并启动检测模块的定时任务
		MeteorfuckCheck.start(this);
	}

	@Override
	public void onDisable() {
		MeteorfuckCheck.stop();
		instance = null;
		mainConfig = null;
		toolConfig = null;
	}

	/* ============================ 配置 ============================ */

	/**
	 * 获取 main.yml 的配置对象，未加载时会自动加载。
	 */
	public FileConfiguration getMainConfig() {
		if (mainConfig == null) {
			reloadMainConfig();
		}
		return mainConfig;
	}

	/**
	 * 重新加载 main.yml。
	 * 若数据文件夹内不存在该文件，会先从插件 jar 内释放一份默认配置。
	 */
	public void reloadMainConfig() {
		File file = new File(getDataFolder(), MAIN_CONFIG_FILE);
		if (!file.exists()) {
			saveResource(MAIN_CONFIG_FILE, false);
		}
		mainConfig = YamlConfiguration.loadConfiguration(file);
	}

	/* ============================ 踢出玩家 ============================ */

	/**
	 * 踢出玩家，踢出信息从 main.yml 的 {@code kick-message} 节点加载。
	 *
	 * @param player 要踢出的玩家
	 */
	public void KickMassage(Player player) {
		KickMassage(player, DEFAULT_KICK_MESSAGE_PATH);
	}

	/**
	 * 踢出玩家，踢出信息从 main.yml 的指定节点加载。
	 * 节点不存在时回退到 {@code kick-message}，仍不存在则使用内置默认文本。
	 *
	 * @param player 要踢出的玩家
	 * @param path   main.yml 中的配置路径，例如 {@code kick-message}
	 */
	public void KickMassage(Player player, String path) {
		if (player == null) {
			return;
		}
		FileConfiguration config = getMainConfig();
		String message = Objects.requireNonNullElse(config.getString(path),
				Objects.requireNonNullElse(config.getString(DEFAULT_KICK_MESSAGE_PATH), DEFAULT_KICK_MESSAGE_TEXT));
		// main.yml 中的 & 颜色代码需要转换为 Adventure Component
		player.kick(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
	}

	/* ============================ 功能开关（tool.yml） ============================ */

	/**
	 * 获取 tool.yml 的配置对象，未加载时会自动加载。
	 */
	public FileConfiguration getToolConfig() {
		if (toolConfig == null) {
			reloadToolConfig();
		}
		return toolConfig;
	}

	/**
	 * 重新加载 tool.yml，并把开关状态同步到调度器。
	 * 若数据文件夹内不存在该文件，会先从插件 jar 内释放一份默认配置。
	 */
	public void reloadToolConfig() {
		File file = new File(getDataFolder(), TOOL_CONFIG_FILE);
		if (!file.exists()) {
			saveResource(TOOL_CONFIG_FILE, false);
		}
		toolConfig = YamlConfiguration.loadConfiguration(file);
		syncToolStates();
	}

	/**
	 * 把 tool.yml 中的开关同步到调度器；缺失的节点会补上默认值并写回文件。
	 */
	private void syncToolStates() {
		boolean missing = false;
		for (String tool : MeteorfuckCheck.TOOL_NAMES) {
			MeteorfuckCheck.applyToolState(tool, toolConfig.getBoolean(tool, true));
			if (!toolConfig.isSet(tool)) {
				toolConfig.set(tool, true);
				missing = true;
			}
		}
		if (missing) {
			saveToolConfig();
		}
	}

	/**
	 * 查询某个功能是否已启用（读取 tool.yml 的当前值）。
	 *
	 * @param tool 功能名，取值见 {@link MeteorfuckCheck#TOOL_NAMES}
	 */
	public boolean isToolEnabled(String tool) {
		return getToolConfig().getBoolean(tool, true);
	}

	/**
	 * 修改并持久化某个功能的开关状态。
	 *
	 * @param tool    功能名（忽略大小写）
	 * @param enabled 是否启用
	 * @return 功能名是否有效
	 */
	public boolean setToolEnabled(String tool, boolean enabled) {
		String matched = MeteorfuckCheck.matchTool(tool);
		if (matched == null) {
			return false;
		}
		getToolConfig().set(matched, enabled);
		saveToolConfig();
		MeteorfuckCheck.applyToolState(matched, enabled);
		return true;
	}

	/**
	 * 保存 tool.yml。
	 */
	public void saveToolConfig() {
		try {
			getToolConfig().save(new File(getDataFolder(), TOOL_CONFIG_FILE));
		} catch (IOException exception) {
			// 日志格式串用常量，避免热路径之外的字符串拼接告警
			getLogger().log(Level.WARNING, "无法保存 tool.yml", exception);
		}
	}

	/* ============================ 指令 ============================ */

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission(ADMIN_PERMISSION)) {
			send(sender, "&c你没有权限使用该指令");
			return true;
		}
		// 目前只有 tool 一个子指令
		if (args.length < 3 || !"tool".equalsIgnoreCase(args[0])) {
			sendUsage(sender);
			return true;
		}
		Boolean enabled = parseBoolean(args[2]);
		if (enabled == null) {
			send(sender, "&c第三个参数只能是 &ftrue &c或 &ffalse");
			return true;
		}
		applyToolToggle(sender, args[1], enabled);
		return true;
	}

	/**
	 * 回显指令用法（Brigadier 注册的指令也复用这里）。
	 *
	 * @param sender 指令发送者
	 */
	public void sendUsage(CommandSender sender) {
		send(sender, USAGE);
	}

	/**
	 * 设置某个功能的开关并回显结果，指令与 Brigadier 注册共用。
	 *
	 * @param sender  指令发送者
	 * @param tool    功能名（忽略大小写）
	 * @param enabled 是否启用
	 */
	public void applyToolToggle(CommandSender sender, String tool, boolean enabled) {
		String matched = MeteorfuckCheck.matchTool(tool);
		if (matched == null) {
			send(sender, "&c未知功能：&f" + tool + "&c，可用功能：&f"
					+ String.join("&7, &f", MeteorfuckCheck.TOOL_NAMES));
			return;
		}
		setToolEnabled(matched, enabled);
		send(sender, "&a已将 &f" + matched + " &a设置为 &f" + enabled);
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!sender.hasPermission(ADMIN_PERMISSION)) {
			return Collections.emptyList();
		}
		if (args.length == 1) {
			return filter(List.of("tool"), args[0]);
		}
		if (!"tool".equalsIgnoreCase(args[0])) {
			return Collections.emptyList();
		}
		if (args.length == 2) {
			return filter(MeteorfuckCheck.TOOL_NAMES, args[1]);
		}
		if (args.length == 3) {
			return filter(List.of("true", "false"), args[2]);
		}
		return Collections.emptyList();
	}

	/**
	 * 按前缀过滤补全候选（忽略大小写）。
	 */
	private static List<String> filter(List<String> candidates, String prefix) {
		List<String> result = new ArrayList<>(candidates.size());
		for (String candidate : candidates) {
			if (candidate.regionMatches(true, 0, prefix, 0, prefix.length())) {
				result.add(candidate);
			}
		}
		return result;
	}

	/**
	 * 严格解析 true / false，其它输入返回 {@code null}。
	 */
	private static Boolean parseBoolean(String input) {
		if ("true".equalsIgnoreCase(input)) {
			return Boolean.TRUE;
		}
		if ("false".equalsIgnoreCase(input)) {
			return Boolean.FALSE;
		}
		return null;
	}

	/**
	 * 发送带 & 颜色代码的消息。
	 */
	private static void send(CommandSender sender, String message) {
		sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
	}
}
