package com.maxlettner.visualmod;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Block-atlas sprite source that creates an "ore pixels only" texture for every {@code *_ore} block texture.
 *
 * <p>Each ore texture is compared with the rock it is drawn on (stone, deepslate, netherrack,
 * end stone): every pixel whose colour also appears in the rock texture becomes transparent, the
 * rest (the actual ore) is kept. Comparing colours rather than positions matters, because ore
 * textures repaint the rock around the ore in a different pattern using the same palette. The result is registered as {@code visualmod:block/<namespace>/<name>}. Because it reads
 * the textures of the active resource packs, it follows texture packs automatically.
 *
 * <p>Listed in {@code assets/minecraft/atlases/blocks.json}, so it runs whenever the block atlas is rebuilt.
 */
public final class OreMaskSpriteSource implements SpriteSource {
	public static final OreMaskSpriteSource INSTANCE = new OreMaskSpriteSource();
	public static final MapCodec<OreMaskSpriteSource> CODEC = MapCodec.unit(INSTANCE);

	/** Ore texture id (e.g. {@code minecraft:block/diamond_ore}) -> generated mask sprite id. */
	private static final Map<Identifier, Identifier> MASKS = new ConcurrentHashMap<>();

	/** Colours are bucketed at 5 bits per channel; a pixel within one bucket of a rock colour counts as rock. */
	private static final int BUCKET_BITS = 5;
	/** If more than this share of pixels differ, the texture is not "rock + ore" and is skipped. */
	private static final float MAX_ORE_COVERAGE = 0.6f;

	private OreMaskSpriteSource() {
	}

	/** Returns the mask sprite id for an ore texture, or {@code null} if there is none. */
	public static @Nullable Identifier maskFor(Identifier oreTexture) {
		return MASKS.get(oreTexture);
	}

	@Override
	public void run(ResourceManager resourceManager, Output output) {
		MASKS.clear();
		int created = 0;

		Map<Identifier, Resource> files = resourceManager.listResources("textures/block", id -> id.getPath().endsWith("_ore.png"));

		for (Map.Entry<Identifier, Resource> entry : files.entrySet()) {
			Identifier texture = TEXTURE_ID_CONVERTER.fileToId(entry.getKey());
			Identifier baseTexture = baseRockFor(texture);
			Optional<Resource> base = resourceManager.getResource(TEXTURE_ID_CONVERTER.idToFile(baseTexture));

			if (base.isEmpty()) {
				continue;
			}

			NativeImage mask = createMask(entry.getValue(), base.get(), texture);

			if (mask == null) {
				continue;
			}

			Identifier maskId = Identifier.fromNamespaceAndPath(VisualModClient.MOD_ID,
					"block/" + texture.getNamespace() + "/" + texture.getPath().substring("block/".length()));
			FrameSize size = new FrameSize(mask.getWidth(), mask.getHeight());
			output.add(maskId, loader -> new SpriteContents(maskId, size, mask));
			MASKS.put(texture, maskId);
			created++;
		}

		VisualModClient.LOGGER.info("Generated {} glowing ore textures", created);
	}

	private static Identifier baseRockFor(Identifier oreTexture) {
		String name = oreTexture.getPath().substring(oreTexture.getPath().lastIndexOf('/') + 1);
		String base;

		if (name.contains("deepslate")) {
			base = "deepslate";
		} else if (name.startsWith("nether_")) {
			base = "netherrack";
		} else if (name.startsWith("end_")) {
			base = "end_stone";
		} else {
			base = "stone";
		}

		return Identifier.withDefaultNamespace("block/" + base);
	}

	private static @Nullable NativeImage createMask(Resource oreResource, Resource baseResource, Identifier texture) {
		try (NativeImage ore = read(oreResource); NativeImage base = read(baseResource)) {
			int w = ore.getWidth();
			int h = ore.getHeight();

			// Animated ore textures (several frames stacked vertically) are not supported.
			if (w != h) {
				return null;
			}

			boolean[] rockColours = rockPalette(base);
			boolean[] isOre = new boolean[w * h];
			int candidates = 0;
			int coloured = 0;

			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int o = ore.getPixel(x, y);

					if ((o >>> 24) != 0 && !rockColours[bucket(o)]) {
						isOre[y * w + x] = true;
						candidates++;

						if (!isGrey(o)) {
							coloured++;
						}
					}
				}
			}

			// Coloured ores (iron, gold, diamond, ...) have neutral-grey rim highlights drawn around the ore.
			// Those are not ore, so drop them. Grey ores (coal) keep their greys.
			boolean dropGreys = coloured * 2 >= candidates;
			NativeImage mask = new NativeImage(ore.format(), w, h, true);
			int orePixels = 0;

			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int o = ore.getPixel(x, y);

					if (isOre[y * w + x] && !(dropGreys && isGrey(o))) {
						mask.setPixel(x, y, o | 0xFF000000);
						orePixels++;
					} else {
						mask.setPixel(x, y, 0);
					}
				}
			}

			if (orePixels == 0 || orePixels > w * h * MAX_ORE_COVERAGE) {
				VisualModClient.LOGGER.debug("Skipping glow for {}: {} of {} pixels differ from its base rock", texture, orePixels, w * h);
				mask.close();
				return null;
			}

			return mask;
		} catch (IOException e) {
			VisualModClient.LOGGER.warn("Could not create glow texture for {}", texture, e);
			return null;
		}
	}

	/** Marks every colour bucket used by the rock texture, plus its direct neighbours (small tolerance). */
	private static boolean[] rockPalette(NativeImage rock) {
		int size = 1 << BUCKET_BITS;
		boolean[] used = new boolean[size * size * size];

		for (int y = 0; y < rock.getHeight(); y++) {
			for (int x = 0; x < rock.getWidth(); x++) {
				int c = rock.getPixel(x, y);
				int r = channel(c, 16), g = channel(c, 8), b = channel(c, 0);

				for (int dr = -1; dr <= 1; dr++) {
					for (int dg = -1; dg <= 1; dg++) {
						for (int db = -1; db <= 1; db++) {
							int nr = r + dr, ng = g + dg, nb = b + db;

							if (nr >= 0 && ng >= 0 && nb >= 0 && nr < size && ng < size && nb < size) {
								used[(nr << (2 * BUCKET_BITS)) | (ng << BUCKET_BITS) | nb] = true;
							}
						}
					}
				}
			}
		}

		return used;
	}

	/** True for neutral greys (all three channels within 6 of each other). */
	private static boolean isGrey(int argb) {
		int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
		return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 6;
	}

	private static int bucket(int argb) {
		return (channel(argb, 16) << (2 * BUCKET_BITS)) | (channel(argb, 8) << BUCKET_BITS) | channel(argb, 0);
	}

	private static int channel(int argb, int shift) {
		return ((argb >> shift) & 0xFF) >> (8 - BUCKET_BITS);
	}

	private static NativeImage read(Resource resource) throws IOException {
		try (InputStream in = resource.open()) {
			return NativeImage.read(in);
		}
	}

	@Override
	public MapCodec<? extends SpriteSource> codec() {
		return CODEC;
	}
}
