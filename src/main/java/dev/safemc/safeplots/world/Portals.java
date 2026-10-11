package dev.safemc.safeplots.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.safemc.safeplots.moderation.ModerationManager;
import dev.safemc.safeplots.plot.PlotManager;
import dev.safemc.safeplots.teleport.Teleports;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Portals between worlds, saved to {@code <world>/safeplots-portals.json}. A portal is a box that leads to a world
 * (its spawn, or an exact spot set with {@code /mv portal setdest}). It works two ways:
 * <ul>
 *   <li>A vanilla Nether or End portal inside the box keeps its swirl, sound and delay but leads to the portal's
 *       world instead (see {@code PortalBlockMixin}).</li>
 *   <li>Anywhere else in the box (any blocks, e.g. a decorative gate), walking in teleports you right away.</li>
 * </ul>
 * Arriving inside another portal doesn't send you on: you have to step out and back in.
 */
public final class Portals {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Inclusive block bounds. {@code dest == null}: the spawn of {@code target}. */
    public record Entry(String name, String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                        String target, Worlds.@Nullable Spawn dest) {
        public boolean contains(String dim, BlockPos pos) {
            return dimension.equals(dim)
                    && pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        public boolean overlaps(Entry o) {
            return dimension.equals(o.dimension) && minX <= o.maxX && maxX >= o.minX
                    && minY <= o.maxY && maxY >= o.minY && minZ <= o.maxZ && maxZ >= o.minZ;
        }

        public String describe() {
            return "(" + minX + ", " + minY + ", " + minZ + ") to (" + maxX + ", " + maxY + ", " + maxZ + ") in " + dimension;
        }
    }

    private static @Nullable Portals instance;

    /** The portal box each online player is standing in, so a portal only fires when you walk into it. */
    private static final Map<UUID, String> INSIDE = new HashMap<>();

    private final MinecraftServer server;
    private final Path file;
    private final Map<String, Entry> portals = new TreeMap<>();

    private Portals(MinecraftServer server, Path file) {
        this.server = server;
        this.file = file;
    }

    public static @Nullable Portals get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        Portals manager = new Portals(server, server.getWorldPath(LevelResource.ROOT).resolve("safeplots-portals.json").normalize());
        try {
            manager.load();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("[SafePlots] Could not read " + manager.file + ". Fix or restore the file.", e);
        }
        instance = manager;
    }

    public static void stop() {
        instance = null;
        INSIDE.clear();
    }

    public @Nullable Entry portal(String name) {
        return portals.get(name);
    }

    public Collection<Entry> portals() {
        return portals.values();
    }

    public @Nullable Entry portalAt(Level level, BlockPos pos) {
        String dim = PlotManager.dimensionId(level);
        for (Entry portal : portals.values()) {
            if (portal.contains(dim, pos)) {
                return portal;
            }
        }
        return null;
    }

    /** Returns the name of an overlapping portal, or null if it was added. */
    public @Nullable String add(Entry portal) {
        for (Entry other : portals.values()) {
            if (other.overlaps(portal)) {
                return other.name();
            }
        }
        portals.put(portal.name(), portal);
        save();
        return null;
    }

    public boolean remove(String name) {
        if (portals.remove(name) == null) {
            return false;
        }
        save();
        return true;
    }

    public void setDestination(Entry portal, String target, Worlds.@Nullable Spawn dest) {
        portals.put(portal.name(), new Entry(portal.name(), portal.dimension(), portal.minX(), portal.minY(), portal.minZ(),
                portal.maxX(), portal.maxY(), portal.maxZ(), target, dest));
        save();
    }

    /** Where a portal leads right now, or null if its world was deleted or isn't loaded. */
    public Teleports.@Nullable Destination destination(Entry portal) {
        Worlds worlds = Worlds.get();
        if (worlds == null) {
            return null;
        }
        if (portal.dest() == null) {
            return worlds.spawnDestination(portal.target());
        }
        var level = worlds.levelByName(portal.target());
        return level == null ? null : new Teleports.Destination(level, Worlds.pos(portal.dest()), portal.dest().yRot(), portal.dest().xRot());
    }

    // ---------------------------------------------------------------- vanilla portal blocks (called by PortalBlockMixin)

