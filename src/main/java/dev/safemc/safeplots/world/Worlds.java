package dev.safemc.safeplots.world;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.safemc.safeplots.mixin.MinecraftServerAccessor;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Extra worlds created by admins while the server runs (like Multiverse), saved to {@code <world>/safeplots-worlds.json}.
 * Each world is a normal server dimension ({@code safeplots:<name>}) built from vanilla dimension types, so vanilla
 * clients can visit it without any mod. Worlds are re-created at every start, before players can join.
 */
public final class Worlds {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final String NAMESPACE = "safeplots";

    public enum Type {
        NORMAL, AMPLIFIED, LARGE_BIOMES, FLAT, VOID, NETHER, END;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static @Nullable Type parse(String id) {
            for (Type type : values()) {
                if (type.id().equals(id)) {
                    return type;
                }
            }
            return null;
        }
    }

    /** The inventory group of the main world, the Nether, the End and other mods' dimensions. */
    public static final String MAIN_GROUP = "main";

    /**
     * {@code spawn == null} until someone first goes there; then a safe spot near 0,0 is picked and saved.
     * {@code group == null} means the world has its own inventory group, named after the world.
     */
    public record World(String name, Type type, long seed, @Nullable Spawn spawn, @Nullable String group) {
        public ResourceKey<Level> key() {
            return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(NAMESPACE, name));
        }

