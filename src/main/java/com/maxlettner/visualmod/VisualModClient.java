package com.maxlettner.visualmod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;

public class VisualModClient implements ClientModInitializer {
	public static final String MOD_ID = "visualmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		VisualConfig.load();
		ColoredLights.init();
		ClientChunkEvents.CHUNK_LOAD.register(ColoredLights::onChunkLoad);
		ClientChunkEvents.CHUNK_UNLOAD.register(ColoredLights::onChunkUnload);
		LOGGER.info("Visual Mod loaded (dynamic lights: {}, colored lights: {})", VisualConfig.dynamicLights, VisualConfig.coloredLights);
	}
}
