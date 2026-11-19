package com.maxlettner.visualmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;

import com.maxlettner.visualmod.ColoredLights;
import com.maxlettner.visualmod.compat.SodiumQuads;

/**
 * Sodium's chunk mesher: tints each terrain quad after Sodium has tinted and shaded it,
 * right before it is written to the mesh. Runs only while chunks are built.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class SodiumBlockRendererMixin {
	@Unique
	private BlockPos visualmod$pos;

	@Inject(method = "renderModel", at = @At("HEAD"))
	private void visualmod$capturePos(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
		this.visualmod$pos = pos;
	}

	@Inject(
			method = "processQuad",
			at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;bufferQuad(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;[FLnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;)V")
	)
	private void visualmod$colorLight(MutableQuadViewImpl quad, CallbackInfo ci) {
		if (this.visualmod$pos == null) {
			return;
		}

		ColoredLights.Tinter tinter = ColoredLights.begin(this.visualmod$pos, SodiumQuads.lightFace(quad));

		if (tinter != null) {
			for (int i = 0; i < 4; i++) {
				quad.setColor(i, ColoredLights.tint(tinter, quad.baseColor(i), SodiumQuads.light(quad, i),
						SodiumQuads.x(quad, i), SodiumQuads.y(quad, i), SodiumQuads.z(quad, i)));
			}
		}
	}
}
