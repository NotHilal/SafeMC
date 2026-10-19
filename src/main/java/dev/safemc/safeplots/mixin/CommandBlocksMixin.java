package dev.safemc.safeplots.mixin;

import dev.safemc.safeplots.world.Worlds;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Command blocks only work in worlds with {@code /mv commandblocks <world> on}: elsewhere neither blocks nor minecarts
 * run, as with the commandBlocksWork game rule off (they keep their command and can still be edited).
 */
@Mixin(ServerLevel.class)
public abstract class CommandBlocksMixin {
    @Inject(method = "isCommandBlockEnabled", at = @At("HEAD"), cancellable = true)
    private void safeplots$commandBlocksPerWorld(CallbackInfoReturnable<Boolean> cir) {
        Worlds worlds = Worlds.get();
        if (worlds != null && !worlds.commandBlocks((ServerLevel) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
