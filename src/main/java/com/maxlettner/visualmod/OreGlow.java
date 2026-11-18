package com.maxlettner.visualmod;

import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
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
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadTransform;

/**
 * Glowing ores.
 *
 * <p>Ore block models are wrapped once, when models are baked. The wrapper raises the minimum
 * block light of the ore's quads, so ores stay faintly visible in dark caves. The light value is
 * baked into the chunk mesh, so there is zero per-frame cost: it is exactly as fast as rendering
 * an ordinary block.
 */
public final class OreGlow {
	private OreGlow() {
	}

	public static void register() {
		ModelLoadingPlugin.register(pluginContext ->
				pluginContext.modifyBlockModelAfterBake().register(ModelModifier.WRAP_PHASE, (model, context) -> {
					if (!VisualConfig.oreGlow) {
						return model;
					}

					int level = glowLevel(context.state());
					return level > 0 ? new GlowingOreModel(model, level) : model;
				}));
	}

	/** Returns the glow level for a block state, or 0 if it is not an ore. */
	private static int glowLevel(BlockState state) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		Integer override = VisualConfig.oreGlowOverrides.get(id.toString());

		if (override != null) {
			return override;
		}

		String path = id.getPath();
		boolean isOre = path.endsWith("_ore") || path.equals("ancient_debris");
		return isOre ? VisualConfig.oreGlowLevel : 0;
	}

	private static final class GlowingOreModel extends WrapperBlockStateModel {
		private final QuadTransform transform;

		GlowingOreModel(BlockStateModel wrapped, int level) {
			super(wrapped);
			// Packed light coords are (sky << 20 | block << 4); we only raise block light.
			int minLight = level << 4;
			this.transform = quad -> {
				quad.minLightmap(minLight);
				return true;
			};
		}

		@Override
		public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<@Nullable Direction> cullTest) {
			emitter.pushTransform(transform);
			super.emitQuads(emitter, level, pos, state, random, cullTest);
			emitter.popTransform();
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
