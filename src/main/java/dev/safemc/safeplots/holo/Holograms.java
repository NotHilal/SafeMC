package dev.safemc.safeplots.holo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.math.Transformation;
import dev.safemc.safeplots.plot.PlotManager;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Floating text (/holo), saved to {@code <world>/safeplots-holograms.json}. Each one is shown by a vanilla text display
 * entity, so players need nothing installed. The file is what counts: a display that doesn't match it (an old one
 * after a move or delete, or a copy) is removed as soon as it loads, and one that went missing (/kill) comes back.
 */
public final class Holograms {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** Marks our text displays, so other ones are left alone. */
    public static final String TAG = "safeplots.holo";
    private static final int BACKGROUND = Display.TextDisplay.INITIAL_BACKGROUND;

    public static final class Holo {
        public final String name;
        public String dimension;
        public double x, y, z;
        public final List<String> lines = new ArrayList<>();
        public float scale = 1.0F;
        public boolean background = false;
        /** The display currently showing it, or null until one is spawned. */
        public @Nullable UUID entity;

        Holo(String name) {
            this.name = name;
        }

        public BlockPos blockPos() {
            return BlockPos.containing(x, y, z);
        }
    }

    private static @Nullable Holograms instance;

    private final MinecraftServer server;
    private final Path file;
    private final Map<String, Holo> holos = new TreeMap<>();

    private Holograms(MinecraftServer server, Path file) {
        this.server = server;
        this.file = file;
    }

    public static @Nullable Holograms get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        Holograms manager = new Holograms(server, server.getWorldPath(LevelResource.ROOT).resolve("safeplots-holograms.json").normalize());
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

    public @Nullable Holo holo(String name) {
        return holos.get(name);
    }

    public Collection<Holo> holos() {
        return holos.values();
    }

    public Holo create(String name, ServerLevel level, double x, double y, double z, String text) {
        Holo holo = new Holo(name);
        holos.put(name, holo);
        holo.lines.addAll(splitLines(text));
        place(holo, level, x, y, z);
        return holo;
    }

    /** Moves it (also to another world): the old display goes, a new one appears at the new spot. */
    public void place(Holo holo, ServerLevel level, double x, double y, double z) {
        removeEntity(holo);
        holo.dimension = PlotManager.dimensionId(level);
        holo.x = x;
        holo.y = y;
        holo.z = z;
        holo.entity = null;
        ensure(holo);
        save();
    }

    /** After its lines, scale or background changed. */
    public void changed(Holo holo) {
        if (display(holo) instanceof Display.TextDisplay display) {
            apply(holo, display);
        }
        save();
    }

    public void delete(Holo holo) {
        removeEntity(holo);
        holos.remove(holo.name);
        save();
    }

