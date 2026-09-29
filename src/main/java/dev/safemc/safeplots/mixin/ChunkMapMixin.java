package dev.safemc.safeplots.mixin;

import dev.safemc.safeplots.moderation.VanishTracking;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Lets vanish take effect immediately instead of waiting until someone moves to another chunk section. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin implements VanishTracking {
    @Shadow
    @Final
    private Int2ObjectMap<?> entityMap;

    @Override
    public void safeplots$refreshTracking(ServerPlayer player) {
        if (entityMap.get(player.getId()) instanceof TrackedEntityAccessor tracked) {
            tracked.safeplots$updatePlayers(player.level().players());
        }
    }
}
