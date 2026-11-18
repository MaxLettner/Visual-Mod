package com.maxlettner.visualmod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;

public class VisualModClient implements ClientModInitializer {
	public static final String MOD_ID = "visualmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		VisualConfig.load();
		OreGlow.register();
		LOGGER.info("Visual Mod loaded (ore glow: {}, dynamic lights: {})", VisualConfig.oreGlow, VisualConfig.dynamicLights);
	}
}
