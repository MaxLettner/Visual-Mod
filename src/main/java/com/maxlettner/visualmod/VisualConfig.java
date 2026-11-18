package com.maxlettner.visualmod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Plain {@code .properties} config stored at {@code config/visualmod.properties}.
 *
 * <p>Read once at startup. Values are plain fields so the hot paths (chunk meshing,
 * entity rendering) can read them without any lookup cost.
 */
public final class VisualConfig {
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("visualmod.properties");

	// --- Glowing ores ---
	/** Make ore blocks render with a minimum brightness so they stand out in the dark. */
	public static boolean oreGlow = true;
	/** Minimum block-light level (0-15) used for ores that have no specific entry below. */
	public static int oreGlowLevel = 6;
	/** Per-block overrides, keyed by block id (e.g. {@code minecraft:diamond_ore}). */
	public static final Map<String, Integer> oreGlowOverrides = new HashMap<>();

	// --- Dynamic lights ---
	/** Light up the area around entities that hold or are light sources (torches, lanterns, ...). */
	public static boolean dynamicLights = true;
	/** Light from items held by players (including yourself). */
	public static boolean heldItemLights = true;
	/** Light from items held or worn by mobs and armor stands. */
	public static boolean mobItemLights = true;
	/** Light from dropped item entities (a torch lying on the ground). */
	public static boolean droppedItemLights = true;
	/** Light from burning entities. */
	public static boolean burningEntityLights = true;
	/** Torches, campfires etc. stop emitting light when the holder's head is underwater. */
	public static boolean waterSensitive = true;
	/** Only entities within this many blocks of the camera can emit dynamic light. */
	public static int maxDistance = 48;
	/** Upper bound on the number of simultaneous dynamic light sources (closest ones win). */
	public static int maxSources = 24;
	/** Extra item light levels, keyed by item id. Block items use their block's light level automatically. */
	public static final Map<String, Integer> itemLightOverrides = new HashMap<>();

	private VisualConfig() {
	}

	private static void defaults() {
		oreGlowOverrides.clear();
		oreGlowOverrides.put("minecraft:diamond_ore", 9);
		oreGlowOverrides.put("minecraft:deepslate_diamond_ore", 9);
		oreGlowOverrides.put("minecraft:emerald_ore", 9);
		oreGlowOverrides.put("minecraft:deepslate_emerald_ore", 9);
		oreGlowOverrides.put("minecraft:ancient_debris", 8);
		oreGlowOverrides.put("minecraft:gold_ore", 7);
		oreGlowOverrides.put("minecraft:deepslate_gold_ore", 7);
		oreGlowOverrides.put("minecraft:nether_gold_ore", 5);
		oreGlowOverrides.put("minecraft:lapis_ore", 7);
		oreGlowOverrides.put("minecraft:deepslate_lapis_ore", 7);
		oreGlowOverrides.put("minecraft:coal_ore", 4);
		oreGlowOverrides.put("minecraft:deepslate_coal_ore", 4);

		itemLightOverrides.clear();
		itemLightOverrides.put("minecraft:lava_bucket", 15);
		itemLightOverrides.put("minecraft:nether_star", 14);
		itemLightOverrides.put("minecraft:glow_berries", 12);
		itemLightOverrides.put("minecraft:glow_ink_sac", 10);
		itemLightOverrides.put("minecraft:blaze_rod", 10);
		itemLightOverrides.put("minecraft:blaze_powder", 9);
		itemLightOverrides.put("minecraft:fire_charge", 10);
		itemLightOverrides.put("minecraft:glowstone_dust", 8);
		itemLightOverrides.put("minecraft:magma_cream", 8);
		itemLightOverrides.put("minecraft:spectral_arrow", 8);
		itemLightOverrides.put("minecraft:prismarine_crystals", 6);
	}

	public static void load() {
		defaults();
		Properties props = new Properties();

		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				props.load(reader);
			} catch (IOException | IllegalArgumentException e) {
				VisualModClient.LOGGER.warn("Could not read {}, using defaults", PATH, e);
			}
		}

		oreGlow = bool(props, "oreGlow", oreGlow);
		oreGlowLevel = clamp(integer(props, "oreGlowLevel", oreGlowLevel), 0, 15);
		dynamicLights = bool(props, "dynamicLights", dynamicLights);
		heldItemLights = bool(props, "heldItemLights", heldItemLights);
		mobItemLights = bool(props, "mobItemLights", mobItemLights);
		droppedItemLights = bool(props, "droppedItemLights", droppedItemLights);
		burningEntityLights = bool(props, "burningEntityLights", burningEntityLights);
		waterSensitive = bool(props, "waterSensitive", waterSensitive);
		maxDistance = clamp(integer(props, "maxDistance", maxDistance), 8, 256);
		maxSources = clamp(integer(props, "maxSources", maxSources), 1, 128);

		for (String key : props.stringPropertyNames()) {
			if (key.startsWith("ore.")) {
				oreGlowOverrides.put(key.substring(4), clamp(integer(props, key, 0), 0, 15));
			} else if (key.startsWith("item.")) {
				itemLightOverrides.put(key.substring(5), clamp(integer(props, key, 0), 0, 15));
			}
		}

		save();
	}

	private static void save() {
		StringBuilder out = new StringBuilder();
		out.append("# Visual Mod config. Changes apply after restarting the game\n");
		out.append("# (ore settings also apply after a resource reload with F3+T).\n\n");
		out.append("# Glowing ores: ores render with a minimum brightness so they are visible in the dark.\n");
		line(out, "oreGlow", oreGlow);
		out.append("# Default minimum light level (0-15) for any block whose id ends in \"_ore\".\n");
		line(out, "oreGlowLevel", oreGlowLevel);
		out.append("# Per-ore levels: ore.<block id>=<0-15>. Set to 0 to disable glow for that ore.\n");
		oreGlowOverrides.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.forEach(e -> line(out, "ore." + e.getKey(), e.getValue()));

		out.append("\n# Dynamic lights: held/dropped light sources light up their surroundings.\n");
		line(out, "dynamicLights", dynamicLights);
		line(out, "heldItemLights", heldItemLights);
		line(out, "mobItemLights", mobItemLights);
		line(out, "droppedItemLights", droppedItemLights);
		line(out, "burningEntityLights", burningEntityLights);
		line(out, "waterSensitive", waterSensitive);
		out.append("# Only entities within this distance (blocks) of the camera emit dynamic light.\n");
		line(out, "maxDistance", maxDistance);
		out.append("# Max simultaneous dynamic light sources; the closest ones are kept.\n");
		line(out, "maxSources", maxSources);
		out.append("# Extra light levels for non-block items: item.<item id>=<0-15>.\n");
		itemLightOverrides.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.forEach(e -> line(out, "item." + e.getKey(), e.getValue()));

		try {
			Files.createDirectories(PATH.getParent());

			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				writer.write(out.toString());
			}
		} catch (IOException e) {
			VisualModClient.LOGGER.warn("Could not write {}", PATH, e);
		}
	}

	private static void line(StringBuilder out, String key, Object value) {
		// ':' is a key/value separator in .properties files, so block/item ids must escape it.
		out.append(key.replace(":", "\\:")).append('=').append(value).append('\n');
	}

	private static boolean bool(Properties props, String key, boolean fallback) {
		String value = props.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value.trim());
	}

	private static int integer(Properties props, String key, int fallback) {
		String value = props.getProperty(key);

		if (value == null) {
			return fallback;
		}

		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
