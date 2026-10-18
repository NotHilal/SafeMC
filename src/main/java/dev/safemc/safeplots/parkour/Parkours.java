package dev.safemc.safeplots.parkour;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.safemc.safeplots.inventory.WorldInventories;
import dev.safemc.safeplots.plot.PlotManager;
import dev.safemc.safeplots.world.Worlds;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Parkour courses, saved to {@code <world>/safeplots-parkour.json}. Stepping on the start block starts the timer,
 * checkpoints must be reached in order, and the finish block ends the run. Falling below the course sends the
 * player back to their last checkpoint (or the start). Finishing gives a reward (diamonds) once
 * per cooldown (24 hours by default), put straight into the player's inventory of another world, e.g. survival.
 * Flying, creative/spectator mode, elytra and teleporting stop the run.
 */
public final class Parkours {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** Moving further than this between two checks is a teleport. */
    private static final double TELEPORT_DISTANCE = 12.0; // falling moves up to about 8 blocks in 2 ticks

    public static final class Course {
        public final String name;
        public String dimension;
        public BlockPos start;
        public @Nullable BlockPos finish;
        public final List<BlockPos> checkpoints = new ArrayList<>();
        /** The /mvtp name of the world whose inventory gets the reward, or null for no reward. */
        public @Nullable String rewardWorld;
        public int diamonds = 3;
        public int cooldownHours = 24;
        /** Feet at this height or lower means the player fell; null: two blocks below the course's lowest block. */
        public @Nullable Integer fallY;

        Course(String name, String dimension, BlockPos start) {
            this.name = name;
            this.dimension = dimension;
            this.start = start;
        }

        public int fallY() {
            if (fallY != null) {
                return fallY;
            }
            int min = start.getY();
            for (BlockPos p : checkpoints) {
                min = Math.min(min, p.getY());
            }
            if (finish != null) {
                min = Math.min(min, finish.getY());
            }
            return min - 2;
        }
    }

    private static final class Run {
        final Course course;
        long startedAt;
        int next; // index of the next checkpoint to reach
        Vec3 last;

        Run(Course course, long startedAt, Vec3 at) {
            this.course = course;
            this.startedAt = startedAt;
            this.last = at;
        }

        BlockPos respawn() {
            return next == 0 ? course.start : course.checkpoints.get(next - 1);
        }
    }

    private static @Nullable Parkours instance;

    private final MinecraftServer server;
    private final Path file;
    private final Map<String, Course> courses = new TreeMap<>();
    /** Per player, per course: when they last got the reward (epoch ms). */
    private final Map<UUID, Map<String, Long>> rewarded = new HashMap<>();
    private final Map<UUID, Run> runs = new HashMap<>();
    /** Left their run while standing on the start: it doesn't start again until they step off. */
    private final Set<UUID> leftOnStart = new HashSet<>();

    private Parkours(MinecraftServer server, Path file) {
        this.server = server;
        this.file = file;
    }

    public static @Nullable Parkours get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        Parkours manager = new Parkours(server, server.getWorldPath(LevelResource.ROOT).resolve("safeplots-parkour.json").normalize());
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

    public @Nullable Course course(String name) {
        return courses.get(name);
    }

    public Collection<Course> courses() {
        return courses.values();
    }

    public Course create(String name, String dimension, BlockPos start) {
        Course course = new Course(name, dimension, start);
        courses.put(name, course);
        save();
        return course;
    }

    public void delete(Course course) {
        courses.remove(course.name);
        runs.values().removeIf(r -> r.course == course);
        rewarded.values().forEach(m -> m.remove(course.name));
        save();
    }

    /** After changing a course: runs on it start over, since its checkpoints may have moved. */
    public void changed(Course course) {
        runs.values().removeIf(r -> r.course == course);
        save();
    }

    public void resetCooldown(UUID player, Course course) {
        Map<String, Long> times = rewarded.get(player);
        if (times != null && times.remove(course.name) != null) {
            save();
        }
    }

    /** Milliseconds until the player can get this course's reward again; 0 if they can now. */
    public long cooldownLeft(UUID player, Course course) {
        Long last = rewarded.getOrDefault(player, Map.of()).get(course.name);
        if (last == null) {
            return 0;
        }
        return Math.max(0, last + course.cooldownHours * 3_600_000L - System.currentTimeMillis());
    }

    public boolean running(ServerPlayer player) {
        return runs.containsKey(player.getUUID());
    }

    /** How many checkpoints the player's current run has reached; -1 without a run. */
    public int checkpointsReached(ServerPlayer player) {
        Run run = runs.get(player.getUUID());
        return run == null ? -1 : run.next;
    }

