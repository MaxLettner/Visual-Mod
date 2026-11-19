package com.maxlettner.visualmod.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import com.maxlettner.visualmod.ColoredLights;

/** Keeps the coloured-light index up to date when torches are placed, broken or toggled on the client. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Shadow
	@Final
	private Level level;

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void visualmod$onSetBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		if (level.isClientSide()) {
			ColoredLights.onBlockChanged(level, pos, cir.getReturnValue(), state);
		}
	}
}
