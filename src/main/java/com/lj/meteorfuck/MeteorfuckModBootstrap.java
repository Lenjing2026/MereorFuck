package com.lj.meteorfuck;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;

@SuppressWarnings("UnstableApiUsage")
public class MeteorfuckModBootstrap implements PluginBootstrap {

	@Override
	public void bootstrap(BootstrapContext context) {
		LifecycleEventManager<BootstrapContext> lifecycle = context.getLifecycleManager();

	}
}