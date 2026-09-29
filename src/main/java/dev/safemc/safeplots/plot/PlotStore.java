package dev.safemc.safeplots.plot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * Reads and writes {@code <world>/safeplots.json}. Writes go to a temp file first and are then
 * moved over the real file, so a crash mid-save can never leave a half-written file behind.
 */
final class PlotStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

    private PlotStore() {}

    static void load(Path file, Map<String, Plot> plots, Map<UUID, PlayerData> players) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Malformed JSON in " + file + ": " + e.getMessage(), e);
        }

        try {
            JsonObject plotsJson = root.has("plots") ? root.getAsJsonObject("plots") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : plotsJson.entrySet()) {
                JsonObject o = entry.getValue().getAsJsonObject();
                Plot plot = new Plot(entry.getKey(), o.get("dimension").getAsString(), readPos(o, "min"), readPos(o, "max"));
                if (o.has("owner") && !o.get("owner").isJsonNull()) {
                    plot.setOwner(UUID.fromString(o.get("owner").getAsString()));
                    if (o.has("trusted")) {
                        for (JsonElement t : o.getAsJsonArray("trusted")) {
                            plot.trusted().add(UUID.fromString(t.getAsString()));
                        }
                    }
                }
                if (o.has("sign") && !o.get("sign").isJsonNull()) {
                    plot.setSign(readPos(o, "sign"));
                }
                plots.put(plot.name(), plot);
            }

            JsonObject playersJson = root.has("players") ? root.getAsJsonObject("players") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : playersJson.entrySet()) {
                JsonObject o = entry.getValue().getAsJsonObject();
                PlayerData data = new PlayerData();
                if (o.has("maxClaims")) {
                    data.maxClaims = Math.max(0, o.get("maxClaims").getAsInt());
                }
                if (o.has("name") && !o.get("name").isJsonNull()) {
                    data.name = o.get("name").getAsString();
                }
                players.put(UUID.fromString(entry.getKey()), data);
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid plot data in " + file + ": " + e.getMessage(), e);
        }
    }

    static void save(Path file, Map<String, Plot> plots, Map<UUID, PlayerData> players) throws IOException {
        JsonObject root = new JsonObject();

        JsonObject plotsJson = new JsonObject();
        for (Plot plot : plots.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("dimension", plot.dimension());
            o.add("min", writePos(plot.min()));
            o.add("max", writePos(plot.max()));
            o.addProperty("owner", plot.owner() == null ? null : plot.owner().toString());
            JsonArray trusted = new JsonArray();
            plot.trusted().forEach(id -> trusted.add(id.toString()));
            o.add("trusted", trusted);
            o.add("sign", plot.sign() == null ? null : writePos(plot.sign()));
            plotsJson.add(plot.name(), o);
        }
        root.add("plots", plotsJson);

        JsonObject playersJson = new JsonObject();
        for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", entry.getValue().name);
            o.addProperty("maxClaims", entry.getValue().maxClaims);
            playersJson.add(entry.getKey().toString(), o);
        }
        root.add("players", playersJson);

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static BlockPos readPos(JsonObject o, String key) {
        JsonArray a = o.getAsJsonArray(key);
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static JsonArray writePos(BlockPos pos) {
        JsonArray a = new JsonArray();
        a.add(pos.getX());
        a.add(pos.getY());
        a.add(pos.getZ());
        return a;
    }
}
