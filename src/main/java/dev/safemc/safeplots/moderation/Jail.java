package dev.safemc.safeplots.moderation;

import dev.safemc.safeplots.plot.PlotManager;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps jailed players at the jail until their time is up, then sends them back to where they were.
 * Everything goes through {@link #tick}: it moves a newly jailed player in (also one who was offline when jailed),
 * pulls back anyone who got out (ender pearl, death, another dimension, ...) and releases them when time runs out.
 */
public final class Jail {
    /** How far (in blocks) a jailed player may get from the jail spawn before being pulled back. */
    public static final double RADIUS = 16;

    private Jail() {}

    static void tick(ServerPlayer player, ModerationManager manager) {
        ModerationManager.Jailed jailed = manager.jailed(player.getUUID());
        if (jailed == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (jailed.expired(now)) {
            release(player, manager, jailed);
            return;
        }
        ModerationManager.Spot jail = manager.jailSpot();
        if (jail == null || inside(player, jail)) {
            if (player.tickCount % 40 == 0) {
                player.sendSystemMessage(Component.literal("Jailed: " + Durations.format(jailed.until() - now) + " left")
                        .withStyle(ChatFormatting.RED), true);
            }
            return;
        }
        boolean entering = jailed.back() == null;
        if (entering) {
            manager.setJailed(player.getUUID(), new ModerationManager.Jailed(jailed.name(), jailed.until(), jailed.reason(), spotOf(player)));
        }
        if (!teleport(player, jail)) {
            return;
        }
        player.sendSystemMessage(Component.literal(entering
                ? "You have been jailed for " + Durations.format(jailed.until() - now) + "." + (jailed.reason().isEmpty() ? "" : " Reason: " + jailed.reason())
                : "You can't leave the jail.").withStyle(ChatFormatting.RED));
    }

    /** Ends the sentence and sends the player back to where they were before the jail. */
    static void release(ServerPlayer player, ModerationManager manager, ModerationManager.Jailed jailed) {
        manager.setJailed(player.getUUID(), null);
        if (jailed.back() != null) {
            teleport(player, jailed.back());
        }
        player.sendSystemMessage(Component.literal("You are out of jail.").withStyle(ChatFormatting.GREEN));
    }

    static ModerationManager.Spot spotOf(ServerPlayer player) {
        return new ModerationManager.Spot(PlotManager.dimensionId(player.level()),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    private static boolean inside(ServerPlayer player, ModerationManager.Spot jail) {
        return PlotManager.dimensionId(player.level()).equals(jail.dimension())
                && player.distanceToSqr(jail.x(), jail.y(), jail.z()) <= RADIUS * RADIUS;
    }

    private static boolean teleport(ServerPlayer player, ModerationManager.Spot spot) {
        PlotManager plots = PlotManager.get();
        ServerLevel level = plots == null ? null : plots.level(spot.dimension());
        if (level == null) {
            return false;
        }
        player.stopRiding();
        player.teleportTo(level, spot.x(), spot.y(), spot.z(), Set.of(), spot.yRot(), spot.xRot(), true);
        ModerationEvents.clearAnchor(player.getUUID()); // a frozen player is re-pinned at the new spot
        return true;
    }
}
