package com.maxlettner.visualmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

import com.maxlettner.visualmod.DynamicLights;

/**
 * Collects dynamic light sources while the client ticks its entities, so no extra pass over
 * the world's entities is needed.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "tickEntities", at = @At("HEAD"))
	private void visualmod$beginTick(CallbackInfo ci) {
		DynamicLights.beginTick((ClientLevel) (Object) this);
	}

	@Inject(method = "tickEntities", at = @At("TAIL"))
	private void visualmod$endTick(CallbackInfo ci) {
		DynamicLights.endTick();
	}

	@Inject(
			method = "tickNonPassenger",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V", shift = At.Shift.AFTER)
	)
	private void visualmod$onTickNonPassenger(Entity entity, CallbackInfo ci) {
		DynamicLights.considerEntity(entity);
	}

	@Inject(
			method = "tickPassenger",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;rideTick()V", shift = At.Shift.AFTER)
	)
	private void visualmod$onTickPassenger(Entity vehicle, Entity passenger, CallbackInfo ci) {
		DynamicLights.considerEntity(passenger);
	}
}
