package dev.safemc.safeplots.mixin;

import dev.safemc.safeplots.world.Portals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Nether and End portals inside a /mv portal box lead to that portal's world instead of the vanilla one. */
@Mixin({NetherPortalBlock.class, EndPortalBlock.class})
public abstract class PortalBlockMixin {
    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void safeplots$worldPortal(ServerLevel level, Entity entity, BlockPos pos, CallbackInfoReturnable<TeleportTransition> cir) {
        boolean[] blocked = {false};
        TeleportTransition transition = Portals.vanillaPortalDestination(level, entity, pos, blocked);
        if (transition != null || blocked[0]) {
            cir.setReturnValue(transition);
        }
    }
}
