package dev.safemc.safeplots.world;

import dev.safemc.safeplots.teleport.Teleports;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Players joining for the first time start at the spawn of the hub world ({@code /mv sethub}). */
public final class Hub {
    private Hub() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        Worlds worlds = Worlds.get();
        if (worlds == null || worlds.hub().equals("world") || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // leave_game is counted on every logout, so 0 means this is their first time on the server.
        if (player.getStats().getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)) > 0) {
            return;
        }
        Teleports.Destination dest = worlds.spawnDestination(worlds.hub());
        if (dest != null) {
            player.teleportTo(dest.level(), dest.pos().x, dest.pos().y, dest.pos().z, Set.of(), dest.yRot(), dest.xRot(), true);
        }
    }
}
