package com.lj.meteorfuck;

import java.util.List;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

/**
 * {@code /meteorfuck} 指令的 Brigadier 注册。
 *
 * 为什么不在 paper-plugin.yml 里写 commands？
 *
 * Paper 插件（{@code paper-plugin.yml}）不支持 plugin.yml 的 {@code commands} 段，
 * 指令必须在 bootstrapper 里通过 {@code LifecycleEvents.COMMANDS} 注册，
 * 否则游戏里连指令名都找不到（客户端补全与服务端都是「未知指令」）。
 *
 * 指令结构：
 *
 * /meteorfuck                                          → 回显用法
 * /meteorfuck tool                                     → 回显用法
 * /meteorfuck tool <功能名> <true|false>   → 开关某个检测模块
 *
 * 功能名与 {@code true/false} 都由 Brigadier 自动补全并校验，
 * 无 {@code meteorfuck.admin} 权限的玩家既用不了也看不到这条指令。
 */
@SuppressWarnings("UnstableApiUsage")
public final class MeteorfuckCommand {

	/** 指令描述，显示在 {@code /help} 中。 */
	private static final String DESCRIPTION = "MeteorFuck 反作弊管理";

	/** 子指令名。 */
	private static final String SUB_TOOL = "tool";

	/** 参数名：功能名。 */
	private static final String ARG_TOOL = "tool";

	/** 参数名：开关状态。 */
	private static final String ARG_STATE = "state";

	/** 指令别名。 */
	private static final List<String> ALIASES = List.of("mf");

	private MeteorfuckCommand() {
	}

	/**
	 * 把 {@code /meteorfuck} 注册进服务端指令系统。
	 *
	 * @param commands 注册器，来自 {@code RegistrarEvent#registrar()}
	 */
	public static void register(Commands commands) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(MeteorfuckMod.COMMAND_NAME)
				// 无权限时连补全都不可见
				.requires(source -> source.getSender().hasPermission(MeteorfuckMod.ADMIN_PERMISSION))
				.executes(context -> {
					usage(context.getSource());
					return 1;
				})
				.then(Commands.literal(SUB_TOOL)
						.executes(context -> {
							usage(context.getSource());
							return 1;
						})
						.then(Commands.argument(ARG_TOOL, StringArgumentType.word())
								// 功能名补全：与实际生效的开关列表同源
								.suggests((context, builder) -> {
									for (String tool : MeteorfuckCheck.TOOL_NAMES) {
										builder.suggest(tool);
									}
									return builder.buildFuture();
								})
								.then(Commands.argument(ARG_STATE, BoolArgumentType.bool())
										// true / false 由 Brigadier 自动补全
										.executes(context -> {
											MeteorfuckMod plugin = MeteorfuckMod.getInstance();
											if (plugin != null) {
												plugin.applyToolToggle(context.getSource().getSender(),
														StringArgumentType.getString(context, ARG_TOOL),
														BoolArgumentType.getBool(context, ARG_STATE));
											}
											return 1;
										}))));

		commands.register(root.build(), DESCRIPTION, ALIASES);
	}

	/**
	 * 回显指令用法。插件尚未启用（拿不到实例）时直接跳过。
	 */
	private static void usage(CommandSourceStack source) {
		MeteorfuckMod plugin = MeteorfuckMod.getInstance();
		if (plugin != null) {
			plugin.sendUsage(source.getSender());
		}
	}
}
