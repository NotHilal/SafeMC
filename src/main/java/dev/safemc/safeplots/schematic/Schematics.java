package dev.safemc.safeplots.schematic;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Schematic clipboards, pasting and undo. Files live in {@code <server>/schematics/}. Big pastes are spread over
 * several ticks (a time budget per tick) so the server keeps running smoothly. Blocks are placed without physics:
 * no neighbor updates, sand doesn't fall, water doesn't flow and torches don't pop off while the build appears.
 * Clipboards and undo history are kept in memory and reset when the server restarts.
 */
public final class Schematics {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final long MAX_VOLUME = 32_000_000;
    public static final int MAX_UNDO = 5;
    /**
     * Undo memory per player, in changed blocks (8 bytes each, so 25M is about 200 MB). Older pastes are forgotten
     * first; the newest paste can always be undone.
     */
    private static final long MAX_UNDO_BLOCKS = 25_000_000;
    /** Ticks between progress messages for long pastes. */
    private static final int PROGRESS_TICKS = 100;
    /** Milliseconds of each tick spent placing blocks. */
    private static final long BUDGET_NANOS = 25_000_000L;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS | Block.UPDATE_SKIP_ON_PLACE;

    /** A loaded schematic and how it's turned. */
    public record Clipboard(Schematic schematic, Rotation rotation) {}

    /**
     * Everything a paste changed, in order, so it can be put back. Positions aren't stored: each entry keeps the
     * schematic cell it came from, and the position is worked out again from the paste's origin and rotation.
     * That's 8 bytes per changed block (cell index + a shared block state reference).
     */
    private static final class Undo {
        final ServerLevel level;
        final Schematic schematic;
        final Rotation rotation;
        final BlockPos at;
        int[] cells = new int[1024];
        BlockState[] states = new BlockState[1024];
        final Map<Integer, CompoundTag> blockEntities = new HashMap<>();
        int size;

        Undo(ServerLevel level, Schematic schematic, Rotation rotation, BlockPos at) {
            this.level = level;
            this.schematic = schematic;
            this.rotation = rotation;
            this.at = at;
        }

        void add(int cell, BlockState state, @Nullable CompoundTag blockEntity) {
            if (size == cells.length) {
                int grown = (int) Math.min(Integer.MAX_VALUE - 8L, size * 2L);
                cells = Arrays.copyOf(cells, grown);
                states = Arrays.copyOf(states, grown);
            }
            if (blockEntity != null) {
                blockEntities.put(size, blockEntity);
            }
            cells[size] = cell;
            states[size++] = state;
        }

        void position(int cell, BlockPos.MutableBlockPos out) {
            placed(schematic, rotation, at, cell, out);
        }
    }

    /** Where cell {@code i} of a schematic lands when pasted at {@code at} with {@code rotation}. */
    private static void placed(Schematic s, Rotation rotation, BlockPos at, int i, BlockPos.MutableBlockPos out) {
        BlockPos rel = s.cell(i).offset(s.offset()).rotate(rotation);
        out.set(at.getX() + rel.getX(), at.getY() + rel.getY(), at.getZ() + rel.getZ());
    }

    /** Work done a bit per tick. Returns true when finished. */
    private interface Job {
        UUID owner();

        boolean step(long deadline);

        /** The level went away (world deleted) or the server is stopping. */
        ServerLevel level();

        /** "Pasting castle: 42%", for the action bar while it runs. */
        String progress();
    }

    private static @Nullable Schematics instance;

    private final MinecraftServer server;
    private final Path dir;
    private final Map<UUID, Clipboard> clipboards = new HashMap<>();
    private final Map<UUID, Deque<Undo>> history = new HashMap<>();
    private final List<Job> jobs = new ArrayList<>();

    private Schematics(MinecraftServer server, Path dir) {
        this.server = server;
        this.dir = dir;
    }

