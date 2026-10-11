package dev.safemc.safeplots.world;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Turns a world save that an admin dropped into {@code <server>/imports/<folder>} into a /mv world. Only the
 * overworld of the save is used. Copying happens off the main thread so a big map doesn't freeze the server.
 * Chunks from older Minecraft versions are upgraded by the game when they are first loaded.
 */
public final class WorldImport {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Region-style folders that belong to one dimension. */
    private static final List<String> DIMENSION_FOLDERS = List.of("region", "entities", "poi");

    /** World names being copied right now, so nobody creates or imports the same name meanwhile. */
    private static final Set<String> IN_PROGRESS = new HashSet<>();

    /** What a save folder contains, read before copying. */
    public record Source(Path overworld, boolean newLayout, OptionalLong seed, Worlds.@Nullable Spawn spawn, int dataVersion) {}

    public interface Reply {
        void send(String message, boolean success);
    }

    private WorldImport() {}

    public static Path importsDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("imports").toAbsolutePath().normalize();
    }

    public static boolean inProgress(String name) {
        return IN_PROGRESS.contains(name);
    }

    /** Folders inside imports/, for suggestions. */
    public static List<String> available(MinecraftServer server) {
        Path dir = importsDir(server);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The save folder for {@code folder}, or null if it escapes imports/ or doesn't exist. */
    public static @Nullable Path resolve(MinecraftServer server, String folder) {
        Path dir = importsDir(server);
        Path path = dir.resolve(folder).toAbsolutePath().normalize();
        return path.startsWith(dir) && !path.equals(dir) && Files.isDirectory(path) ? path : null;
    }

    /**
     * Finds the overworld chunks in a save. Supports the 26.x layout ({@code dimensions/minecraft/overworld/region})
     * and the older one ({@code region/} at the top). Returns null if there are no region files.
     */
    public static @Nullable Source inspect(Path save) {
        Path newOverworld = save.resolve("dimensions").resolve("minecraft").resolve("overworld");
        boolean newLayout = Files.isDirectory(newOverworld.resolve("region"));
        Path overworld = newLayout ? newOverworld : save;
        if (!Files.isDirectory(overworld.resolve("region"))) {
            return null;
        }
        CompoundTag level = read(save.resolve("level.dat"));
        CompoundTag data = level == null ? null : level.getCompound("Data").orElse(null);
        OptionalLong seed = findSeed(read(save.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat")));
        if (seed.isEmpty()) {
            seed = findSeed(data);
        }
        int dataVersion = data == null ? 0 : data.getIntOr("DataVersion", 0);
        return new Source(overworld, newLayout, seed, data == null ? null : findSpawn(data), dataVersion);
    }

    /** True if the save was made by a newer Minecraft than this server (it can't be loaded). */
    public static boolean tooNew(Source source) {
        return source.dataVersion() > SharedConstants.getCurrentVersion().dataVersion().version();
    }

    /**
     * Copies the chunks into the new world's folder on a background thread, then creates the world on the
     * server thread. {@code reply} is always called on the server thread.
     */
    public static void start(MinecraftServer server, Worlds worlds, String name, Worlds.Type type, long seed, Source source, Reply reply) {
        Path target = worlds.folder(name);
        IN_PROGRESS.add(name);
        Thread thread = new Thread(() -> {
            String error = null;
            try {
                if (Files.exists(target)) {
                    throw new IOException("the folder " + target + " already exists");
                }
                for (String folder : DIMENSION_FOLDERS) {
                    copyTree(source.overworld().resolve(folder), target.resolve(folder));
                }
                if (source.newLayout()) {
                    // Per-dimension saved data (world border, raids, ...). The old layout mixes it with global data, so skip it there.
                    copyTree(source.overworld().resolve("data"), target.resolve("data"));
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.error("[SafePlots] Import of {} failed", name, e);
                error = e.getMessage();
                deleteTree(target);
            }
            String failure = error;
            server.execute(() -> {
                IN_PROGRESS.remove(name);
                if (failure != null) {
                    reply.send("Import failed: " + failure, false);
                    return;
                }
                try {
                    worlds.create(name, type, seed, source.spawn());
                } catch (RuntimeException e) {
                    LOGGER.error("[SafePlots] Could not load imported world {}", name, e);
                    reply.send("The files were copied but the world could not be loaded: " + e.getMessage(), false);
                    return;
                }
                reply.send("✓ Imported " + name + ". Go there with /mvtp " + name + ".", true);
            });
        }, "SafePlots world import " + name);
        thread.setDaemon(true);
        thread.start();
    }

    // ---------------------------------------------------------------- reading the save

    private static @Nullable CompoundTag read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[SafePlots] Could not read {}: {}", file, e.toString());
            return null;
        }
    }

    /** The first long called "seed" (26.x and 1.16+) or "RandomSeed" (older), anywhere in the tag. */
    private static OptionalLong findSeed(@Nullable CompoundTag tag) {
        if (tag == null) {
            return OptionalLong.empty();
        }
        for (String key : tag.keySet()) {
            Tag value = tag.get(key);
            if (value instanceof LongTag(long v) && (key.equals("seed") || key.equals("RandomSeed"))) {
                return OptionalLong.of(v);
            }
            if (value instanceof CompoundTag child) {
                OptionalLong found = findSeed(child);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return OptionalLong.empty();
    }

    /** The save's spawn point: {@code spawn: {pos: [x, y, z], yaw, pitch}} (new) or SpawnX/Y/Z (old). */
    private static Worlds.@Nullable Spawn findSpawn(CompoundTag data) {
        Optional<CompoundTag> spawn = data.getCompound("spawn");
        if (spawn.isPresent()) {
            int[] pos = spawn.get().getIntArray("pos").orElse(null);
            if (pos != null && pos.length == 3) {
                return new Worlds.Spawn(pos[0] + 0.5, pos[1], pos[2] + 0.5,
                        spawn.get().getFloat("yaw").orElse(0f), spawn.get().getFloat("pitch").orElse(0f));
            }
        }
        Optional<Integer> x = data.getInt("SpawnX"), y = data.getInt("SpawnY"), z = data.getInt("SpawnZ");
        if (x.isPresent() && y.isPresent() && z.isPresent()) {
            return new Worlds.Spawn(x.get() + 0.5, y.get(), z.get() + 0.5, data.getFloat("SpawnAngle").orElse(0f), 0);
        }
        return null;
    }

    // ---------------------------------------------------------------- files

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            LOGGER.warn("[SafePlots] Could not clean up {}: {}", dir, e.toString());
        }
    }
}