    public @Nullable ServerLevel level(Holo holo) {
        if (holo.dimension == null) {
            return null; // still being created
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(holo.dimension)));
    }

    private @Nullable Entity display(Holo holo) {
        ServerLevel level = level(holo);
        return level == null || holo.entity == null ? null : level.getEntity(holo.entity);
    }

    private void removeEntity(Holo holo) {
        Entity old = display(holo);
        if (old != null) {
            old.discard();
        }
    }

    /** Spawns its display if the spot is loaded and none is there (new, moved, or killed). */
    private void ensure(Holo holo) {
        ServerLevel level = level(holo);
        if (level == null || !level.areEntitiesLoaded(ChunkPos.pack(holo.blockPos())) || display(holo) != null) {
            return;
        }
        Display.TextDisplay display = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
        display.setPos(holo.x, holo.y, holo.z);
        display.addTag(TAG);
        apply(holo, display);
        holo.entity = display.getUUID();
        level.addFreshEntity(display);
        save();
    }

    private static void apply(Holo holo, Display.TextDisplay display) {
        display.setText(text(holo.lines));
        display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        display.setBackgroundColor(holo.background ? BACKGROUND : 0);
        display.setFlags(Display.TextDisplay.FLAG_SHADOW);
        display.setLineWidth(1000);
        display.setTransformation(new Transformation(null, null, new Vector3f(holo.scale, holo.scale, holo.scale), null));
    }

    // ---------------------------------------------------------------- keeping displays in line with the file

    /** A display of ours that loads with a chunk but isn't the one in the file (moved, deleted, copied) is removed. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onJoin(EntityJoinLevelEvent event) {
        Holograms manager = instance;
        if (manager == null || !(event.getEntity() instanceof Display.TextDisplay display) || !display.entityTags().contains(TAG)) {
            return;
        }
        for (Holo holo : manager.holos.values()) {
            if (display.getUUID().equals(holo.entity) && holo.dimension.equals(PlotManager.dimensionId(event.getLevel()))) {
                apply(holo, display); // edited while its chunk wasn't loaded
                return;
            }
        }
        event.setCanceled(true);
    }

    /** Every 5 seconds: brings back displays that are missing where their chunk is loaded. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Holograms manager = instance;
        if (manager == null || event.getServer().getTickCount() % 100 != 0) {
            return;
        }
        manager.ensureAll();
    }

    public void ensureAll() {
        holos.values().forEach(this::ensure);
    }

    // ---------------------------------------------------------------- text

    /** Lines are separated by a typed {@code \n}. */
    public static List<String> splitLines(String text) {
        return List.of(text.split("\\\\n", -1));
    }

    public static Component text(List<String> lines) {
        MutableComponent all = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                all.append("\n");
            }
            all.append(colored(lines.get(i)));
        }
        return all;
    }

    /**
     * {@code &} color codes: {@code &0}-{@code &9} and {@code &a}-{@code &f} colors, {@code &l} bold, {@code &o} italic,
     * {@code &n} underlined, {@code &m} struck through, {@code &k} scrambled, {@code &r} reset, {@code &#RRGGBB} any
     * color. A color clears the bold/italic/... before it, like in chat. {@code &&} is a plain {@code &}.
     */
    public static Component colored(String line) {
        MutableComponent out = Component.empty();
        StringBuilder run = new StringBuilder();
        Style style = Style.EMPTY;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch != '&' || i + 1 >= line.length()) {
                run.append(ch);
                continue;
            }
            char code = line.charAt(i + 1);
            Style next = null;
            int skip = 1;
            if (code == '&') {
                run.append('&');
                i++;
                continue;
            } else if (code == '#' && i + 8 <= line.length() && line.substring(i + 2, i + 8).matches("[0-9A-Fa-f]{6}")) {
                next = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(line.substring(i + 2, i + 8), 16)));
                skip = 7;
            } else {
                ChatFormatting format = ChatFormatting.getByCode(Character.toLowerCase(code));
                if (format == ChatFormatting.RESET) {
                    next = Style.EMPTY;
                } else if (format != null && format.ordinal() <= ChatFormatting.WHITE.ordinal()) { // the 16 colors come first
                    next = Style.EMPTY.withColor(format);
                } else if (format != null) {
                    next = style.applyFormat(format);
                }
            }
            if (next == null) {
                run.append(ch);
                continue;
            }
            if (!run.isEmpty()) {
                out.append(Component.literal(run.toString()).withStyle(style));
                run.setLength(0);
            }
            style = next;
            i += skip;
        }
        if (!run.isEmpty()) {
            out.append(Component.literal(run.toString()).withStyle(style));
        }
        return out;
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
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Holo holo = new Holo(e.getKey());
            holo.dimension = o.get("dimension").getAsString();
            holo.x = o.get("x").getAsDouble();
            holo.y = o.get("y").getAsDouble();
            holo.z = o.get("z").getAsDouble();
            o.getAsJsonArray("lines").forEach(el -> holo.lines.add(el.getAsString()));
            holo.scale = o.has("scale") ? o.get("scale").getAsFloat() : 1.0F;
            holo.background = o.has("background") && o.get("background").getAsBoolean();
            holo.entity = o.has("entity") ? UUID.fromString(o.get("entity").getAsString()) : null;
            holos.put(holo.name, holo);
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        holos.forEach((name, holo) -> {
            JsonObject o = new JsonObject();
            o.addProperty("dimension", holo.dimension);
            o.addProperty("x", holo.x);
            o.addProperty("y", holo.y);
            o.addProperty("z", holo.z);
            JsonArray lines = new JsonArray();
            holo.lines.forEach(lines::add);
            o.add("lines", lines);
            o.addProperty("scale", holo.scale);
            o.addProperty("background", holo.background);
            if (holo.entity != null) {
                o.addProperty("entity", holo.entity.toString());
            }
            root.add(name, o);
        });
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