    public static @Nullable Schematics get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        Schematics manager = new Schematics(server, server.getServerDirectory().resolve("schematics").toAbsolutePath().normalize());
        try {
            Files.createDirectories(manager.dir);
        } catch (IOException e) {
            LOGGER.warn("[SafePlots] Could not create {}: {}", manager.dir, e.toString());
        }
        instance = manager;
    }

    public static void stop() {
        instance = null;
    }

    public Path dir() {
        return dir;
    }

    // ---------------------------------------------------------------- files

    /** Schematic names (path inside schematics/, without extension), sorted. */
    public List<String> available() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir, 4)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .filter(n -> n.endsWith(".schem") || n.endsWith(".schematic"))
                    .map(n -> n.substring(0, n.lastIndexOf('.')))
                    .sorted().distinct().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The file for a name ({@code .schem} first, then {@code .schematic}), or null. Never outside schematics/. */
    public @Nullable Path resolve(String name) {
        for (String candidate : new String[] {name, name + ".schem", name + ".schematic"}) {
            Path path = dir.resolve(candidate).toAbsolutePath().normalize();
            if (path.startsWith(dir) && Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- clipboard

    public @Nullable Clipboard clipboard(UUID player) {
        return clipboards.get(player);
    }

    public void setClipboard(UUID player, Schematic schematic) {
        clipboards.put(player, new Clipboard(schematic, Rotation.NONE));
    }

    /** Turns the clipboard further by {@code rotation}. */
    public @Nullable Clipboard rotate(UUID player, Rotation rotation) {
        Clipboard clip = clipboards.get(player);
        if (clip == null) {
            return null;
        }
        Clipboard turned = new Clipboard(clip.schematic(), clip.rotation().getRotated(rotation));
        clipboards.put(player, turned);
        return turned;
    }

    public boolean busy(UUID player) {
        return jobs.stream().anyMatch(j -> j.owner().equals(player));
    }

    public int undoCount(UUID player) {
        Deque<Undo> undos = history.get(player);
        return undos == null ? 0 : undos.size();
    }

    // ---------------------------------------------------------------- paste

    /** Corners (min, max) the clipboard would cover if pasted at {@code at}. */
    public static BlockPos[] bounds(Clipboard clip, BlockPos at) {
        Schematic s = clip.schematic();
        BlockPos a = at.offset(s.offset().rotate(clip.rotation()));
        BlockPos b = at.offset(s.offset().offset(s.width() - 1, s.height() - 1, s.length() - 1).rotate(clip.rotation()));
        return new BlockPos[] {BlockPos.min(a, b), BlockPos.max(a, b)};
    }

    /** Queues a paste. {@code done} gets the number of changed blocks when it finishes. */
    public void paste(ServerPlayer player, Clipboard clip, BlockPos at, boolean skipAir, Consumer<Integer> done) {
        Schematic s = clip.schematic();
        Rotation rotation = clip.rotation();
        BlockState[] palette = new BlockState[s.palette().length];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = s.palette()[i].rotate(rotation);
        }
        ServerLevel level = player.level();
        UUID owner = player.getUUID();
        Undo undo = new Undo(level, s, rotation, at.immutable());
        jobs.add(new Job() {
            int next;

            @Override
            public UUID owner() {
                return owner;
            }

            @Override
            public ServerLevel level() {
                return level;
            }

            @Override
            public String progress() {
                return "Pasting " + s.name() + ": " + (int) (100L * next / s.volume()) + "%";
            }

            @Override
            public boolean step(long deadline) {
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                while (next < s.volume()) {
                    if ((next & 127) == 0 && System.nanoTime() > deadline) {
                        return false;
                    }
                    int i = next++;
                    BlockState state = palette[s.blocks()[i]];
                    if (skipAir && state.isAir()) {
                        continue;
                    }
                    placed(s, rotation, at, i, pos);
                    if (level.isOutsideBuildHeight(pos)) {
                        continue;
                    }
                    CompoundTag newTag = s.blockEntities().get(i);
                    BlockState old = level.getBlockState(pos);
                    BlockEntity oldBe = level.getBlockEntity(pos);
                    if (old == state && oldBe == null && newTag == null) {
                        continue; // already right: nothing to place or undo
                    }
                    undo.add(i, old, oldBe == null ? null : oldBe.saveWithFullMetadata(level.registryAccess()));
                    level.setBlock(pos, state, FLAGS);
                    if (newTag != null) {
                        loadBlockEntity(level, pos, state, newTag);
                    }
                }
                remember(owner, undo);
                done.accept(undo.size);
                return true;
            }
        });
    }

    /** Queues undoing the player's last paste. Returns its name, or null if there is nothing to undo. */
    public @Nullable String undo(UUID player, Consumer<Integer> done) {
        Deque<Undo> undos = history.get(player);
        Undo undo = undos == null ? null : undos.pollLast();
        if (undo == null) {
            return null;
        }
        jobs.add(new Job() {
            int next = undo.size - 1; // newest change first

            @Override
            public UUID owner() {
                return player;
            }

            @Override
            public ServerLevel level() {
                return undo.level;
            }

            @Override
            public String progress() {
                return "Undoing " + undo.schematic.name() + ": " + (int) (100L * (undo.size - 1 - next) / Math.max(1, undo.size)) + "%";
            }

            @Override
            public boolean step(long deadline) {
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                while (next >= 0) {
                    if ((next & 127) == 0 && System.nanoTime() > deadline) {
                        return false;
                    }
                    int i = next--;
                    undo.position(undo.cells[i], pos);
                    BlockState state = undo.states[i];
                    undo.level.setBlock(pos, state, FLAGS);
                    CompoundTag tag = undo.blockEntities.get(i);
                    if (tag != null) {
                        loadBlockEntity(undo.level, pos, state, tag);
                    }
                }
                done.accept(undo.size);
                return true;
            }
        });
        return undo.schematic.name();
    }

    private void remember(UUID player, Undo undo) {
        if (undo.size == 0) {
            return;
        }
        Deque<Undo> undos = history.computeIfAbsent(player, id -> new ArrayDeque<>());
        undos.addLast(undo);
        long total = undos.stream().mapToLong(u -> u.size).sum();
        // Forget the oldest pastes first; the newest one can always be undone.
        while (undos.size() > 1 && (undos.size() > MAX_UNDO || total > MAX_UNDO_BLOCKS)) {
            total -= undos.pollFirst().size;
        }
    }

    private static void loadBlockEntity(ServerLevel level, BlockPos pos, BlockState state, CompoundTag tag) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return;
        }
        CompoundTag data = tag.copy();
        data.putInt("x", pos.getX());
        data.putInt("y", pos.getY());
        data.putInt("z", pos.getZ());
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            be.loadWithComponents(TagValueInput.create(reporter.forChild(be.problemPath()), level.registryAccess(), data));
        } catch (RuntimeException e) {
            LOGGER.warn("[SafePlots] Could not load block entity data at {}: {}", pos, e.toString());
        }
        be.setChanged();
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
    }

    // ---------------------------------------------------------------- ticking

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Schematics manager = instance;
        if (manager == null || manager.jobs.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + BUDGET_NANOS;
        Iterator<Job> it = manager.jobs.iterator();
        while (it.hasNext() && System.nanoTime() < deadline) {
            Job job = it.next();
            if (manager.server.getLevel(job.level().dimension()) != job.level()) {
                it.remove(); // its world was deleted
                continue;
            }
            if (job.step(deadline)) {
                it.remove();
            } else {
                if (manager.server.getTickCount() % PROGRESS_TICKS == 0) {
                    ServerPlayer owner = manager.server.getPlayerList().getPlayer(job.owner());
                    if (owner != null) {
                        owner.sendSystemMessage(Component.literal(job.progress()).withStyle(ChatFormatting.YELLOW), true);
                    }
                }
                break; // out of time; continue next tick
            }
        }
    }

    /** Runs every queued job to the end right now (used by the self-test). */
    public void finishAll() {
        while (!jobs.isEmpty()) {
            Job job = jobs.removeFirst();
            job.step(Long.MAX_VALUE);
        }
    }
}
