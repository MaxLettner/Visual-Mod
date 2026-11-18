package com.maxlettner.visualmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;

import com.maxlettner.visualmod.DynamicLights;

/**
 * Adds dynamic light to the default brightness lookup that vanilla (and Fabric's Indigo renderer)
 * use when building chunk meshes and lighting held items in first person.
 */
@Mixin(value = LightCoordsUtil.BrightnessGetter.class, priority = 900)
public interface BrightnessGetterMixin {
	@ModifyReturnValue(method = "lambda$static$0", at = @At("RETURN"), remap = false, allow = 1)
	private static int visualmod$addDynamicLight(int original, BlockAndLightGetter level, BlockPos pos) {
		if (!DynamicLights.hasSources()) {
			return original;
		}

		float light = DynamicLights.lightAt(pos.getX(), pos.getY(), pos.getZ());

		if (light <= 0f || level.getBlockState(pos).isSolidRender()) {
			return original;
		}

		return DynamicLights.combine(original, light);
	}
}
