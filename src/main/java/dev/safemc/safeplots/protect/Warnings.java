package dev.safemc.safeplots.protect;

import dev.safemc.safeplots.plot.Plot;
import dev.safemc.safeplots.plot.PlotManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Action-bar "this land belongs to..." messages, rate limited so holding a mouse button doesn't spam. */
final class Warnings {
    private static final long COOLDOWN_MS = 1500;
    private static final Map<UUID, Long> LAST_SENT = new HashMap<>();

    private Warnings() {}

    static void denied(PlotManager manager, Player player, Plot plot) {
        warn(player, plot.owner() == null
                ? "This plot is not claimed yet. Claim it at its sign."
                : "This land belongs to " + manager.nameOf(plot.owner()) + ".");
    }

    static void warn(Player player, String text) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_SENT.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        LAST_SENT.put(player.getUUID(), now);
        serverPlayer.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.RED), true);
    }

    static void forget(UUID player) {
        LAST_SENT.remove(player);
    }
}
