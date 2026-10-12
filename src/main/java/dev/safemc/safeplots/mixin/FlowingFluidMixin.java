package dev.safemc.safeplots.mixin;

import dev.safemc.safeplots.grave.GraveManager;
import dev.safemc.safeplots.plot.PlotManager;
import dev.safemc.safeplots.world.Portals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops water and lava from flowing into a plot from outside it (or from another owner's plot), and keeps the water
 * of a water portal inside its box.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void safeplots$blockCrossBorderFlow(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState target, CallbackInfo ci) {
        GraveManager graves = GraveManager.get();
        if (graves != null && level instanceof Level l && graves.at(l, pos) != null) {
            ci.cancel();
            return;
        }
        if (!(level instanceof Level lvl)) {
            return;
        }
        // `direction` is the flow direction, so the fluid comes from the opposite side.
        BlockPos from = pos.relative(direction.getOpposite());
        if (Portals.keepsFluidIn(lvl, from, pos)) {
            ci.cancel();
            return;
        }
        PlotManager manager = PlotManager.get();
        if (manager != null && !manager.mayAffect(lvl, from, pos)) {
            ci.cancel();
        }
    }
}
