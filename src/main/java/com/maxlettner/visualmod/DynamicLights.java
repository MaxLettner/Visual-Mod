package com.maxlettner.visualmod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Lightweight dynamic lights: entities holding (or being) a light source light up their surroundings.
 *
 * <h2>How it stays cheap</h2>
 * <ul>
 *     <li>Light sources are collected while entities tick (no extra world scan), and only within
 *     {@link VisualConfig#maxDistance} of the camera. Only the closest {@link VisualConfig#maxSources} are kept.</li>
 *     <li>Terrain is only re-meshed when a source moves into a different block or changes brightness,
 *     and only the (at most 8) chunk sections its light can reach. Re-meshing runs on vanilla's
 *     background chunk-builder threads.</li>
 *     <li>The chunk mesher reads an immutable float array snapshot; with no light sources around,
 *     the hook costs one volatile read.</li>
 * </ul>
 */
public final class DynamicLights {
	/** Light falls off linearly to zero at this distance (blocks) from the source. */
	public static final float RADIUS = 7.5f;
	private static final float RADIUS_SQ = RADIUS * RADIUS;
	/** Stride of one source in {@link #snapshot}: x, y, z, luminance. */
	private static final int STRIDE = 4;

	/** Immutable snapshot read by the render and chunk-builder threads. */
	private static volatile float[] snapshot = new float[0];

	// --- Main thread state ---
	private static final List<Candidate> pending = new ArrayList<>();
	private static final Map<Integer, Candidate> active = new HashMap<>();
	private static final LongOpenHashSet dirtySections = new LongOpenHashSet();
	private static final Reference2IntOpenHashMap<Item> itemLightCache = new Reference2IntOpenHashMap<>();
	private static final int WATER_SENSITIVE_FLAG = 1 << 8;
	private static final Set<String> WATER_SENSITIVE_ITEMS = Set.of(
			"minecraft:torch", "minecraft:soul_torch", "minecraft:copper_torch",
			"minecraft:campfire", "minecraft:soul_campfire",
			"minecraft:fire_charge", "minecraft:blaze_powder"
	);

	private static boolean collecting;
	private static double camX, camY, camZ;
	private static double maxDistSq;
	private static Level lastLevel;

	static {
		itemLightCache.defaultReturnValue(-1);
	}

	private DynamicLights() {
	}

	private record Candidate(int id, float x, float y, float z, int blockX, int blockY, int blockZ, int luminance, double distSq) {
	}

	// ------------------------------------------------------------------------------------------
	// Render-side queries (any thread)
	// ------------------------------------------------------------------------------------------

	/** Dynamic light level (0-15, fractional) at the centre of the given block. */
	public static float lightAt(int blockX, int blockY, int blockZ) {
		float[] s = snapshot;

		if (s.length == 0) {
			return 0f;
		}

		float x = blockX + 0.5f;
		float y = blockY + 0.5f;
		float z = blockZ + 0.5f;
		float best = 0f;

		for (int i = 0; i < s.length; i += STRIDE) {
			float dx = s[i] - x;
			float dy = s[i + 1] - y;
			float dz = s[i + 2] - z;
			float distSq = dx * dx + dy * dy + dz * dz;

			if (distSq < RADIUS_SQ) {
				float light = s[i + 3] * (1f - (float) Math.sqrt(distSq) / RADIUS);

				if (light > best) {
					best = light;
				}
			}
		}

		return best;
	}

	public static boolean hasSources() {
		return snapshot.length != 0;
	}

	/**
	 * Combines vanilla packed light coords with a dynamic light level.
	 * Packed light coords are {@code sky << 20 | block << 4}, where the low 4 bits of the block
	 * part hold a fraction, which keeps the light falloff smooth.
	 */
	public static int combine(int packedLight, float dynamicLight) {
		int vanillaBlock = (packedLight >> 4) & 0xF;

		if (dynamicLight <= vanillaBlock) {
			return packedLight;
		}

		int block = Math.min((int) (dynamicLight * 16f), 15 << 4);
		return (packedLight & 0xFFF00000) | block;
	}

	// ------------------------------------------------------------------------------------------
	// Collection (main thread, driven by ClientLevelMixin)
	// ------------------------------------------------------------------------------------------

	public static void beginTick(Level level) {
		collecting = false;
		pending.clear();

		if (level != lastLevel) {
			// New world/dimension: forget everything, there is nothing to re-mesh.
			lastLevel = level;
			active.clear();
			snapshot = new float[0];
		}

		if (!VisualConfig.dynamicLights) {
			return;
		}

		Minecraft client = Minecraft.getInstance();

		if (client.gameRenderer == null) {
			return;
		}

		Vec3 cam = client.gameRenderer.mainCamera().position();
		camX = cam.x;
		camY = cam.y;
		camZ = cam.z;
		maxDistSq = (double) VisualConfig.maxDistance * VisualConfig.maxDistance;
		collecting = true;
	}

	public static void considerEntity(Entity entity) {
		if (!collecting) {
			return;
		}

		double x = entity.getX();
		double y = entity instanceof LivingEntity ? entity.getEyeY() : entity.getY() + 0.25;
		double z = entity.getZ();
		double dx = x - camX;
		double dy = y - camY;
		double dz = z - camZ;
		double distSq = dx * dx + dy * dy + dz * dz;

		if (distSq > maxDistSq || entity.isSpectator() || entity.isRemoved()) {
			return;
		}

		int luminance = 0;

		if (VisualConfig.burningEntityLights && entity.isOnFire()) {
			luminance = 14;
		}

		if (entity instanceof LivingEntity living) {
			boolean enabled = entity instanceof Player ? VisualConfig.heldItemLights : VisualConfig.mobItemLights;

			if (enabled) {
				boolean underwater = VisualConfig.waterSensitive && entity.isUnderWater();

				for (EquipmentSlot slot : EquipmentSlot.VALUES) {
					luminance = Math.max(luminance, itemLight(living.getItemBySlot(slot), underwater));
				}
			}
		} else if (entity instanceof ItemEntity item && VisualConfig.droppedItemLights) {
			boolean underwater = VisualConfig.waterSensitive && entity.isUnderWater();
			luminance = Math.max(luminance, itemLight(item.getItem(), underwater));
		}

		if (luminance <= 0) {
			return;
		}

		pending.add(new Candidate(entity.getId(), (float) x, (float) y, (float) z,
				Mth.floor(x), Mth.floor(y), Mth.floor(z), luminance, distSq));
	}

	public static void endTick() {
		if (!collecting && active.isEmpty()) {
			return;
		}

		collecting = false;

		if (pending.size() > VisualConfig.maxSources) {
			pending.sort(Comparator.comparingDouble(Candidate::distSq));
			pending.subList(VisualConfig.maxSources, pending.size()).clear();
		}

		boolean changed = false;
		Map<Integer, Candidate> next = new HashMap<>(Math.max(16, pending.size() * 2));

		for (Candidate now : pending) {
			Candidate before = active.get(now.id());

			if (before != null && before.blockX() == now.blockX() && before.blockY() == now.blockY()
					&& before.blockZ() == now.blockZ() && before.luminance() == now.luminance()) {
				// Same block, same brightness: keep the old position so the mesh stays valid.
				next.put(now.id(), before);
				continue;
			}

			if (before != null) {
				markDirty(before);
			}

			markDirty(now);
			next.put(now.id(), now);
			changed = true;
		}

		for (Candidate before : active.values()) {
			if (!next.containsKey(before.id())) {
				markDirty(before);
				changed = true;
			}
		}

		active.clear();
		active.putAll(next);

		if (changed) {
			float[] s = new float[active.size() * STRIDE];
			int i = 0;

			for (Candidate c : active.values()) {
				s[i++] = c.x();
				s[i++] = c.y();
				s[i++] = c.z();
				s[i++] = c.luminance();
			}

			// Publish first, then schedule rebuilds, so the rebuilt meshes see the new lights.
			snapshot = s;
			flushDirtySections();
		}
	}

	private static void markDirty(Candidate c) {
		int minX = SectionPos.blockToSectionCoord(Mth.floor(c.x() - RADIUS));
		int maxX = SectionPos.blockToSectionCoord(Mth.floor(c.x() + RADIUS));
		int minY = SectionPos.blockToSectionCoord(Mth.floor(c.y() - RADIUS));
		int maxY = SectionPos.blockToSectionCoord(Mth.floor(c.y() + RADIUS));
		int minZ = SectionPos.blockToSectionCoord(Mth.floor(c.z() - RADIUS));
		int maxZ = SectionPos.blockToSectionCoord(Mth.floor(c.z() + RADIUS));

		for (int sx = minX; sx <= maxX; sx++) {
			for (int sy = minY; sy <= maxY; sy++) {
				for (int sz = minZ; sz <= maxZ; sz++) {
					dirtySections.add(SectionPos.asLong(sx, sy, sz));
				}
			}
		}
	}

	private static void flushDirtySections() {
		Minecraft client = Minecraft.getInstance();

		if (client.level != null && client.levelExtractor != null) {
			for (LongIterator it = dirtySections.iterator(); it.hasNext(); ) {
				long section = it.nextLong();
				client.levelExtractor.setSectionDirty(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
			}
		}

		dirtySections.clear();
	}

	// ------------------------------------------------------------------------------------------
	// Item light levels
	// ------------------------------------------------------------------------------------------

	private static int itemLight(ItemStack stack, boolean underwater) {
		if (stack.isEmpty()) {
			return 0;
		}

		Item item = stack.getItem();
		int cached = itemLightCache.getInt(item);

		if (cached < 0) {
			cached = computeItemLight(item);
			itemLightCache.put(item, cached);
		}

		if (underwater && (cached & WATER_SENSITIVE_FLAG) != 0) {
			return 0;
		}

		return cached & 0xF;
	}

	private static int computeItemLight(Item item) {
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		Integer override = VisualConfig.itemLightOverrides.get(id);
		int light;

		if (override != null) {
			light = override;
		} else if (item instanceof BlockItem blockItem) {
			light = blockItem.getBlock().defaultBlockState().getLightEmission();
		} else {
			light = 0;
		}

		light = Mth.clamp(light, 0, 15);
		return WATER_SENSITIVE_ITEMS.contains(id) ? light | WATER_SENSITIVE_FLAG : light;
	}
}
