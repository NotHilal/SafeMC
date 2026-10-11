package dev.safemc.safeplots.teleport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Up to {@link #MAX_HOMES} named homes per player, saved to {@code <world>/safeplots-homes.json}. */
public final class HomeManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final int MAX_HOMES = 5;
    /** The name used when a player doesn't give one. */
    public static final String DEFAULT_NAME = "home";

    public enum SetResult { SET, MOVED, LIMIT }

    public record Home(String dimension, double x, double y, double z, float yRot, float xRot) {}

    private static @Nullable HomeManager instance;

    private final Path file;
    /** Per player, in the order they were set. Names are lower case. */
    private final Map<UUID, Map<String, Home>> homes = new HashMap<>();

    private HomeManager(Path file) {
        this.file = file;
    }

    public static @Nullable HomeManager get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        HomeManager manager = new HomeManager(server.getWorldPath(LevelResource.ROOT).resolve("safeplots-homes.json").normalize());
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

    /** Home names are 1-16 letters, digits, _ or -, and not case sensitive. Returns null if the name is not allowed. */
    public static @Nullable String normalize(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.matches("[a-z0-9_-]{1,16}") ? n : null;
    }

    /** The player's homes in the order they were set (read-only). */
    public Map<String, Home> homes(UUID player) {
        Map<String, Home> mine = homes.get(player);
        return mine == null ? Map.of() : Collections.unmodifiableMap(mine);
    }

    public @Nullable Home home(UUID player, String name) {
        return homes(player).get(name);
    }

    /** Sets or moves a home. Fails with LIMIT if it would be a new home beyond {@link #MAX_HOMES}. */
    public SetResult setHome(UUID player, String name, Home home) {
        Map<String, Home> mine = homes.computeIfAbsent(player, id -> new LinkedHashMap<>());
        boolean exists = mine.containsKey(name);
        if (!exists && mine.size() >= MAX_HOMES) {
            return SetResult.LIMIT;
        }
        mine.put(name, home);
        save();
        return exists ? SetResult.MOVED : SetResult.SET;
    }

    public boolean deleteHome(UUID player, String name) {
        Map<String, Home> mine = homes.get(player);
        if (mine == null || mine.remove(name) == null) {
            return false;
        }
        if (mine.isEmpty()) {
            homes.remove(player);
        }
        save();
        return true;
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Map<String, Home> mine = new LinkedHashMap<>();
            if (o.has("homes")) {
                for (Map.Entry<String, JsonElement> h : o.getAsJsonObject("homes").entrySet()) {
                    String name = normalize(h.getKey());
                    if (name != null && !mine.containsKey(name)) {
                        mine.put(name, readHome(h.getValue().getAsJsonObject()));
                    }
                }
            } else if (o.has("home")) {
                // The one-home version saved a single "home".
                mine.put(DEFAULT_NAME, readHome(o.getAsJsonObject("home")));
            }
            if (!mine.isEmpty()) {
                homes.put(UUID.fromString(e.getKey()), mine);
            }
        }
    }

    private static Home readHome(JsonObject h) {
        return new Home(h.get("dimension").getAsString(), h.get("x").getAsDouble(), h.get("y").getAsDouble(),
                h.get("z").getAsDouble(), h.get("yRot").getAsFloat(), h.get("xRot").getAsFloat());
    }

    private void save() {
        JsonObject players = new JsonObject();
        homes.forEach((id, mine) -> {
            JsonObject named = new JsonObject();
            mine.forEach((name, h) -> {
                JsonObject ho = new JsonObject();
                ho.addProperty("dimension", h.dimension());
                ho.addProperty("x", h.x());
                ho.addProperty("y", h.y());
                ho.addProperty("z", h.z());
                ho.addProperty("yRot", h.yRot());
                ho.addProperty("xRot", h.xRot());
                named.add(name, ho);
            });
            JsonObject o = new JsonObject();
            o.add("homes", named);
            players.add(id.toString(), o);
        });
        JsonObject root = new JsonObject();
        root.add("players", players);
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
