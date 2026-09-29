package dev.safemc.safeplots.plot;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Holds all plot state for the running server. Everything here is only touched from the server
 * thread, so no locking is needed: two players clicking the same sign are handled one after the other.
 */
public final class PlotManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "safeplots.json";

    private static @Nullable PlotManager instance;

    private final MinecraftServer server;
    private final Path file;
    private final Map<String, Plot> plots = new TreeMap<>();
    private final Map<UUID, PlayerData> players = new HashMap<>();

    // Lookup indexes, rebuilt whenever plots change: dimension -> chunk key -> plots / sign pos -> plot.
    private final Map<String, Map<Long, List<Plot>>> chunkIndex = new HashMap<>();
    private final Map<String, Map<Long, Plot>> signIndex = new HashMap<>();

    // Runtime-only state. Bypass is intentionally not saved, so it resets to off after a restart.
    private final Set<UUID> bypass = new HashSet<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, String> pendingSignLinks = new HashMap<>();

    public record Selection(@Nullable String dimension, @Nullable BlockPos pos1, @Nullable BlockPos pos2) {}

    private PlotManager(MinecraftServer server, Path file) {
        this.server = server;
        this.file = file;
    }

    /** Returns the active manager, or null when no server is running. */
    public static @Nullable PlotManager get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        Path file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve(FILE_NAME).normalize();
        PlotManager manager = new PlotManager(server, file);
        try {
            PlotStore.load(file, manager.plots, manager.players);
        } catch (IOException e) {
            // Refuse to start rather than run with every plot unprotected and later overwrite the file.
            throw new IllegalStateException("[SafePlots] Could not read " + file
                    + ". Fix or restore the file (the server was stopped to protect your plots).", e);
        }
        manager.rebuildIndexes();
        instance = manager;
        LOGGER.info("[SafePlots] Loaded {} plots from {}", manager.plots.size(), file);
    }

    public static void stop() {
        if (instance != null) {
            instance.save();
        }
        instance = null;
    }

    public MinecraftServer server() {
        return server;
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            PlotStore.save(file, plots, players);
        } catch (IOException e) {
            LOGGER.error("[SafePlots] Failed to save {}", file, e);
        }
    }

    // ---------------------------------------------------------------- lookups

    public static String dimensionId(Level level) {
        return level.dimension().identifier().toString();
    }

    public @Nullable ServerLevel level(String dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
    }

    public @Nullable Plot plot(String name) {
        return plots.get(name);
    }

    public Collection<Plot> plots() {
        return plots.values();
    }

    public @Nullable Plot plotAt(Level level, BlockPos pos) {
        Map<Long, List<Plot>> byChunk = chunkIndex.get(dimensionId(level));
        if (byChunk == null) {
            return null;
        }
        List<Plot> candidates = byChunk.get(chunkKey(pos.getX() >> 4, pos.getZ() >> 4));
        if (candidates != null) {
            for (Plot plot : candidates) {
                if (plot.contains(pos)) {
                    return plot;
                }
            }
        }
        return null;
    }

    public boolean intersectsAnyPlot(Level level, AABB box) {
        Map<Long, List<Plot>> byChunk = chunkIndex.get(dimensionId(level));
        if (byChunk == null) {
            return false;
        }
        int minCx = (int) Math.floor(box.minX) >> 4, maxCx = (int) Math.floor(box.maxX) >> 4;
        int minCz = (int) Math.floor(box.minZ) >> 4, maxCz = (int) Math.floor(box.maxZ) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                List<Plot> candidates = byChunk.get(chunkKey(cx, cz));
                if (candidates != null) {
                    for (Plot plot : candidates) {
                        if (plot.intersects(box)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public @Nullable Plot plotForSign(Level level, BlockPos pos) {
        Map<Long, Plot> signs = signIndex.get(dimensionId(level));
        return signs == null ? null : signs.get(pos.asLong());
    }

    /**
     * True when something at {@code from} may affect {@code to}: the target is outside every plot,
     * both positions are in the same plot, or both plots have the same owner.
     * Used for liquids, pistons, hoppers and dispensers crossing plot borders.
     */
    public boolean mayAffect(Level level, BlockPos from, BlockPos to) {
        Plot target = plotAt(level, to);
        if (target == null) {
            return true;
        }
        Plot source = plotAt(level, from);
        if (source == target) {
            return true;
        }
        return source != null && source.owner() != null && source.owner().equals(target.owner());
    }

    public List<Plot> plotsOwnedBy(UUID player) {
        List<Plot> result = new ArrayList<>();
        for (Plot plot : plots.values()) {
            if (player.equals(plot.owner())) {
                result.add(plot);
            }
        }
        return result;
    }

    public int maxClaims(UUID player) {
        PlayerData data = players.get(player);
        return data == null ? PlayerData.DEFAULT_MAX_CLAIMS : data.maxClaims;
    }

    /** Best known current name for a UUID. Works for offline players who have joined before. */
    public String nameOf(UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().name();
        }
        PlayerData data = players.get(player);
        if (data != null && data.name != null) {
            return data.name;
        }
        return server.services().nameToIdCache().get(player).map(NameAndId::name).orElse(player.toString());
    }

    // ---------------------------------------------------------------- permissions

    public static boolean isAdmin(Player player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public boolean hasBypass(Player player) {
        return bypass.contains(player.getUUID()) && isAdmin(player);
    }

    /** Toggles bypass and returns the new state. */
    public boolean toggleBypass(UUID player) {
        if (bypass.remove(player)) {
            return false;
        }
        bypass.add(player);
        return true;
    }

    /** The core rule: owner, trusted, or admin with bypass may modify; everyone may modify outside plots. */
    public boolean canModify(Player player, Level level, BlockPos pos) {
        Plot plot = plotAt(level, pos);
        return plot == null || plot.isMember(player.getUUID()) || hasBypass(player);
    }

    // ---------------------------------------------------------------- selections and sign linking

    public Selection selection(UUID player) {
        return selections.getOrDefault(player, new Selection(null, null, null));
    }

    public void setCorner(UUID player, int corner, String dimension, BlockPos pos) {
        Selection old = selection(player);
        // Starting a selection in another dimension discards the other corner.
        boolean sameDim = dimension.equals(old.dimension());
        BlockPos pos1 = corner == 1 ? pos : (sameDim ? old.pos1() : null);
        BlockPos pos2 = corner == 2 ? pos : (sameDim ? old.pos2() : null);
        selections.put(player, new Selection(dimension, pos1, pos2));
    }

    public void setPendingSignLink(UUID admin, String plotName) {
        pendingSignLinks.put(admin, plotName);
    }

    public @Nullable String takePendingSignLink(UUID admin) {
        return pendingSignLinks.remove(admin);
    }

    public boolean hasPendingSignLink(UUID admin) {
        return pendingSignLinks.containsKey(admin);
    }

    // ---------------------------------------------------------------- mutations (each one saves)

    /** Returns the name of an overlapping plot, or null if the new plot was created. */
    public @Nullable String create(Plot plot) {
        for (Plot other : plots.values()) {
            if (other.overlaps(plot)) {
                return other.name();
            }
        }
        plots.put(plot.name(), plot);
        changed();
        return null;
    }

    /** Moves or resizes a plot, keeping owner, trust and sign. Returns an overlapping plot's name, or null on success. */
    public @Nullable String resize(Plot plot, BlockPos a, BlockPos b) {
        Plot candidate = new Plot(plot.name(), plot.dimension(), a, b);
        for (Plot other : plots.values()) {
            if (other != plot && other.overlaps(candidate)) {
                return other.name();
            }
        }
        plot.setBounds(a, b);
        changed();
        return null;
    }

    public void delete(Plot plot) {
        ClaimSigns.clear(this, plot.dimension(), plot.sign());
        plots.remove(plot.name());
        changed();
    }

    public void setOwner(Plot plot, @Nullable UUID owner) {
        plot.setOwner(owner);
        changed();
        ClaimSigns.refresh(this, plot);
    }

    /** Links a sign, unlinking it from any other plot and blanking this plot's previous sign. */
    public void linkSign(Plot plot, BlockPos pos) {
        for (Plot other : plots.values()) {
            if (other != plot && other.dimension().equals(plot.dimension()) && pos.equals(other.sign())) {
                other.setSign(null);
            }
        }
        if (plot.sign() != null && !plot.sign().equals(pos)) {
            ClaimSigns.clear(this, plot.dimension(), plot.sign());
        }
        plot.setSign(pos.immutable());
        changed();
        ClaimSigns.refresh(this, plot);
    }

    public void setTrusted(Plot plot, UUID player, boolean trusted) {
        if (trusted) {
            plot.trusted().add(player);
        } else {
            plot.trusted().remove(player);
        }
        changed();
    }

    public void setMaxClaims(UUID player, int maxClaims) {
        players.computeIfAbsent(player, id -> new PlayerData()).maxClaims = Math.max(0, maxClaims);
        changed();
    }

    public void rememberName(NameAndId player) {
        PlayerData data = players.computeIfAbsent(player.id(), id -> new PlayerData());
        if (!player.name().equals(data.name)) {
            data.name = player.name();
            save();
        }
    }

    private void changed() {
        rebuildIndexes();
        save();
    }

    private void rebuildIndexes() {
        chunkIndex.clear();
        signIndex.clear();
        for (Plot plot : plots.values()) {
            Map<Long, List<Plot>> byChunk = chunkIndex.computeIfAbsent(plot.dimension(), d -> new HashMap<>());
            for (int cx = plot.minChunkX(); cx <= plot.maxChunkX(); cx++) {
                for (int cz = plot.minChunkZ(); cz <= plot.maxChunkZ(); cz++) {
                    byChunk.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>(1)).add(plot);
                }
            }
            if (plot.sign() != null) {
                signIndex.computeIfAbsent(plot.dimension(), d -> new HashMap<>()).put(plot.sign().asLong(), plot);
            }
        }
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
