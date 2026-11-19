package com.maxlettner.visualmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl;

import com.maxlettner.visualmod.ColoredLights;

/**
 * Tints each terrain quad after Indigo (Fabric's default renderer) has shaded and tinted it,
 * while the chunk mesh is built. Not used when another renderer (e.g. Sodium) replaces Indigo.
 */
@Mixin(value = AltModelBlockRendererImpl.class, remap = false)
public abstract class IndigoBlockRendererMixin {
	@Shadow
	private BlockPos pos;

	@Inject(
			method = "transform",
			at = @At(value = "INVOKE", target = "Lnet/fabricmc/fabric/impl/client/indigo/renderer/render/AltModelBlockRendererImpl;tintQuad(Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/MutableQuadView;)V", shift = At.Shift.AFTER)
	)
	private void visualmod$colorLight(MutableQuadView quad, CallbackInfoReturnable<Boolean> cir) {
		ColoredLights.tintQuad(quad, pos);
	}
}
