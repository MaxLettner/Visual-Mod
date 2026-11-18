package com.maxlettner.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import com.maxlettner.visualmod.DynamicLights;

/** Entities standing near a dynamic light source (including the holder) are lit by it too. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {
	@ModifyReturnValue(method = "getBlockLightLevel", at = @At("RETURN"))
	private int visualmod$addDynamicLight(int original, T entity, BlockPos pos) {
		if (original >= 15 || !DynamicLights.hasSources()) {
			return original;
		}

		int light = (int) DynamicLights.lightAt(pos.getX(), pos.getY(), pos.getZ());
		return Math.max(original, light);
	}
}
