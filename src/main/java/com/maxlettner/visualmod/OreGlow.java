package com.maxlettner.visualmod;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.client.rendering.v1.SpriteSourceRegistry;
import net.fabricmc.fabric.api.util.TriState;

/**
 * Glowing ores: only the ore pixels render at full brightness (as if at light level 15),
 * the surrounding rock is lit normally, and no light is emitted into the world.
 *
 * <p>Ore blocks are wrapped once when models are baked. The wrapper emits the normal block, then a
 * second, coplanar cutout quad per face that uses the ore-only texture from {@link OreMaskSpriteSource}
 * with a fixed light value. Everything is baked into the chunk mesh, so there is no per-frame cost.
 */
public final class OreGlow {
	private OreGlow() {
	}

	public static void register() {
		SpriteSourceRegistry.register(Identifier.fromNamespaceAndPath(VisualModClient.MOD_ID, "ore_masks"), OreMaskSpriteSource.CODEC);

		ModelLoadingPlugin.register(pluginContext ->
				pluginContext.modifyBlockModelAfterBake().register(ModelModifier.WRAP_PHASE, (model, context) -> {
					if (!VisualConfig.oreGlow) {
						return model;
					}

					int level = glowLevel(context.state());

					if (level <= 0) {
						return model;
					}

					Map<TextureAtlasSprite, TextureAtlasSprite> masks = findMasks(model, context.baker());
					return masks.isEmpty() ? model : new GlowingOreModel(model, masks, level);
				}));
	}

	/** Configured brightness for a block (0 = off). */
	private static int glowLevel(BlockState state) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		Integer override = VisualConfig.oreGlowOverrides.get(id.toString());
		return override != null ? override : VisualConfig.oreGlowLevel;
	}

	/** Maps each ore sprite used by the model to its generated ore-only mask sprite. */
	private static Map<TextureAtlasSprite, TextureAtlasSprite> findMasks(BlockStateModel model, ModelBaker baker) {
		Map<TextureAtlasSprite, TextureAtlasSprite> masks = new IdentityHashMap<>();
		List<BlockStateModelPart> parts = new ArrayList<>();
		model.collectParts(RandomSource.create(42L), parts);

		for (BlockStateModelPart part : parts) {
			for (Direction face : FACES) {
				for (BakedQuad quad : part.getQuads(face)) {
					TextureAtlasSprite sprite = quad.materialInfo().sprite();

					if (masks.containsKey(sprite)) {
						continue;
					}

					Identifier maskId = OreMaskSpriteSource.maskFor(sprite.contents().name());

					if (maskId != null) {
						TextureAtlasSprite mask = baker.materials().get(new Material(maskId), () -> "visualmod ore glow").sprite();
						masks.put(sprite, mask);
					}
				}
			}
		}

		return masks;
	}

	/** All six faces plus {@code null} (quads that are never culled). */
	private static final Direction[] FACES = {
			Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null
	};

	private static final class GlowingOreModel extends WrapperBlockStateModel {
		private final Map<TextureAtlasSprite, TextureAtlasSprite> masks;
		private final boolean fullBright;
		/** Packed light coords ({@code sky << 20 | block << 4}) used when brightness is below 15. */
		private final int light;

		GlowingOreModel(BlockStateModel wrapped, Map<TextureAtlasSprite, TextureAtlasSprite> masks, int level) {
			super(wrapped);
			this.masks = masks;
			this.fullBright = level >= 15;
			this.light = level << 20 | level << 4;
		}

		@Override
		public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<@Nullable Direction> cullTest) {
			// The normal block, exactly as before.
			super.emitQuads(emitter, level, pos, state, random, cullTest);

			// Same model variant as the renderer picked: it seeds its random with the state's seed.
			List<BlockStateModelPart> parts = new ArrayList<>();
			wrapped.collectParts(RandomSource.create(state.getSeed(pos)), parts);

			for (BlockStateModelPart part : parts) {
				for (Direction face : FACES) {
					if (cullTest.test(face)) {
						continue;
					}

					for (BakedQuad quad : part.getQuads(face)) {
						TextureAtlasSprite mask = masks.get(quad.materialInfo().sprite());

						if (mask != null) {
							emitOverlay(emitter, quad, face, quad.materialInfo().sprite(), mask);
						}
					}
				}
			}
		}

		private void emitOverlay(QuadEmitter emitter, BakedQuad quad, @Nullable Direction face, TextureAtlasSprite from, TextureAtlasSprite to) {
			emitter.cullFace(face);
			emitter.fromBakedQuad(quad);

			// Move the UVs from the ore texture to the same spot on the ore-only texture.
			float uScale = (to.getU1() - to.getU0()) / (from.getU1() - from.getU0());
			float vScale = (to.getV1() - to.getV0()) / (from.getV1() - from.getV0());

			for (int i = 0; i < 4; i++) {
				emitter.uv(i,
						to.getU0() + (emitter.u(i) - from.getU0()) * uScale,
						to.getV0() + (emitter.v(i) - from.getV0()) * vScale);
			}

			emitter.chunkLayer(ChunkSectionLayer.CUTOUT);
			emitter.tintIndex(-1);
			emitter.ambientOcclusion(TriState.FALSE);
			emitter.shadeMode(ShadeMode.VANILLA);

			if (fullBright) {
				emitter.emissive(true);
			} else {
				emitter.lightmap(light, light, light, light);
			}

			emitter.emit();
		}

		@Override
		public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
			Object subkey = wrapped.createGeometryKey(level, pos, state, random);
			return subkey == null ? null : new Key(subkey);
		}

		private record Key(Object subkey) {
		}
	}
}
