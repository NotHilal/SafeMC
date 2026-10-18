package dev.safemc.safeplots.world;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * Chat shows the world the player is in before their name: {@code [hub] <Steve> hi}. Runs last, so mutes and minigame
 * chat (which cancel the message) come first.
 */
public final class WorldChat {
    private WorldChat() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onChat(ServerChatEvent event) {
        Worlds worlds = Worlds.get();
        ServerPlayer player = event.getPlayer();
        String world = worlds == null ? null : worlds.nameOf(player.level());
        if (world == null) {
            world = player.level().dimension().identifier().getPath();
        }
        event.setCanceled(true);
        Component line = Component.empty()
                .append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(world).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("<").append(player.getDisplayName()).append("> "))
                .append(event.getMessage());
        player.level().getServer().getPlayerList().broadcastSystemMessage(line, false);
    }
}
