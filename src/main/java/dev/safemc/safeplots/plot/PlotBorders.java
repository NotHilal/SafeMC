package dev.safemc.safeplots.plot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * {@code /plot showlimits}: outlines a plot with glowing particles, sent only to the player who asked.
 * Particles are a vanilla feature, so no client mod is needed. Only the part of the outline near the
 * player is drawn, which keeps huge or full-height plots cheap.
 */
public final class PlotBorders {
    private static final int REFRESH_TICKS = 40;      // end rod particles live ~3 s, so they overlap slightly
    private static final int HORIZONTAL_RANGE = 48;   // draw edges within this many blocks of the player
    private static final int VERTICAL_RANGE = 16;     // corner pillars go this far above/below the player
    private static final int MAX_POINTS = 3000;

    private static final Map<UUID, String> SHOWING = new HashMap<>();

    private PlotBorders() {}

    public static @Nullable String showing(UUID player) {
        return SHOWING.get(player);
    }

    public static void show(ServerPlayer player, Plot plot) {
        SHOWING.put(player.getUUID(), plot.name());
        draw(player, plot);
    }

    public static void hide(UUID player) {
        SHOWING.remove(player);
    }

    /** Owner and trusted players may see their plot's borders; admins may see any plot's. */
    public static boolean canView(ServerPlayer player, Plot plot) {
        return plot.isMember(player.getUUID()) || PlotManager.isAdmin(player);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        PlotManager manager = PlotManager.get();
        if (SHOWING.isEmpty() || manager == null || server.getTickCount() % REFRESH_TICKS != 0) {
            return;
        }
        Iterator<Map.Entry<UUID, String>> it = SHOWING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, String> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Plot plot = manager.plot(entry.getValue());
            if (player == null || plot == null || !canView(player, plot)) {
                it.remove(); // logged out, plot deleted, or access removed
                continue;
            }
            draw(player, plot);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        hide(event.getEntity().getUUID());
    }

    private static void draw(ServerPlayer player, Plot plot) {
        if (!plot.dimension().equals(PlotManager.dimensionId(player.level()))) {
            return;
        }
        BlockPos min = plot.min(), max = plot.max();
        // The outline sits on the outer faces of the plot's blocks.
        double x0 = min.getX(), z0 = min.getZ(), x1 = max.getX() + 1, z1 = max.getZ() + 1;
        double bottom = min.getY(), top = max.getY() + 1;
        double px = player.getX(), py = player.getY(), pz = player.getZ();

        List<double[]> points = new ArrayList<>();
        // A ring at the player's height (clamped into the plot) so the border is always visible nearby,
        // plus the real floor and ceiling when they're close.
        double eye = Math.max(bottom, Math.min(top, Math.floor(py) + 0.5));
        ring(points, x0, z0, x1, z1, eye, px, pz);
        if (Math.abs(bottom - py) <= VERTICAL_RANGE * 2 && Math.abs(bottom - eye) > 1) {
            ring(points, x0, z0, x1, z1, bottom, px, pz);
        }
        if (Math.abs(top - py) <= VERTICAL_RANGE * 2 && Math.abs(top - eye) > 1) {
            ring(points, x0, z0, x1, z1, top, px, pz);
        }
        double from = Math.max(bottom, Math.floor(py) - VERTICAL_RANGE), to = Math.min(top, Math.floor(py) + VERTICAL_RANGE);
        for (double[] corner : new double[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
            if (near(corner[0], corner[1], px, pz)) {
                for (double y = from; y <= to; y += 1) {
                    points.add(new double[] {corner[0], y, corner[1]});
                }
            }
        }

        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        for (int i = 0; i < points.size() && i < MAX_POINTS; i++) {
            double[] p = points.get(i);
            packets.add(new ClientboundLevelParticlesPacket(ParticleTypes.END_ROD, true, true, p[0], p[1], p[2], 0F, 0F, 0F, 0F, 1));
        }
        if (!packets.isEmpty()) {
            player.connection.send(new ClientboundBundlePacket(packets));
        }
    }

    private static void ring(List<double[]> points, double x0, double z0, double x1, double z1, double y, double px, double pz) {
        for (double x = x0; x <= x1; x += 1) {
            if (near(x, z0, px, pz)) points.add(new double[] {x, y, z0});
            if (near(x, z1, px, pz)) points.add(new double[] {x, y, z1});
        }
        for (double z = z0 + 1; z < z1; z += 1) {
            if (near(x0, z, px, pz)) points.add(new double[] {x0, y, z});
            if (near(x1, z, px, pz)) points.add(new double[] {x1, y, z});
        }
    }

    private static boolean near(double x, double z, double px, double pz) {
        return Math.abs(x - px) <= HORIZONTAL_RANGE && Math.abs(z - pz) <= HORIZONTAL_RANGE;
    }
}