    /**
     * A Nether or End portal block at {@code pos} is about to send {@code entity} somewhere. Returns our destination
     * if the block is inside a portal box; null means "vanilla decides". {@code blocked} is set for jailed players.
     */
    public static @Nullable TeleportTransition vanillaPortalDestination(Level level, Entity entity, BlockPos pos, boolean[] blocked) {
        Portals manager = instance;
        Entry portal = manager == null ? null : manager.portalAt(level, pos);
        if (portal == null) {
            return null;
        }
        if (entity instanceof ServerPlayer player && jailed(player)) {
            blocked[0] = true;
            return null;
        }
        Teleports.Destination dest = manager.destination(portal);
        if (dest == null) {
            blocked[0] = true;
            if (entity instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.literal("This portal leads to " + portal.target() + ", which doesn't exist anymore.")
                        .withStyle(ChatFormatting.RED), true);
            }
            return null;
        }
        if (entity instanceof ServerPlayer player) {
            markArriving(player, dest);
        }
        return new TeleportTransition(dest.level(), dest.pos(), Vec3.ZERO, dest.yRot(), dest.xRot(),
                TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET));
    }

    // ---------------------------------------------------------------- walk-in portals

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Portals manager = instance;
        if (manager == null || manager.portals.isEmpty() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        BlockPos feet = player.blockPosition();
        Entry portal = manager.portalAt(player.level(), feet);
        String before = portal == null ? INSIDE.remove(player.getUUID()) : INSIDE.put(player.getUUID(), portal.name());
        if (portal == null || portal.name().equals(before) || player.isSpectator()) {
            return;
        }
        // Standing in real portal blocks: the vanilla portal (with our destination) handles it after its usual delay.
        if (player.level().getBlockState(feet).getBlock() instanceof Portal
                || player.level().getBlockState(feet.above()).getBlock() instanceof Portal) {
            return;
        }
        if (jailed(player)) {
            player.sendSystemMessage(Component.literal("You can't use portals while in jail.").withStyle(ChatFormatting.RED), true);
            return;
        }
        ModerationManager moderation = ModerationManager.get();
        if (moderation != null && moderation.isFrozen(player.getUUID())) {
            return;
        }
        Teleports.Destination dest = manager.destination(portal);
        if (dest == null) {
            player.sendSystemMessage(Component.literal("This portal leads to " + portal.target() + ", which doesn't exist anymore.")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        markArriving(player, dest);
        player.stopRiding();
        player.teleportTo(dest.level(), dest.pos().x, dest.pos().y, dest.pos().z, Set.of(), dest.yRot(), dest.xRot(), true);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        INSIDE.remove(event.getEntity().getUUID());
    }

    /** Arriving inside another portal box must not fire it until the player steps out and back in. */
    private static void markArriving(ServerPlayer player, Teleports.Destination dest) {
        Portals manager = instance;
        Entry there = manager == null ? null : manager.portalAt(dest.level(), BlockPos.containing(dest.pos()));
        if (there == null) {
            INSIDE.remove(player.getUUID());
        } else {
            INSIDE.put(player.getUUID(), there.name());
        }
    }

    private static boolean jailed(ServerPlayer player) {
        ModerationManager moderation = ModerationManager.get();
        return moderation != null && moderation.isJailed(player.getUUID());
    }

    // ---------------------------------------------------------------- storage

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonObject json = root.has("portals") ? root.getAsJsonObject("portals") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Worlds.Spawn dest = null;
            if (o.has("dest")) {
                JsonObject d = o.getAsJsonObject("dest");
                dest = new Worlds.Spawn(d.get("x").getAsDouble(), d.get("y").getAsDouble(), d.get("z").getAsDouble(),
                        d.get("yRot").getAsFloat(), d.get("xRot").getAsFloat());
            }
            portals.put(e.getKey(), new Entry(e.getKey(), o.get("dimension").getAsString(),
                    o.get("minX").getAsInt(), o.get("minY").getAsInt(), o.get("minZ").getAsInt(),
                    o.get("maxX").getAsInt(), o.get("maxY").getAsInt(), o.get("maxZ").getAsInt(),
                    o.get("target").getAsString(), dest));
        }
    }

    private void save() {
        JsonObject json = new JsonObject();
        portals.forEach((name, p) -> {
            JsonObject o = new JsonObject();
            o.addProperty("dimension", p.dimension());
            o.addProperty("minX", p.minX());
            o.addProperty("minY", p.minY());
            o.addProperty("minZ", p.minZ());
            o.addProperty("maxX", p.maxX());
            o.addProperty("maxY", p.maxY());
            o.addProperty("maxZ", p.maxZ());
            o.addProperty("target", p.target());
            if (p.dest() != null) {
                JsonObject d = new JsonObject();
                d.addProperty("x", p.dest().x());
                d.addProperty("y", p.dest().y());
                d.addProperty("z", p.dest().z());
                d.addProperty("yRot", p.dest().yRot());
                d.addProperty("xRot", p.dest().xRot());
                o.add("dest", d);
            }
            json.add(name, o);
        });
        JsonObject root = new JsonObject();
        root.add("portals", json);
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("[SafePlots] Failed to save {}", file, e);
        }
    }
}
