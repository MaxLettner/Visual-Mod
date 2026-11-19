package com.maxlettner.visualmod;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;

/**
 * Coloured light for the torch families (torch, lantern, campfire and their soul, copper and redstone variants).
 *
 * <p>Brightness still comes from vanilla's light engine (and {@link DynamicLights}); this class only tints the
 * vertex colours of terrain while chunk meshes are built, based on which coloured sources are nearby.
 * So the cost is paid once per chunk rebuild, never per frame.
 *
 * <ul>
 *     <li>Placed sources are kept in a per-chunk-section index, built when a chunk loads (sections without
 *     any torch are skipped with a palette check) and updated on block changes.</li>
 *     <li>Held and dropped sources come from the {@link DynamicLights} snapshot, which carries their colour.</li>
 * </ul>
 */
public final class ColoredLights {
	public static final int WHITE = 0xFFFFFF;
	// Vanilla block light is already warm, so the warm torch tint is mild and the others are strongly saturated.
	private static final int WARM = 0xFFC890;
	private static final int SOUL = 0x1E78FF;
	private static final int REDSTONE = 0xFF2010;
	private static final int COPPER = 0x40FF70;

	/** Block -> RGB tint, 0 for blocks that don't have coloured light. Written once at startup. */
	private static final Reference2IntOpenHashMap<Block> BLOCK_COLORS = new Reference2IntOpenHashMap<>();

	/** Placed coloured sources, keyed by {@link SectionPos#asLong}. Arrays are never modified after publishing. */
	private static final ConcurrentHashMap<Long, Source[]> SECTIONS = new ConcurrentHashMap<>();
	/** Bumped on every index change so the per-thread candidate caches know when to refresh. */
	private static final AtomicInteger VERSION = new AtomicInteger();
	private static volatile Level indexedLevel;

	private static final Source[] NONE = new Source[0];
	private static final ThreadLocal<CandidateCache> CACHE = ThreadLocal.withInitial(CandidateCache::new);

	private ColoredLights() {
	}

	private record Source(int blockX, int blockY, int blockZ, int level, float r, float g, float b) {
	}

	private static final class CandidateCache {
		long section = Long.MIN_VALUE;
		int version = -1;
		Source[] sources = NONE;
	}

	// ------------------------------------------------------------------------------------------
	// Colours
	// ------------------------------------------------------------------------------------------

	/** Assigns colours by block id, so modded torches/lanterns/campfires are covered too. */
	public static void init() {
		BLOCK_COLORS.defaultReturnValue(0);

		for (Block block : BuiltInRegistries.BLOCK) {
			String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
			boolean family = path.endsWith("torch") || path.endsWith("campfire")
					|| (path.endsWith("lantern") && !path.equals("sea_lantern") && !path.equals("jack_o_lantern"));

			if (!family) {
				continue;
			}

			int color;

			if (path.contains("soul")) {
				color = SOUL;
			} else if (path.contains("copper")) {
				color = COPPER;
			} else if (path.contains("redstone")) {
				color = REDSTONE;
			} else {
				color = WARM;
			}

			BLOCK_COLORS.put(block, color);
		}
	}

	/** RGB colour of the light this state emits, or 0 if it has no coloured light (or is unlit). */
	public static int colorOf(BlockState state) {
		int color = BLOCK_COLORS.getInt(state.getBlock());
		return color != 0 && state.getLightEmission() > 0 ? color : 0;
	}

	/** RGB colour of the light a held item gives: its block's colour, otherwise white. */
	public static int colorOf(Item item) {
		if (item instanceof BlockItem blockItem) {
			int color = BLOCK_COLORS.getInt(blockItem.getBlock());

			if (color != 0) {
				return color;
			}
		}

		return WHITE;
	}

	// ------------------------------------------------------------------------------------------
	// Index of placed sources (main thread)
	// ------------------------------------------------------------------------------------------

