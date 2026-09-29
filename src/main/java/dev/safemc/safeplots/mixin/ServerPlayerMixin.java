package dev.safemc.safeplots.mixin;

import dev.safemc.safeplots.moderation.Vanish;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanished admins are not sent to other (non-admin) players' clients at all. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
    private void safeplots$vanish(ServerPlayer viewer, CallbackInfoReturnable<Boolean> cir) {
        if (Vanish.hiddenFrom((ServerPlayer) (Object) this, viewer)) {
            cir.setReturnValue(false);
        }
    }
}