    /** Stops the run. Someone standing on the start is moved next to it, or it would start a new run right away. */
    public void leave(ServerPlayer player) {
        Run run = runs.remove(player.getUUID());
        if (run == null || !on(player.blockPosition(), run.course.start)) {
            return;
        }
        BlockPos spot = offStart(player.level(), run.course.start);
        if (spot != null) {
            player.teleportTo(player.level(), spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
            player.resetFallDistance();
        } else {
            leftOnStart.add(player.getUUID()); // nowhere to stand: no new run until they step off
        }
    }

    /** The nearest place to stand around the start (feet position), up to 3 blocks away, or null. */
    private static @Nullable BlockPos offStart(ServerLevel level, BlockPos start) {
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // only this ring
                    }
                    for (int dy : new int[] {0, 1, -1}) {
                        BlockPos feet = start.offset(dx, dy, dz);
                        if (!on(feet, start) && canStand(level, feet)) {
                            return feet;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean canStand(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getFluidState(feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    /** Sends a running player back to their last checkpoint (or the start). False if they aren't on a course. */
    public boolean backToCheckpoint(ServerPlayer player) {
        Run run = runs.get(player.getUUID());
        ServerLevel level = run == null ? null : level(run.course);
        if (run == null || level == null) {
            return false;
        }
        BlockPos pos = run.respawn();
        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
        player.resetFallDistance();
        run.last = player.position();
        return true;
    }

    public @Nullable ServerLevel level(Course course) {
        PlotManager plots = PlotManager.get();
        return plots == null ? null : plots.level(course.dimension);
    }

    // ---------------------------------------------------------------- following players

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Parkours manager = instance;
        if (manager == null || manager.courses.isEmpty() || event.getServer().getTickCount() % 2 != 0) {
            return;
        }
        boolean second = event.getServer().getTickCount() % 20 == 0;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            manager.check(player, second);
        }
    }

    public void check(ServerPlayer player, boolean showTime) {
        follow(player, showTime);
        LeaveItem.sync(player, running(player));
    }

    private void follow(ServerPlayer player, boolean showTime) {
        UUID id = player.getUUID();
        String dim = PlotManager.dimensionId(player.level());
        Run run = runs.get(id);
        if (run != null && (!run.course.dimension.equals(dim) || cheating(player, run))) {
            runs.remove(id);
            player.sendSystemMessage(Component.literal("Parkour stopped: " + (!run.course.dimension.equals(dim) ? "you left the world."
                    : "no flying, elytra, creative or teleporting during a run.")).withStyle(ChatFormatting.RED));
            run = null;
        }
        BlockPos feet = player.blockPosition();
        Course startedHere = null;
        for (Course course : courses.values()) {
            if (course.dimension.equals(dim) && course.finish != null && on(feet, course.start)) {
                startedHere = course;
            }
        }
        if (startedHere != null && leftOnStart.contains(id)) {
            return;
        }
        leftOnStart.remove(id);
        if (startedHere != null) {
            if (run != null && run.course == startedHere) {
                run.last = player.position(); // back on the start (fell, or walked back): same run, the time keeps going
            } else if (!player.isSpectator() && !player.isCreative() && !player.getAbilities().flying) {
                runs.put(id, new Run(startedHere, System.currentTimeMillis(), player.position()));
                player.sendSystemMessage(Component.literal("Started parkour!").withStyle(ChatFormatting.AQUA));
                player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.5F);
            }
            return;
        }
        if (run == null) {
            return;
        }
        run.last = player.position();
        Course course = run.course;
        if (feet.getY() <= course.fallY()) {
            backToCheckpoint(player); // the time keeps going
            return;
        }
        if (run.next < course.checkpoints.size() && on(feet, course.checkpoints.get(run.next))) {
            run.next++;
            player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.0F, 1.2F);
        }
        if (course.finish != null && on(feet, course.finish)) {
            if (run.next < course.checkpoints.size()) {
                if (showTime) {
                    player.sendSystemMessage(Component.literal("You missed checkpoint " + (run.next + 1) + ". Go back with /parkour checkpoint.")
                            .withStyle(ChatFormatting.RED), true);
                }
                return;
            }
            runs.remove(id);
            finish(player, course, System.currentTimeMillis() - run.startedAt);
        }
    }

    private static boolean cheating(ServerPlayer player, Run run) {
        return player.getAbilities().flying || player.isFallFlying() || player.isCreative() || player.isSpectator()
                || player.position().distanceTo(run.last) > TELEPORT_DISTANCE;
    }

    /** Standing on (or in, for a pressure plate) the block: the same X and Z, feet at its height or one above. */
    private static boolean on(BlockPos feet, BlockPos target) {
        return feet.getX() == target.getX() && feet.getZ() == target.getZ() && (feet.getY() == target.getY() || feet.getY() == target.getY() + 1);
    }

    private void finish(ServerPlayer player, Course course, long millis) {
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
        player.sendSystemMessage(Component.literal("Parkour finished in " + time(millis) + "!").withStyle(ChatFormatting.GREEN));
        if (course.rewardWorld == null || course.diamonds <= 0) {
            return;
        }
        long left = cooldownLeft(player.getUUID(), course);
        if (left > 0) {
            player.sendSystemMessage(Component.literal("Next reward in " + duration(left) + ".")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        Worlds worlds = Worlds.get();
        WorldInventories inventories = WorldInventories.get();
        ServerLevel rewardLevel = worlds == null ? null : worlds.levelByName(course.rewardWorld);
        if (inventories == null || rewardLevel == null) {
            player.sendSystemMessage(Component.literal("The reward world (" + course.rewardWorld + ") isn't loaded. Tell an admin.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (!inventories.give(player, worlds.inventoryGroup(rewardLevel), new ItemStack(Items.DIAMOND, course.diamonds))) {
            player.sendSystemMessage(Component.literal("Your " + course.rewardWorld + " inventory is full! Make room there and finish again.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        rewarded.computeIfAbsent(player.getUUID(), k -> new HashMap<>()).put(course.name, System.currentTimeMillis());
        save();
        player.sendSystemMessage(Component.literal("+" + course.diamonds + " diamonds added to your " + course.rewardWorld
                + " inventory!").withStyle(ChatFormatting.AQUA));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (instance != null) {
            instance.runs.remove(event.getEntity().getUUID());
            instance.leftOnStart.remove(event.getEntity().getUUID());
        }
    }

    static String time(long millis) {
        long s = millis / 1000;
        return (s >= 60 ? s / 60 + "m " : "") + (s % 60) + "." + (millis % 1000) / 100 + "s";
    }

    static String duration(long millis) {
        long minutes = (millis + 59_999) / 60_000;
        return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m";
    }

    // ---------------------------------------------------------------- storage

    private static JsonArray pos(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    private static BlockPos pos(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonObject list = root.has("courses") ? root.getAsJsonObject("courses") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : list.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Course c = new Course(e.getKey(), o.get("dimension").getAsString(), pos(o.get("start")));
            c.finish = o.has("finish") ? pos(o.get("finish")) : null;
            if (o.has("checkpoints")) {
                o.getAsJsonArray("checkpoints").forEach(p -> c.checkpoints.add(pos(p)));
            }
            c.rewardWorld = o.has("rewardWorld") ? o.get("rewardWorld").getAsString() : null;
            c.diamonds = o.has("diamonds") ? o.get("diamonds").getAsInt() : 3;
            c.cooldownHours = o.has("cooldownHours") ? o.get("cooldownHours").getAsInt() : 24;
            c.fallY = o.has("fallY") ? o.get("fallY").getAsInt() : null;
            courses.put(c.name, c);
        }
        JsonObject times = root.has("rewarded") ? root.getAsJsonObject("rewarded") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : times.entrySet()) {
            Map<String, Long> m = new HashMap<>();
            e.getValue().getAsJsonObject().entrySet().forEach(t -> m.put(t.getKey(), t.getValue().getAsLong()));
            rewarded.put(UUID.fromString(e.getKey()), m);
        }
    }

    private void save() {
        JsonObject list = new JsonObject();
        courses.forEach((name, c) -> {
            JsonObject o = new JsonObject();
            o.addProperty("dimension", c.dimension);
            o.add("start", pos(c.start));
            if (c.finish != null) {
                o.add("finish", pos(c.finish));
            }
            JsonArray cps = new JsonArray();
            c.checkpoints.forEach(p -> cps.add(pos(p)));
            o.add("checkpoints", cps);
            if (c.rewardWorld != null) {
                o.addProperty("rewardWorld", c.rewardWorld);
            }
            o.addProperty("diamonds", c.diamonds);
            o.addProperty("cooldownHours", c.cooldownHours);
            if (c.fallY != null) {
                o.addProperty("fallY", c.fallY);
            }
            list.add(name, o);
        });
        JsonObject times = new JsonObject();
        rewarded.forEach((id, m) -> {
            if (!m.isEmpty()) {
                JsonObject o = new JsonObject();
                m.forEach(o::addProperty);
                times.add(id.toString(), o);
            }
        });
        JsonObject root = new JsonObject();
        root.add("courses", list);
        root.add("rewarded", times);
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
