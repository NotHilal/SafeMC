package dev.safemc.safeplots.mixin;

import dev.safemc.safeplots.grave.GraveManager;
import dev.safemc.safeplots.plot.PlotManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** LavaFluid overrides spreadTo (lava flowing onto water makes stone), so it needs the same check. */
@Mixin(LavaFluid.class)
public abstract class LavaFluidMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void safeplots$blockCrossBorderFlow(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState target, CallbackInfo ci) {
        GraveManager graves = GraveManager.get();
        if (graves != null && level instanceof Level l && graves.at(l, pos) != null) {
            ci.cancel();
            return;
        }
        PlotManager manager = PlotManager.get();
        if (manager != null && level instanceof Level lvl && !manager.mayAffect(lvl, pos.relative(direction.getOpposite()), pos)) {
            ci.cancel();
        }
    }
}
