package dev.safemc.safeplots.mixin;

import dev.safemc.safeplots.plot.PlotManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hoppers and hopper minecarts pull from the block above them. Stop them pulling out of a plot
 * (containers or dropped items) when the hopper itself is outside that plot.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void safeplots$blockCrossBorderPull(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        PlotManager manager = PlotManager.get();
        if (manager == null) {
            return;
        }
        BlockPos hopperPos = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY(), hopper.getLevelZ());
        BlockPos sourcePos = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0, hopper.getLevelZ());
        if (!manager.mayAffect(level, hopperPos, sourcePos)) {
            cir.setReturnValue(false);
        }
    }
}
