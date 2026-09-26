package com.lj.meteorfuck;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

/**
 * Paper 插件引导类：在插件加载前完成指令注册。
 *
 * {@code paper-plugin.yml} 不支持 plugin.yml 里的 {@code commands} 段，
 * 指令必须在这里通过 {@link LifecycleEvents#COMMANDS} 注册，
 * 否则游戏里根本找不到 {@code /meteorfuck}。
 */
@SuppressWarnings("UnstableApiUsage")
public class MeteorfuckModBootstrap implements PluginBootstrap {

	@Override
	public void bootstrap(BootstrapContext context) {
		LifecycleEventManager<BootstrapContext> lifecycle = context.getLifecycleManager();
		lifecycle.registerEventHandler(LifecycleEvents.COMMANDS,
				event -> MeteorfuckCommand.register(event.registrar()));
	}
}