package dev.safemc.safeplots.mixin;

import dev.safemc.safeplots.plot.PlotManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dispensers outside a plot can't fire into it (water, lava, fire, TNT, flint and steel, shears, ...). */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void safeplots$blockCrossBorderDispense(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        PlotManager manager = PlotManager.get();
        if (manager != null && !manager.mayAffect(level, pos, pos.relative(state.getValue(DispenserBlock.FACING)))) {
            ci.cancel();
        }
    }
}
