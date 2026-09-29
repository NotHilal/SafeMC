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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** One home per player, saved to {@code <world>/safeplots-homes.json}. */
public final class HomeManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public record Home(String dimension, double x, double y, double z, float yRot, float xRot) {}

    private static @Nullable HomeManager instance;

    private final Path file;
    private final Map<UUID, Home> homes = new HashMap<>();

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

    public @Nullable Home home(UUID player) {
        return homes.get(player);
    }

    /** Sets (or replaces) the player's home. Returns true if it replaced an existing one. */
    public boolean setHome(UUID player, Home home) {
        boolean replaced = homes.put(player, home) != null;
        save();
        return replaced;
    }

    public boolean deleteHome(UUID player) {
        if (homes.remove(player) == null) {
            return false;
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
            JsonObject h = null;
            if (o.has("home")) {
                h = o.getAsJsonObject("home");
            } else if (o.has("homes")) {
                // Older versions allowed several named homes: keep "home", or else the first one.
                JsonObject named = o.getAsJsonObject("homes");
                h = named.has("home") ? named.getAsJsonObject("home")
                        : named.entrySet().stream().findFirst().map(x -> x.getValue().getAsJsonObject()).orElse(null);
            }
            if (h != null) {
                homes.put(UUID.fromString(e.getKey()), new Home(h.get("dimension").getAsString(), h.get("x").getAsDouble(),
                        h.get("y").getAsDouble(), h.get("z").getAsDouble(), h.get("yRot").getAsFloat(), h.get("xRot").getAsFloat()));
            }
        }
    }

    private void save() {
        JsonObject players = new JsonObject();
        homes.forEach((id, h) -> {
            JsonObject ho = new JsonObject();
            ho.addProperty("dimension", h.dimension());
            ho.addProperty("x", h.x());
            ho.addProperty("y", h.y());
            ho.addProperty("z", h.z());
            ho.addProperty("yRot", h.yRot());
            ho.addProperty("xRot", h.xRot());
            JsonObject o = new JsonObject();
            o.add("home", ho);
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
