package dev.safemc.safeplots.moderation;

import net.minecraft.server.level.ServerPlayer;

/** Added to ChunkMap by ChunkMapMixin: re-checks right away which players may see this player. */
public interface VanishTracking {
    void safeplots$refreshTracking(ServerPlayer player);
}
