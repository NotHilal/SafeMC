package dev.safemc.safeplots;

import dev.safemc.safeplots.command.PlotCommand;
import dev.safemc.safeplots.command.PlotWand;
import dev.safemc.safeplots.grave.GraveCommand;
import dev.safemc.safeplots.grave.GraveEvents;
import dev.safemc.safeplots.grave.GraveManager;
import dev.safemc.safeplots.moderation.ModerationCommands;
import dev.safemc.safeplots.moderation.ModerationEvents;
import dev.safemc.safeplots.moderation.ModerationManager;
import dev.safemc.safeplots.plot.PlotBorders;
import dev.safemc.safeplots.plot.PlotManager;
import dev.safemc.safeplots.protect.PetProtection;
import dev.safemc.safeplots.protect.ProtectionEvents;
import dev.safemc.safeplots.teleport.HomeManager;
import dev.safemc.safeplots.teleport.TeleportCommands;
import dev.safemc.safeplots.teleport.Teleports;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@Mod(SafePlots.MOD_ID)
public final class SafePlots {
    public static final String MOD_ID = "safeplots";

    public SafePlots() {
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent e) -> {
            PlotManager.start(e.getServer());
            ModerationManager.start(e.getServer());
            HomeManager.start(e.getServer());
        });
        // Graves decode items with the server's registries, which are ready by ServerStarting.
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent e) -> GraveManager.start(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            GraveManager.stop();
            ModerationManager.stop();
            HomeManager.stop();
            PlotManager.stop();
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> {
            PlotCommand.register(e.getDispatcher());
            GraveCommand.register(e.getDispatcher());
            ModerationCommands.register(e.getDispatcher());
            TeleportCommands.register(e.getDispatcher());
        });
        // Moderation first: a frozen player is stopped before any other rule is even checked.
        NeoForge.EVENT_BUS.register(ModerationEvents.class);
        NeoForge.EVENT_BUS.register(Teleports.class);
        NeoForge.EVENT_BUS.register(GraveEvents.class);
        NeoForge.EVENT_BUS.register(PlotWand.class);
        NeoForge.EVENT_BUS.register(PlotBorders.class);
        NeoForge.EVENT_BUS.register(PetProtection.class);
        NeoForge.EVENT_BUS.register(ProtectionEvents.class);
    }
}
