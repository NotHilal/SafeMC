package dev.safemc.safeplots.zone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.safemc.safeplots.plot.PlotManager;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Areas where hostile mobs never spawn on their own, saved to {@code <world>/safeplots-nomobspawn.json}.
 * A zone covers its X/Z area at every height. Mobs from spawn eggs, /summon, conversions etc. are still allowed,
 * and hostile mobs can still walk in from outside.
 */
public final class NoSpawnZones {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Spawns that happen by themselves. Everything else (eggs, /summon, breeding, conversions, ...) is left alone. */
    private static final Set<EntitySpawnReason> BLOCKED = Set.of(
            EntitySpawnReason.NATURAL, EntitySpawnReason.CHUNK_GENERATION, EntitySpawnReason.SPAWNER,
            EntitySpawnReason.TRIAL_SPAWNER, EntitySpawnReason.STRUCTURE, EntitySpawnReason.PATROL,
            EntitySpawnReason.REINFORCEMENT, EntitySpawnReason.JOCKEY);

    /** Inclusive block bounds. */
    public record Zone(String name, String dimension, int minX, int minZ, int maxX, int maxZ) {
        public boolean contains(String dim, double x, double z) {
            return dimension.equals(dim) && x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1;
        }

        public String describe() {
            return "(" + minX + ", " + minZ + ") to (" + maxX + ", " + maxZ + ") in " + dimension;
        }
    }

    private static @Nullable NoSpawnZones instance;

    private final Path file;
    private final Map<String, Zone> zones = new TreeMap<>();

    private NoSpawnZones(Path file) {
        this.file = file;
    }

    public static @Nullable NoSpawnZones get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        NoSpawnZones manager = new NoSpawnZones(server.getWorldPath(LevelResource.ROOT).resolve("safeplots-nomobspawn.json").normalize());
        try {
            manager.load();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("[SafePlots] Could not read " + manager.file + ". Fix or restore the file.", e);
        }
        instance = manager;
    }

    public static void stop() {
        instance = null;
    }

    public @Nullable Zone zone(String name) {
        return zones.get(name);
    }

    public Collection<Zone> zones() {
        return zones.values();
    }

    public @Nullable Zone zoneAt(Level level, double x, double z) {
        String dim = PlotManager.dimensionId(level);
        for (Zone zone : zones.values()) {
            if (zone.contains(dim, x, z)) {
                return zone;
            }
        }
        return null;
    }

    public void add(Zone zone) {
        zones.put(zone.name(), zone);
        save();
    }

    public boolean remove(String name) {
        if (zones.remove(name) == null) {
            return false;
        }
        save();
        return true;
    }

    // ---------------------------------------------------------------- spawning

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        NoSpawnZones manager = instance;
        if (manager == null || manager.zones.isEmpty() || !(event.getEntity() instanceof Enemy)
                || !BLOCKED.contains(event.getSpawnType())) {
            return;
        }
        if (manager.zoneAt(event.getLevel().getLevel(), event.getX(), event.getZ()) != null) {
            event.setSpawnCancelled(true);
        }
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
        JsonObject zonesJson = root.has("zones") ? root.getAsJsonObject("zones") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : zonesJson.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            zones.put(e.getKey(), new Zone(e.getKey(), o.get("dimension").getAsString(), o.get("minX").getAsInt(),
                    o.get("minZ").getAsInt(), o.get("maxX").getAsInt(), o.get("maxZ").getAsInt()));
        }
    }

    private void save() {
        JsonObject zonesJson = new JsonObject();
        zones.forEach((name, z) -> {
            JsonObject o = new JsonObject();
            o.addProperty("dimension", z.dimension());
            o.addProperty("minX", z.minX());
            o.addProperty("minZ", z.minZ());
            o.addProperty("maxX", z.maxX());
            o.addProperty("maxZ", z.maxZ());
            zonesJson.add(name, o);
        });
        JsonObject root = new JsonObject();
        root.add("zones", zonesJson);
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
