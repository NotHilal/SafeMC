package dev.safemc.safeplots.moderation;

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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Mutes and freezes, saved to {@code <world>/safeplots-moderation.json} so they survive relogs and restarts.
 * Vanish is runtime-only (it ends when the admin logs out). Temp bans use the vanilla ban list.
 */
public final class ModerationManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** {@code until} is epoch millis, or 0 for a permanent mute. */
    public record Mute(String name, long until, String reason) {
        public boolean expired(long now) {
            return until != 0 && now >= until;
        }
    }

    private static @Nullable ModerationManager instance;

    private final Path file;
    private final Map<UUID, Mute> mutes = new HashMap<>();
    private final Map<UUID, String> frozen = new HashMap<>();
    private final Set<UUID> vanished = new HashSet<>();

    private ModerationManager(Path file) {
        this.file = file;
    }

    public static @Nullable ModerationManager get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        ModerationManager manager = new ModerationManager(server.getWorldPath(LevelResource.ROOT).resolve("safeplots-moderation.json").normalize());
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

    // ---------------------------------------------------------------- mute

    /** The active mute, or null. Expired mutes are removed on the way. */
    public @Nullable Mute mute(UUID player) {
        Mute mute = mutes.get(player);
        if (mute != null && mute.expired(System.currentTimeMillis())) {
            mutes.remove(player);
            save();
            return null;
        }
        return mute;
    }

    public void setMute(UUID player, @Nullable Mute mute) {
        if (mute == null) {
            mutes.remove(player);
        } else {
            mutes.put(player, mute);
        }
        save();
    }

    // ---------------------------------------------------------------- freeze

    public boolean isFrozen(UUID player) {
        return frozen.containsKey(player);
    }

    public void setFrozen(UUID player, String name, boolean value) {
        if (value) {
            frozen.put(player, name);
        } else {
            frozen.remove(player);
        }
        save();
    }

    // ---------------------------------------------------------------- vanish

    public boolean isVanished(UUID player) {
        return vanished.contains(player);
    }

    public Set<UUID> vanished() {
        return vanished;
    }

    public void setVanished(UUID player, boolean value) {
        if (value) {
            vanished.add(player);
        } else {
            vanished.remove(player);
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
        JsonObject mutesJson = root.has("mutes") ? root.getAsJsonObject("mutes") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : mutesJson.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            mutes.put(UUID.fromString(e.getKey()), new Mute(o.get("name").getAsString(), o.get("until").getAsLong(),
                    o.has("reason") ? o.get("reason").getAsString() : ""));
        }
        JsonObject frozenJson = root.has("frozen") ? root.getAsJsonObject("frozen") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : frozenJson.entrySet()) {
            frozen.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        JsonObject mutesJson = new JsonObject();
        mutes.forEach((id, m) -> {
            JsonObject o = new JsonObject();
            o.addProperty("name", m.name());
            o.addProperty("until", m.until());
            o.addProperty("reason", m.reason());
            mutesJson.add(id.toString(), o);
        });
        root.add("mutes", mutesJson);
        JsonObject frozenJson = new JsonObject();
        frozen.forEach((id, name) -> frozenJson.addProperty(id.toString(), name));
        root.add("frozen", frozenJson);
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
