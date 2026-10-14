package dev.safemc.safeplots.mixin;

import dev.safemc.safeplots.world.Worlds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Worlds with {@code /mv alwaysday on} have no weather: no rain, snow, thunder or lightning. */
@Mixin(Level.class)
public abstract class LevelWeatherMixin {
    @Inject(method = "canHaveWeather", at = @At("HEAD"), cancellable = true)
    private void safeplots$noWeather(CallbackInfoReturnable<Boolean> cir) {
        Worlds worlds = Worlds.get();
        if (worlds != null && (Object) this instanceof ServerLevel level && worlds.alwaysDay(level)) {
            cir.setReturnValue(false);
        }
    }
}
