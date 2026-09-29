package dev.safemc.safeplots.moderation;

import dev.safemc.safeplots.plot.PlotManager;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * Hides an admin from non-admin players: gone from the tab list, their body isn't sent to other clients
 * (see ServerPlayerMixin), and others see a normal "left the game" message. Other admins still see them.
 */
public final class Vanish {
    private Vanish() {}

    public static boolean canSeeVanished(ServerPlayer viewer) {
        return PlotManager.isAdmin(viewer);
    }

    /** Used by the tracking mixin: should {@code target}'s body be hidden from {@code viewer}? */
    public static boolean hiddenFrom(ServerPlayer target, ServerPlayer viewer) {
        ModerationManager manager = ModerationManager.get();
        return manager != null && target != viewer && manager.isVanished(target.getUUID()) && !canSeeVanished(viewer);
    }

    public static void set(ServerPlayer target, boolean vanish) {
        ModerationManager manager = ModerationManager.get();
        if (manager == null) {
            return;
        }
        manager.setVanished(target.getUUID(), vanish);
        Component message = Component.translatable(vanish ? "multiplayer.player.left" : "multiplayer.player.joined", target.getDisplayName())
                .withStyle(ChatFormatting.YELLOW);
        for (ServerPlayer viewer : target.level().getServer().getPlayerList().getPlayers()) {
            if (viewer == target || canSeeVanished(viewer)) {
                continue;
            }
            // Tab list first: the client needs the player info before it can show the body again.
            viewer.connection.send(vanish
                    ? new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID()))
                    : ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(target)));
            viewer.sendSystemMessage(message);
        }
        ((VanishTracking) target.level().getChunkSource().chunkMap).safeplots$refreshTracking(target);
    }

    /** A player joined: if they aren't an admin, remove vanished admins from their tab list. */
    static void hideVanishedFrom(ServerPlayer joined) {
        ModerationManager manager = ModerationManager.get();
        if (manager == null || canSeeVanished(joined) || manager.vanished().isEmpty()) {
            return;
        }
        joined.connection.send(new ClientboundPlayerInfoRemovePacket(List.copyOf(manager.vanished())));
    }
}