	public static void onChunkLoad(Level level, LevelChunk chunk) {
		if (!VisualConfig.coloredLights) {
			return;
		}

		if (level != indexedLevel) {
			indexedLevel = level;
			SECTIONS.clear();
		}

		LevelChunkSection[] sections = chunk.getSections();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();

		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			int sectionY = chunk.getSectionYFromSectionIndex(i);
			long key = SectionPos.asLong(chunk.getPos().x(), sectionY, chunk.getPos().z());

			// Palette check first: most sections contain no torch at all.
			if (section.hasOnlyAir() || !section.maybeHas(state -> colorOf(state) != 0)) {
				SECTIONS.remove(key);
				continue;
			}

			List<Source> found = new ArrayList<>();
			int minY = SectionPos.sectionToBlockCoord(sectionY);

			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						int color = colorOf(state);

						if (color != 0) {
							found.add(source(minX + x, minY + y, minZ + z, state.getLightEmission(), color));
						}
					}
				}
			}

			publish(key, found.toArray(NONE));
		}
	}

	public static void onChunkUnload(Level level, LevelChunk chunk) {
		if (level != indexedLevel) {
			return;
		}

		int sections = chunk.getSections().length;

		for (int i = 0; i < sections; i++) {
			if (SECTIONS.remove(SectionPos.asLong(chunk.getPos().x(), chunk.getSectionYFromSectionIndex(i), chunk.getPos().z())) != null) {
				VERSION.incrementAndGet();
			}
		}
	}

	public static void onBlockChanged(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
		if (!VisualConfig.coloredLights || level != indexedLevel) {
			return;
		}

		int oldColor = oldState == null ? 0 : colorOf(oldState);
		int newColor = colorOf(newState);

		if (oldColor == 0 && newColor == 0) {
			return;
		}

		long key = SectionPos.asLong(pos);
		Source[] current = SECTIONS.getOrDefault(key, NONE);
		List<Source> next = new ArrayList<>(current.length + 1);

		for (Source s : current) {
			if (s.blockX() != pos.getX() || s.blockY() != pos.getY() || s.blockZ() != pos.getZ()) {
				next.add(s);
			}
		}

		if (newColor != 0) {
			next.add(source(pos.getX(), pos.getY(), pos.getZ(), newState.getLightEmission(), newColor));
		}

		publish(key, next.toArray(NONE));
	}

	private static void publish(long key, Source[] sources) {
		if (sources.length == 0) {
			SECTIONS.remove(key);
		} else {
			SECTIONS.put(key, sources);
		}

		VERSION.incrementAndGet();
	}

	private static Source source(int x, int y, int z, int level, int color) {
		return new Source(x, y, z, level, ((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f);
	}

	// ------------------------------------------------------------------------------------------
	// Tinting (chunk-builder threads)
	// ------------------------------------------------------------------------------------------

	/** Placed sources whose light can reach the given section; cached per thread, since a worker builds one section at a time. */
	private static Source[] candidates(int sx, int sy, int sz) {
		CandidateCache cache = CACHE.get();
		long key = SectionPos.asLong(sx, sy, sz);
		int version = VERSION.get();

		if (cache.section == key && cache.version == version) {
			return cache.sources;
		}

		Source[] result = NONE;

		if (!SECTIONS.isEmpty()) {
			List<Source> list = null;
			int minX = sx << 4, minY = sy << 4, minZ = sz << 4;

			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						Source[] sources = SECTIONS.get(SectionPos.asLong(sx + dx, sy + dy, sz + dz));

						if (sources == null) {
							continue;
						}

						for (Source s : sources) {
							// Distance from the source to the section's box, per axis.
							int ox = Math.max(0, Math.max(minX - s.blockX(), s.blockX() - (minX + 15)));
							int oy = Math.max(0, Math.max(minY - s.blockY(), s.blockY() - (minY + 15)));
							int oz = Math.max(0, Math.max(minZ - s.blockZ(), s.blockZ() - (minZ + 15)));

							if (ox * ox + oy * oy + oz * oz < s.level() * s.level()) {
								if (list == null) {
									list = new ArrayList<>();
								}

								list.add(s);
							}
						}
					}
				}
			}

			if (list != null) {
				result = list.toArray(NONE);
			}
		}

		cache.section = key;
		cache.version = version;
		cache.sources = result;
		return result;
	}

	/** Tints a shaded terrain quad (block-local vertex positions) at block {@code pos}. */
	public static void tintQuad(MutableQuadView quad, BlockPos pos) {
		if (!VisualConfig.coloredLights) {
			return;
		}

		Source[] placed = candidates(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
		float[] moving = DynamicLights.coloredSnapshot();

		if (placed.length == 0 && moving.length == 0) {
			return;
		}

		// Sample half a block in front of the face, where the light that hits it comes from.
		Direction face = quad.lightFace();
		float offX = pos.getX() + face.getStepX() * 0.5f;
		float offY = pos.getY() + face.getStepY() * 0.5f;
		float offZ = pos.getZ() + face.getStepZ() * 0.5f;

		for (int i = 0; i < 4; i++) {
			float px = offX + quad.x(i);
			float py = offY + quad.y(i);
			float pz = offZ + quad.z(i);
			float sumW = 0, r = 0, g = 0, b = 0;
			boolean coloured = false;

			for (Source s : placed) {
				float dx = s.blockX() + 0.5f - px, dy = s.blockY() + 0.5f - py, dz = s.blockZ() + 0.5f - pz;
				float w = s.level() - (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

				if (w > 0) {
					sumW += w;
					r += s.r() * w;
					g += s.g() * w;
					b += s.b() * w;
					coloured = true;
				}
			}

			for (int j = 0; j < moving.length; j += DynamicLights.COLOR_STRIDE) {
				float w = DynamicLights.falloff(moving[j + 3], moving[j] - px, moving[j + 1] - py, moving[j + 2] - pz);

				if (w > 0) {
					sumW += w;
					r += moving[j + 4] * w;
					g += moving[j + 5] * w;
					b += moving[j + 6] * w;
					coloured |= moving[j + 4] != 1f || moving[j + 5] != 1f || moving[j + 6] != 1f;
				}
			}

			if (!coloured || sumW <= 0) {
				continue;
			}

			// Average colour, normalised so the brightest channel is 1 (tinting shifts hue, brightness stays vanilla's).
			r /= sumW;
			g /= sumW;
			b /= sumW;
			float max = Math.max(r, Math.max(g, b));
			r /= max;
			g /= max;
			b /= max;

			// Full tint once block light reaches 8; faded where sky light is present (outdoors).
			int light = quad.lightmap(i);
			float block = Math.min(1f, (light & 0xFFFFF) / 16f / 8f);
			float sky = ((light >>> 20) & 0xF) / 15f;
			float strength = VisualConfig.coloredLightStrength * block * (1f - 0.6f * sky);

			if (strength <= 0.01f) {
				continue;
			}

			int c = quad.color(i);
			float mr = 1f - strength + strength * r;
			float mg = 1f - strength + strength * g;
			float mb = 1f - strength + strength * b;
			quad.color(i, (c & 0xFF000000)
					| ((int) (((c >> 16) & 0xFF) * mr) << 16)
					| ((int) (((c >> 8) & 0xFF) * mg) << 8)
					| (int) ((c & 0xFF) * mb));
		}
	}

	/** For tests: number of indexed placed coloured sources. */
	public static int indexedSourceCount() {
		return SECTIONS.values().stream().mapToInt(a -> a.length).sum();
	}
}