        public String inventoryGroup() {
            return group != null ? group : name;
        }
    }

    public record Spawn(double x, double y, double z, float yRot, float xRot) {}

    private static @Nullable Worlds instance;

    private final MinecraftServer server;
    private final Path file;
    private final Map<String, World> worlds = new TreeMap<>();
    /** Folders that could not be removed right away (still open); deleted at the next start. */
    private final Set<String> pendingDeletes = new TreeSet<>();
    private final Map<ResourceKey<Level>, Spawn> vanillaSpawns = new HashMap<>();

    private Worlds(MinecraftServer server, Path file) {
        this.server = server;
        this.file = file;
    }

    public static @Nullable Worlds get() {
        return instance;
    }

    /** Called at ServerStarting: the vanilla levels exist, no player has joined yet. */
    public static void start(MinecraftServer server) {
        Worlds manager = new Worlds(server, server.getWorldPath(LevelResource.ROOT).resolve("safeplots-worlds.json").normalize());
        try {
            manager.load();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("[SafePlots] Could not read " + manager.file + ". Fix or restore the file.", e);
        }
        instance = manager;
        try {
            Files.createDirectories(WorldImport.importsDir(server)); // so admins see where to drop maps for /mv import
        } catch (IOException e) {
            LOGGER.warn("[SafePlots] Could not create {}: {}", WorldImport.importsDir(server), e.toString());
        }
        if (!manager.pendingDeletes.isEmpty()) {
            for (String name : List.copyOf(manager.pendingDeletes)) {
                if (!manager.worlds.containsKey(name) && manager.deleteFolder(name)) {
                    manager.pendingDeletes.remove(name);
                }
            }
            manager.save();
        }
        for (World world : manager.worlds.values()) {
            manager.open(world);
        }
    }

    public static void stop() {
        instance = null; // the server closes and saves every level itself, ours included
    }

    public @Nullable World world(String name) {
        return worlds.get(name);
    }

    public Collection<World> worlds() {
        return worlds.values();
    }

    public boolean isPendingDelete(String name) {
        return pendingDeletes.contains(name);
    }

    public @Nullable ServerLevel level(World world) {
        return server.getLevel(world.key());
    }

    /** Our world for a level, or null for vanilla / other mods' dimensions. */
    public @Nullable World worldOf(Level level) {
        Identifier id = level.dimension().identifier();
        return id.getNamespace().equals(NAMESPACE) ? worlds.get(id.getPath()) : null;
    }

    /** Which inventory a player uses in this level. Vanilla and other mods' dimensions all share {@link #MAIN_GROUP}. */
    public String inventoryGroup(Level level) {
        World world = worldOf(level);
        return world == null ? MAIN_GROUP : world.inventoryGroup();
    }

    /** The /mvtp name of a level ({@code world}, {@code nether}, {@code end} or one of ours), or null for other mods' dimensions. */
    public @Nullable String nameOf(Level level) {
        if (level.dimension() == Level.OVERWORLD) return "world";
        if (level.dimension() == Level.NETHER) return "nether";
        if (level.dimension() == Level.END) return "end";
        World world = worldOf(level);
        return world == null ? null : world.name();
    }

    /** The level for a /mvtp name, or null if there is no such world or it isn't loaded. */
    public @Nullable ServerLevel levelByName(String name) {
        return switch (name) {
            case "world" -> server.overworld();
            case "nether" -> server.getLevel(Level.NETHER);
            case "end" -> server.getLevel(Level.END);
            default -> {
                World world = worlds.get(name);
                yield world == null ? null : level(world);
            }
        };
    }

    /** The spawn of a world by its /mvtp name, or null if it doesn't exist (any more). */
    public Teleports.@Nullable Destination spawnDestination(String name) {
        ServerLevel level = levelByName(name);
        if (level == null) {
            return null;
        }
        if (level == server.overworld()) {
            LevelData.RespawnData spawn = server.getRespawnData();
            BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.pos());
            return new Teleports.Destination(level, Vec3.atBottomCenterOf(pos), spawn.yaw(), spawn.pitch());
        }
        World world = worldOf(level);
        Spawn spawn = world != null ? spawn(world, level) : vanillaSpawn(level);
        return new Teleports.Destination(level, pos(spawn), spawn.yRot(), spawn.xRot());
    }

    /** Moves a world to another inventory group ({@code null}: its own). */
    public void setGroup(World world, @Nullable String group) {
        worlds.put(world.name(), new World(world.name(), world.type(), world.seed(), world.spawn(),
                group == null || group.equals(world.name()) ? null : group));
        save();
    }

    // ---------------------------------------------------------------- create / delete

    public ServerLevel create(String name, Type type, long seed) {
        return create(name, type, seed, null);
    }

    /** {@code spawn} may be null: a safe one is picked on the first visit. */
    public ServerLevel create(String name, Type type, long seed, @Nullable Spawn spawn) {
        World world = new World(name, type, seed, spawn, null);
        ServerLevel level = open(world);
        worlds.put(name, world);
        save();
        return level;
    }

    /**
     * Unloads the world and deletes its folder. Players inside are sent to the main spawn first.
     * Returns false if the files must wait for a restart to be removed.
     */
    public boolean delete(World world) {
        ServerLevel level = level(world);
        if (level != null) {
            ServerLevel overworld = server.overworld();
            LevelData.RespawnData spawn = server.getRespawnData();
            BlockPos pos = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.pos());
            for (ServerPlayer player : List.copyOf(level.players())) {
                player.stopRiding();
                player.teleportTo(overworld, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(), spawn.yaw(), spawn.pitch(), true);
                player.sendSystemMessage(Component.literal(
                        "The world " + world.name() + " was deleted. You were sent to spawn.").withStyle(ChatFormatting.YELLOW));
            }
            accessor().safeplots$levels().remove(world.key());
            server.markWorldsDirty();
            try {
                level.close();
            } catch (IOException e) {
                LOGGER.error("[SafePlots] Error closing world {}", world.name(), e);
            }
            NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
        }
        worlds.remove(world.name());
        boolean deleted = deleteFolder(world.name());
        if (!deleted) {
            pendingDeletes.add(world.name());
        }
        save();
        return deleted;
    }

    // ---------------------------------------------------------------- spawn

    public void setSpawn(World world, Spawn spawn) {
        worlds.put(world.name(), new World(world.name(), world.type(), world.seed(), spawn, world.group()));
        save();
    }

    /** The world's spawn, picking (and saving) a safe one near 0,0 the first time. */
    public Spawn spawn(World world, ServerLevel level) {
        World current = worlds.getOrDefault(world.name(), world);
        if (current.spawn() != null) {
            return current.spawn();
        }
        BlockPos ground = findGround(level);
        Spawn spawn = new Spawn(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, 0, 0);
        setSpawn(current, spawn);
        return spawn;
    }

    /** Where /mvtp nether|end lands: a safe spot near 0,0, found once per start. */
    public Spawn vanillaSpawn(ServerLevel level) {
        return vanillaSpawns.computeIfAbsent(level.dimension(), k -> {
            BlockPos ground = findGround(level);
            return new Spawn(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, 0, 0);
        });
    }

    /** First spot (spiralling out from 0,0) where a player can stand. Builds a small platform if there is none. */
    private static BlockPos findGround(ServerLevel level) {
        DimensionType dim = level.dimensionType();
        int top = level.getMinY() + dim.logicalHeight() - 3;
        for (int r = 0; r <= 48; r += 8) {
            for (int dx = -r; dx <= r; dx += 8) {
                for (int dz = -r; dz <= r; dz += 8) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // only the ring at distance r
                    }
                    BlockPos found = standable(level, dx, dz, top);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        // Void (or a column of lava everywhere): put a 5x5 stone platform under 0,64,0.
        BlockPos center = new BlockPos(0, Math.max(level.getMinY() + 1, Math.min(64, top)), 0);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(center.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
            }
        }
        return center;
    }

    private static @Nullable BlockPos standable(ServerLevel level, int x, int z, int top) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top; y > level.getMinY(); y--) {
            pos.setY(y);
            BlockState floor = level.getBlockState(pos);
            if (!floor.isFaceSturdy(level, pos, Direction.UP) || !floor.getFluidState().isEmpty() || floor.is(Blocks.BEDROCK) && y > level.getMinY() + 8) {
                continue;
            }
            BlockPos feet = pos.above();
            if (level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()) {
                return feet.immutable();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- levels

    private MinecraftServerAccessor accessor() {
        return (MinecraftServerAccessor) server;
    }

    /** Builds the level and adds it to the server, the same way vanilla adds the Nether and the End. */
    private ServerLevel open(World world) {
        ServerLevel existing = server.getLevel(world.key());
        if (existing != null) {
            return existing;
        }
        LevelStem stem = stem(server.registryAccess(), world.type(), world.seed());
        ServerLevel level = new ServerLevel(server, accessor().safeplots$executor(), accessor().safeplots$storageSource(),
                new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData()), world.key(), stem,
                false, BiomeManager.obfuscateSeed(world.seed()), ImmutableList.of(), false);
        level.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
        server.getPlayerList().addWorldborderListener(level);
        accessor().safeplots$levels().put(world.key(), level);
        server.markWorldsDirty();
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
        LOGGER.info("[SafePlots] Loaded world {} ({}, seed {})", world.name(), world.type().id(), world.seed());
        return level;
    }

    private static LevelStem stem(RegistryAccess registries, Type type, long seed) {
        Registry<DimensionType> types = registries.lookupOrThrow(Registries.DIMENSION_TYPE);
        Registry<NoiseGeneratorSettings> noise = registries.lookupOrThrow(Registries.NOISE_SETTINGS);
        Registry<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        Registry<StructureSet> structureSets = registries.lookupOrThrow(Registries.STRUCTURE_SET);
        Registry<PlacedFeature> placedFeatures = registries.lookupOrThrow(Registries.PLACED_FEATURE);
        var biomePresets = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        ChunkGenerator generator = switch (type) {
            case NORMAL, AMPLIFIED, LARGE_BIOMES -> new NoiseBasedChunkGenerator(
                    MultiNoiseBiomeSource.createFromPreset(biomePresets.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)),
                    noise.getOrThrow(switch (type) {
                        case AMPLIFIED -> NoiseGeneratorSettings.AMPLIFIED;
                        case LARGE_BIOMES -> NoiseGeneratorSettings.LARGE_BIOMES;
                        default -> NoiseGeneratorSettings.OVERWORLD;
                    }));
            case FLAT -> new FlatLevelSource(FlatLevelGeneratorSettings.getDefault(biomes, structureSets, placedFeatures));
            case VOID -> new FlatLevelSource(FlatLevelGeneratorSettings.getDefault(biomes, structureSets, placedFeatures)
                    .withBiomeAndLayers(List.of(new FlatLayerInfo(1, Blocks.AIR)), Optional.of(HolderSet.direct()), biomes.getOrThrow(Biomes.THE_VOID)));
            case NETHER -> new NoiseBasedChunkGenerator(
                    MultiNoiseBiomeSource.createFromPreset(biomePresets.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER)),
                    noise.getOrThrow(NoiseGeneratorSettings.NETHER));
            case END -> new NoiseBasedChunkGenerator(TheEndBiomeSource.create(biomes), noise.getOrThrow(NoiseGeneratorSettings.END));
        };
        var dimensionType = types.getOrThrow(switch (type) {
            case NETHER -> BuiltinDimensionTypes.NETHER;
            case END -> BuiltinDimensionTypes.END;
            default -> BuiltinDimensionTypes.OVERWORLD;
        });
        return new LevelStem(dimensionType, generator, OptionalLong.of(seed));
    }

    /** Where a world's region files live: {@code <world>/dimensions/safeplots/<name>}. */
    public Path folder(String name) {
        return accessor().safeplots$storageSource().getDimensionPath(
                ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(NAMESPACE, name)));
    }

    private boolean deleteFolder(String name) {
        Path dir = folder(name);
        if (!Files.exists(dir)) {
            return true;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("[SafePlots] Could not delete {} yet, will retry at next start: {}", dir, e.toString());
            return false;
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
        JsonObject worldsJson = root.has("worlds") ? root.getAsJsonObject("worlds") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : worldsJson.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Type type = Type.parse(o.get("type").getAsString());
            if (type == null) {
                throw new IllegalStateException("Unknown world type " + o.get("type") + " for world " + e.getKey());
            }
            Spawn spawn = null;
            if (o.has("spawn")) {
                JsonObject s = o.getAsJsonObject("spawn");
                spawn = new Spawn(s.get("x").getAsDouble(), s.get("y").getAsDouble(), s.get("z").getAsDouble(),
                        s.get("yRot").getAsFloat(), s.get("xRot").getAsFloat());
            }
            String group = o.has("group") ? o.get("group").getAsString() : null;
            worlds.put(e.getKey(), new World(e.getKey(), type, o.get("seed").getAsLong(), spawn, group));
        }
        if (root.has("pendingDeletes")) {
            root.getAsJsonArray("pendingDeletes").forEach(el -> pendingDeletes.add(el.getAsString()));
        }
    }

    private void save() {
        JsonObject worldsJson = new JsonObject();
        worlds.forEach((name, w) -> {
            JsonObject o = new JsonObject();
            o.addProperty("type", w.type().id());
            o.addProperty("seed", w.seed());
            if (w.spawn() != null) {
                JsonObject s = new JsonObject();
                s.addProperty("x", w.spawn().x());
                s.addProperty("y", w.spawn().y());
                s.addProperty("z", w.spawn().z());
                s.addProperty("yRot", w.spawn().yRot());
                s.addProperty("xRot", w.spawn().xRot());
                o.add("spawn", s);
            }
            if (w.group() != null) {
                o.addProperty("group", w.group());
            }
            worldsJson.add(name, o);
        });
        JsonObject root = new JsonObject();
        root.add("worlds", worldsJson);
        if (!pendingDeletes.isEmpty()) {
            root.add("pendingDeletes", GSON.toJsonTree(pendingDeletes));
        }
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

    /** For teleporting: the spawn of a world as a Vec3. */
    public static Vec3 pos(Spawn spawn) {
        return new Vec3(spawn.x(), spawn.y(), spawn.z());
    }
}
