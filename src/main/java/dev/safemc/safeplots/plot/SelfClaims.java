package dev.safemc.safeplots.plot;

import dev.safemc.safeplots.moderation.ModerationManager;
import dev.safemc.safeplots.world.Worlds;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * {@code /plot claimhere}: any player can make their own plot, a {@link #SIZE}x{@link #SIZE} square around where
 * they stand, from bedrock to the build limit. They first see its border and must {@code /plot confirm} within
 * {@link #CONFIRM_SECONDS} seconds. One such plot per player, and it counts toward their normal plot limit.
 */
public final class SelfClaims {
    public static final int SIZE = 50;
    /** Free blocks required between a self-made plot and any other plot. */
    public static final int GAP = 10;
    /** No self-made plot may come closer than this to a world's spawn. */
    public static final int SPAWN_RADIUS = 100;
    public static final int CONFIRM_SECONDS = 30;
    private static final int REFRESH_TICKS = 40;

    private record Pending(Plot plot, long expiresAt) {}

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private SelfClaims() {}

    /** The square around the player, full height. Not yet added to the manager. */
    public static Plot candidate(PlotManager manager, ServerPlayer player) {
        ServerLevel level = player.level();
        BlockPos at = player.blockPosition();
        int half = SIZE / 2;
        BlockPos a = new BlockPos(at.getX() - half, level.getMinY(), at.getZ() - half);
        BlockPos b = new BlockPos(at.getX() - half + SIZE - 1, level.getMaxY(), at.getZ() - half + SIZE - 1);
        return new Plot(freeName(manager, player.getGameProfile().name()), PlotManager.dimensionId(level), a, b);
    }

    /** Why the player can't have this plot, or null if they can. */
    public static @Nullable String problem(PlotManager manager, ServerPlayer player, Plot candidate) {
        UUID id = player.getUUID();
        ModerationManager moderation = ModerationManager.get();
        if (moderation != null && moderation.isJailed(id)) {
            return "You can't claim land while in jail.";
        }
        for (Plot plot : manager.plotsOwnedBy(id)) {
            if (plot.selfClaimed()) {
                return "You already have your own plot (" + plot.name() + ", around " + center(plot)
                        + "). Use /plot abandon " + plot.name() + " first if you want to claim somewhere else.";
            }
        }
        int owned = manager.plotsOwnedBy(id).size();
        int max = manager.maxClaims(id);
        if (owned >= max) {
            return "You already own " + owned + "/" + max + " plots. Ask an admin for another claim slot.";
        }
        ServerLevel level = manager.level(candidate.dimension());
        if (level != null) {
            BlockPos spawn = spawnOf(level);
            BlockPos min = candidate.min(), max2 = candidate.max();
            int dx = Math.max(0, Math.max(min.getX() - spawn.getX(), spawn.getX() - max2.getX()));
            int dz = Math.max(0, Math.max(min.getZ() - spawn.getZ(), spawn.getZ() - max2.getZ()));
            int distance = Math.max(dx, dz);
            if (distance < SPAWN_RADIUS) {
                return "Too close to spawn: plots must be at least " + SPAWN_RADIUS + " blocks from it. Move about "
                        + (SPAWN_RADIUS - distance) + " blocks further away.";
            }
        }
        BlockPos min = candidate.min(), max2 = candidate.max();
        Plot withGap = new Plot("gap", candidate.dimension(),
                new BlockPos(min.getX() - GAP, min.getY(), min.getZ() - GAP), new BlockPos(max2.getX() + GAP, max2.getY(), max2.getZ() + GAP));
        for (Plot other : manager.plots()) {
            if (other.overlaps(withGap)) {
                String whose = other.owner() == null ? "the plot " + other.name() : manager.nameOf(other.owner()) + "'s plot (" + other.name() + ")";
                return (other.overlaps(candidate) ? "This would overlap " : "Too close to ") + whose
                        + ". Plots need " + GAP + " free blocks between them; move away from it and try again.";
            }
        }
        return null;
    }

    /** Remembers the candidate and shows its border until it is confirmed, cancelled or expires. */
    public static void propose(ServerPlayer player, Plot candidate) {
        PlotBorders.hide(player.getUUID());
        PENDING.put(player.getUUID(), new Pending(candidate, System.currentTimeMillis() + CONFIRM_SECONDS * 1000L));
        PlotBorders.draw(player, candidate);
    }

    /** The waiting claim, or null if there is none (or it expired). Removes it. */
    public static @Nullable Plot take(UUID player) {
        Pending pending = PENDING.remove(player);
        return pending == null || pending.expiresAt() < System.currentTimeMillis() ? null : pending.plot();
    }

    public static boolean cancel(UUID player) {
        return PENDING.remove(player) != null;
    }

    /**
     * Creates the plot for the player after checking everything again (something may have changed while they
     * were deciding). Returns the problem, or null on success.
     */
    public static @Nullable String create(PlotManager manager, ServerPlayer player, Plot candidate) {
        String problem = problem(manager, player, candidate);
        if (problem != null) {
            return problem;
        }
        Plot plot = new Plot(freeName(manager, candidate.name()), candidate.dimension(), candidate.min(), candidate.max());
        plot.setOwner(player.getUUID());
        plot.setSelfClaimed(true);
        manager.rememberName(player.nameAndId());
        String overlap = manager.create(plot);
        return overlap == null ? null : "This would overlap " + overlap + ".";
    }

    /** Where a world's players spawn (X/Z), used to keep the spawn area free. */
    private static BlockPos spawnOf(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (level == server.overworld()) {
            return server.getRespawnData().pos();
        }
        Worlds worlds = Worlds.get();
        Worlds.World world = worlds == null ? null : worlds.worldOf(level);
        if (world != null && world.spawn() != null) {
            return BlockPos.containing(Worlds.pos(world.spawn()));
        }
        return BlockPos.ZERO; // nether, end, and worlds nobody has visited yet: /mvtp lands near 0,0
    }

    private static String freeName(PlotManager manager, String base) {
        String name = base;
        for (int i = 2; manager.plot(name) != null; i++) {
            name = base + "_" + i;
        }
        return name;
    }

    public static String center(Plot plot) {
        BlockPos min = plot.min(), max = plot.max();
        return (min.getX() + max.getX()) / 2 + ", " + (min.getZ() + max.getZ()) / 2 + dimensionSuffix(plot.dimension());
    }

    private static String dimensionSuffix(String dimension) {
        return dimension.equals(Level.OVERWORLD.identifier().toString()) ? "" : " in " + dimension;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (PENDING.isEmpty() || server.getTickCount() % REFRESH_TICKS != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
            } else if (entry.getValue().expiresAt() < now) {
                it.remove();
                player.sendSystemMessage(Component.literal("Your plot claim expired. Run /plot claimhere again if you still want it.")
                        .withStyle(ChatFormatting.GRAY));
            } else {
                PlotBorders.draw(player, entry.getValue().plot());
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }
}
