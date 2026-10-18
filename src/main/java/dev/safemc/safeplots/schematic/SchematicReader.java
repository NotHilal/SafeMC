package dev.safemc.safeplots.schematic;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.util.datafix.fixes.ChunkPalettedStorageFix;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Reads Sponge schematics ({@code .schem}, versions 1 to 3), the format WorldEdit 7+, FAWE, Axiom and most
 * schematic sites use, and old MCEdit / WorldEdit 6 {@code .schematic} files with pre-1.13 number ids.
 * Blocks and block entities saved by older Minecraft versions are upgraded with the game's own data fixers.
 * Thread-safe: it only reads registries.
 */
public final class SchematicReader {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Sponge v1 files have no DataVersion; WorldEdit treats them as 1.13.2. */
    private static final int V1_DATA_VERSION = 1631;
    /** The Flattening (17w47a): old ids are translated into block states of this version. */
    private static final int FLATTENING_DATA_VERSION = 1451;
    /** 1.12.2, for block entities of old schematics with namespaced ids ("minecraft:chest"). */
    private static final int LEGACY_DATA_VERSION = 1343;
    /** Before 1.11, block entity ids had no namespace ("Chest"); the fixers rename them from here. */
    private static final int ANCIENT_DATA_VERSION = 99;

    private SchematicReader() {}

    public static Schematic read(Path file, String name, HolderLookup.Provider registries, long maxVolume) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        CompoundTag s = root.getCompound("Schematic").orElse(root); // v3 nests everything in "Schematic"
        if (s.contains("Materials")) {
            return readLegacy(s, name, registries, maxVolume);
        }
        int version = s.getIntOr("Version", 1);
        int width = s.getShortOr("Width", (short) 0) & 0xFFFF;
        int height = s.getShortOr("Height", (short) 0) & 0xFFFF;
        int length = s.getShortOr("Length", (short) 0) & 0xFFFF;
        long volume = (long) width * height * length;
        if (volume <= 0) {
            throw new IOException("the schematic is empty or not a Sponge schematic");
        }
        if (volume > maxVolume) {
            throw new IOException("it is too big (" + width + " x " + height + " x " + length + " = " + volume
                    + " blocks; the limit is " + maxVolume + ")");
        }
        int dataVersion = s.getIntOr("DataVersion", V1_DATA_VERSION);
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        if (dataVersion > current) {
            throw new IOException("it was saved by a newer Minecraft version than this server");
        }

        CompoundTag paletteTag;
        byte[] data;
        ListTag blockEntityTags;
        boolean nested;
        BlockPos offset;
        if (version >= 3) {
            CompoundTag blocks = s.getCompoundOrEmpty("Blocks");
            paletteTag = blocks.getCompoundOrEmpty("Palette");
            data = blocks.getByteArray("Data").orElse(new byte[0]);
            blockEntityTags = blocks.getListOrEmpty("BlockEntities");
            nested = true;
            // v3: the corner is at Offset from the paste position.
            offset = toPos(s.getIntArray("Offset").orElse(null));
        } else {
            paletteTag = s.getCompoundOrEmpty("Palette");
            data = s.getByteArray("BlockData").orElse(new byte[0]);
            blockEntityTags = s.contains("BlockEntities") ? s.getListOrEmpty("BlockEntities") : s.getListOrEmpty("TileEntities");
            nested = false;
            // v1/v2: "Offset" is where it was copied in the world; WorldEdit stores the paste offset in its metadata.
            CompoundTag meta = s.getCompoundOrEmpty("Metadata");
            offset = new BlockPos(meta.getIntOr("WEOffsetX", 0), meta.getIntOr("WEOffsetY", 0), meta.getIntOr("WEOffsetZ", 0));
        }

        DataFixer fixer = DataFixers.getDataFixer();
        HolderLookup<Block> blockLookup = registries.lookupOrThrow(Registries.BLOCK);
        int unknown = 0;
        int paletteSize = 0;
        for (String key : paletteTag.keySet()) {
            paletteSize = Math.max(paletteSize, paletteTag.getIntOr(key, -1) + 1);
        }
        BlockState[] palette = new BlockState[paletteSize];
        for (String key : paletteTag.keySet()) {
            int id = paletteTag.getIntOr(key, -1);
            if (id < 0) {
                continue;
            }
            String state = dataVersion < current ? upgradeState(fixer, key, dataVersion, current) : key;
            try {
                palette[id] = BlockStateParser.parseForBlock(blockLookup, state, false).blockState();
            } catch (CommandSyntaxException e) {
                palette[id] = Blocks.AIR.defaultBlockState(); // a block from a mod that isn't installed
                unknown++;
            }
        }
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == null) {
                palette[i] = Blocks.AIR.defaultBlockState();
            }
        }

        int[] blocks = new int[(int) volume];
        int index = 0;
        int pos = 0;
        while (pos < data.length) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                if (pos >= data.length || shift > 28) {
                    throw new IOException("the block data is damaged");
                }
                b = data[pos++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            if (index >= blocks.length || value < 0 || value >= palette.length) {
                throw new IOException("the block data is damaged");
            }
            blocks[index++] = value;
        }
        if (index != blocks.length) {
            throw new IOException("the block data is incomplete (" + index + " of " + blocks.length + " blocks)");
        }

        Map<Integer, CompoundTag> blockEntities = new HashMap<>();
        for (Tag t : blockEntityTags) {
            if (!(t instanceof CompoundTag be)) {
                continue;
            }
            int[] p = be.getIntArray("Pos").orElse(null);
            String id = be.getString("Id").or(() -> be.getString("id")).orElse(null);
            if (p == null || p.length != 3 || id == null || p[0] < 0 || p[1] < 0 || p[2] < 0
                    || p[0] >= width || p[1] >= height || p[2] >= length) {
                continue;
            }
            CompoundTag tag;
            if (nested) {
                tag = be.getCompoundOrEmpty("Data").copy();
            } else {
                tag = be.copy();
                tag.remove("Id");
                tag.remove("Pos");
            }
            tag.putString("id", id);
            if (dataVersion < current) {
                Tag fixed = fixer.update(References.BLOCK_ENTITY, new Dynamic<>(NbtOps.INSTANCE, (Tag) tag), dataVersion, current).getValue();
                if (fixed instanceof CompoundTag c) {
                    tag = c;
                }
            }
            tag.remove("x");
            tag.remove("y");
            tag.remove("z");
            blockEntities.put(p[0] + p[2] * width + p[1] * width * length, tag);
        }

        int entities = s.getListOrEmpty("Entities").size();
        return new Schematic(name, width, height, length, offset, palette, blocks, blockEntities, entities, unknown);
    }

    /**
     * MCEdit / WorldEdit 6 {@code .schematic}: one byte id per block ({@code Blocks}, plus 4 high bits in
     * {@code AddBlocks} for ids above 255) and a 4-bit data value ({@code Data}), in the same x, z, y order as Sponge.
     * Converted the way Minecraft upgrades a pre-1.13 world: ids through the game's id table, then the blocks whose
     * new state depends on their block entity or a neighbour, then the block entities through the data fixers.
     */
    private static Schematic readLegacy(CompoundTag s, String name, HolderLookup.Provider registries, long maxVolume) throws IOException {
        String materials = s.getStringOr("Materials", "");
        if (!materials.equals("Alpha")) {
            throw new IOException("its block format (" + materials + ") isn't supported; only Java Edition (Alpha) schematics are");
        }
        int width = s.getShortOr("Width", (short) 0) & 0xFFFF;
        int height = s.getShortOr("Height", (short) 0) & 0xFFFF;
        int length = s.getShortOr("Length", (short) 0) & 0xFFFF;
        long volume = (long) width * height * length;
        if (volume <= 0) {
            throw new IOException("the schematic is empty");
        }
        if (volume > maxVolume) {
            throw new IOException("it is too big (" + width + " x " + height + " x " + length + " = " + volume
                    + " blocks; the limit is " + maxVolume + ")");
        }
        byte[] ids = s.getByteArray("Blocks").orElse(new byte[0]);
        byte[] data = s.getByteArray("Data").orElse(new byte[0]);
        byte[] add = s.getByteArray("AddBlocks").orElse(new byte[0]);
        if (ids.length != volume || data.length != volume) {
            throw new IOException("the block data is incomplete (" + ids.length + " of " + volume + " blocks)");
        }

        // 1. Every block's old id, and the 1.13 state the game's id table gives it (null: a modded id above 255).
        int[] oldIds = new int[(int) volume];
        Dynamic<?>[] states = new Dynamic<?>[(int) volume];
        Set<Integer> unknownIds = new HashSet<>();
        for (int i = 0; i < oldIds.length; i++) {
            int id = ids[i] & 0xFF;
            if ((i >> 1) < add.length) {
                int high = (i & 1) == 0 ? add[i >> 1] & 0x0F : (add[i >> 1] & 0xF0) >> 4;
                id |= high << 8;
            }
            oldIds[i] = id;
            if (id > 255) {
                unknownIds.add(id);
            } else {
                states[i] = BlockStateData.getTag(id << 4 | (data[i] & 0x0F));
            }
        }

        // 2. Block entities by cell. Ids from before 1.11 ("Chest") are renamed first.
        DataFixer fixer = DataFixers.getDataFixer();
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        Map<Integer, Dynamic<Tag>> entities = new HashMap<>();
        int skippedEntities = 0;
        for (Tag t : s.getListOrEmpty("TileEntities")) {
            if (!(t instanceof CompoundTag be)) {
                continue;
            }
            int x = be.getIntOr("x", -1);
            int y = be.getIntOr("y", -1);
            int z = be.getIntOr("z", -1);
            String id = be.getString("id").orElse(null);
            if (id == null || x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
                continue;
            }
            Dynamic<Tag> entity = new Dynamic<>(NbtOps.INSTANCE, be.copy());
            if (!id.contains(":")) {
                try {
                    entity = new Dynamic<>(NbtOps.INSTANCE, fixer.update(References.BLOCK_ENTITY, entity, ANCIENT_DATA_VERSION, LEGACY_DATA_VERSION).getValue());
                } catch (RuntimeException e) {
                    skippedEntities++;
                    continue;
                }
            }
            entities.put(x + z * width + y * width * length, entity);
        }

        // 3. Beds, banners, flower pots, skulls, note blocks, doors, snowy grass and tall plants.
        fixDependentBlocks(width * length, oldIds, states, entities);

        // 4. The palette, upgraded from 1.13 to now.
        HolderLookup<Block> blockLookup = registries.lookupOrThrow(Registries.BLOCK);
        Map<String, Integer> paletteIndex = new HashMap<>();
        List<BlockState> palette = new ArrayList<>();
        int unknown = unknownIds.size();
        int[] blocks = new int[(int) volume];
        for (int i = 0; i < blocks.length; i++) {
            String state = states[i] == null ? "minecraft:air" : stateString(states[i]);
            Integer index = paletteIndex.get(state);
            if (index == null) {
                BlockState blockState = Blocks.AIR.defaultBlockState();
                try {
                    blockState = BlockStateParser.parseForBlock(blockLookup, upgradeState(fixer, state, FLATTENING_DATA_VERSION, current), false).blockState();
                } catch (CommandSyntaxException e) {
                    BlockState lenient = lenientState(blockLookup, fixer, states[i], current);
                    if (lenient == null) {
                        unknown++;
                    } else {
                        blockState = lenient;
                    }
                }
                index = palette.size();
                palette.add(blockState);
                paletteIndex.put(state, index);
            }
            blocks[i] = index;
        }

        // 5. The block entities that are left, upgraded to now.
        Map<Integer, CompoundTag> blockEntities = new HashMap<>();
        for (Map.Entry<Integer, Dynamic<Tag>> e : entities.entrySet()) {
            try {
                Tag fixed = fixer.update(References.BLOCK_ENTITY, e.getValue(), LEGACY_DATA_VERSION, current).getValue();
                if (fixed instanceof CompoundTag tag) {
                    tag.remove("x");
                    tag.remove("y");
                    tag.remove("z");
                    blockEntities.put(e.getKey(), tag);
                }
            } catch (RuntimeException ex) {
                skippedEntities++; // a block entity type that no longer exists; the block itself is still placed
            }
        }
        if (skippedEntities > 0) {
            LOGGER.warn("[SafePlots] {}: skipped {} block entities that could not be upgraded", name, skippedEntities);
        }

        BlockPos offset = new BlockPos(s.getIntOr("WEOffsetX", 0), s.getIntOr("WEOffsetY", 0), s.getIntOr("WEOffsetZ", 0));
        int entityCount = s.getListOrEmpty("Entities").size();
        return new Schematic(name, width, height, length, offset, palette.toArray(new BlockState[0]), blocks, blockEntities, entityCount, unknown, true);
    }

    /**
     * The same corrections Minecraft's chunk upgrade ({@code ChunkPalettedStorageFix}) makes after translating ids:
     * before 1.13 some blocks kept part of what they are in their block entity (bed and banner colors, the plant in
     * a flower pot, skull types, note block pitch) or got it from a neighbour (door hinges, tall plant tops, snowy
     * grass). {@code layer} is width * length: the index step of one block up.
     */
    private static void fixDependentBlocks(int layer, int[] oldIds, Dynamic<?>[] states, Map<Integer, Dynamic<Tag>> entities) {
        for (int i = 0; i < states.length; i++) {
            Dynamic<?> state = states[i];
            if (state == null) {
                continue;
            }
            int up = i + layer < states.length ? i + layer : -1;
            int down = i - layer;
            switch (oldIds[i]) {
                case 18, 161 -> {
                    // Old leaves have decayable/check_decay, which no longer exist. Leaves in a build should stay.
                    CompoundTag leaves = new CompoundTag();
                    leaves.putString("Name", nameOf(state));
                    CompoundTag properties = new CompoundTag();
                    properties.putString("persistent", "true");
                    leaves.put("Properties", properties);
                    states[i] = new Dynamic<>(NbtOps.INSTANCE, leaves);
                }
                case 2 -> {
                    if (nameOf(state).equals("minecraft:grass_block") && snowAt(states, up)) {
                        states[i] = ChunkPalettedStorageFix.MappingConstants.SNOWY_GRASS;
                    }
                }
                case 3 -> {
                    if (nameOf(state).equals("minecraft:podzol") && snowAt(states, up)) {
                        states[i] = ChunkPalettedStorageFix.MappingConstants.SNOWY_PODZOL;
                    }
                }
                case 110 -> {
                    if (nameOf(state).equals("minecraft:mycelium") && snowAt(states, up)) {
                        states[i] = ChunkPalettedStorageFix.MappingConstants.SNOWY_MYCELIUM;
                    }
                }
                case 86 -> {
                    String below = down >= 0 && states[down] != null ? nameOf(states[down]) : "";
                    if (nameOf(state).equals("minecraft:carved_pumpkin") && (below.equals("minecraft:grass_block") || below.equals("minecraft:dirt"))) {
                        states[i] = ChunkPalettedStorageFix.MappingConstants.PUMPKIN;
                    }
                }
                case 25 -> {
                    Dynamic<Tag> entity = entities.remove(i);
                    if (entity != null) {
                        String key = Boolean.toString(entity.get("powered").asBoolean(false))
                                + (byte) Math.min(Math.max(entity.get("note").asInt(0), 0), 24);
                        Map<String, Dynamic<?>> map = ChunkPalettedStorageFix.MappingConstants.NOTE_BLOCK_MAP;
                        states[i] = map.getOrDefault(key, map.get("false0"));
                    }
                }
                case 26 -> {
                    Dynamic<Tag> entity = entities.get(i);
                    int color = entity == null ? 14 : entity.get("color").asInt(0);
                    if (color != 14 && color >= 0 && color < 16) {
                        String key = property(state, "facing") + property(state, "occupied") + property(state, "part") + color;
                        Dynamic<?> bed = ChunkPalettedStorageFix.MappingConstants.BED_BLOCK_MAP.get(key);
                        if (bed != null) {
                            states[i] = bed;
                        }
                    }
                }
                case 64, 71, 193, 194, 195, 196, 197 -> {
                    if (nameOf(state).endsWith("_door") && property(state, "half").equals("lower") && up >= 0 && states[up] != null
                            && nameOf(states[up]).equals(nameOf(state))) {
                        Dynamic<?> upper = states[up];
                        String key = nameOf(state) + property(state, "facing");
                        String rest = property(upper, "hinge") + property(state, "open") + property(upper, "powered");
                        Map<String, Dynamic<?>> doors = ChunkPalettedStorageFix.MappingConstants.DOOR_MAP;
                        Dynamic<?> lower = doors.get(key + "lower" + rest);
                        Dynamic<?> top = doors.get(key + "upper" + rest);
                        if (lower != null && top != null) {
                            states[i] = lower;
                            states[up] = top;
                        }
                    }
                }
                case 140 -> {
                    Dynamic<Tag> entity = entities.remove(i);
                    if (entity != null) {
                        String key = entity.get("Item").asString("") + entity.get("Data").asInt(0);
                        Map<String, Dynamic<?>> pots = ChunkPalettedStorageFix.MappingConstants.FLOWER_POT_MAP;
                        states[i] = pots.getOrDefault(key, pots.get("minecraft:air0"));
                    }
                }
                case 144 -> {
                    Dynamic<Tag> entity = entities.get(i);
                    if (entity != null) {
                        String facing = property(state, "facing");
                        String key = entity.get("SkullType").asInt(0)
                                + (facing.equals("up") || facing.equals("down") ? String.valueOf(entity.get("Rot").asInt(0)) : facing);
                        entities.put(i, entity.remove("SkullType").remove("facing").remove("Rot"));
                        Map<String, Dynamic<?>> skulls = ChunkPalettedStorageFix.MappingConstants.SKULL_MAP;
                        states[i] = skulls.getOrDefault(key, skulls.get("0north"));
                    }
                }
                case 175 -> {
                    if (property(state, "half").equals("upper") && down >= 0 && states[down] != null) {
                        String plant = nameOf(states[down]);
                        if (Set.of("minecraft:sunflower", "minecraft:lilac", "minecraft:tall_grass", "minecraft:large_fern",
                                "minecraft:rose_bush", "minecraft:peony").contains(plant)) {
                            CompoundTag top = new CompoundTag();
                            top.putString("Name", plant);
                            CompoundTag properties = new CompoundTag();
                            properties.putString("half", "upper");
                            top.put("Properties", properties);
                            states[i] = new Dynamic<>(NbtOps.INSTANCE, top);
                        }
                    }
                }
                case 176, 177 -> {
                    Dynamic<Tag> entity = entities.get(i);
                    int color = entity == null ? 15 : entity.get("Base").asInt(0);
                    if (color != 15 && color >= 0 && color < 16) {
                        String key = property(state, oldIds[i] == 176 ? "rotation" : "facing") + "_" + color;
                        Dynamic<?> banner = ChunkPalettedStorageFix.MappingConstants.BANNER_BLOCK_MAP.get(key);
                        if (banner != null) {
                            states[i] = banner;
                        }
                    }
                }
                default -> {}
            }
        }
    }

    /**
     * For a state the parser refuses (a property that was renamed or dropped since): the upgraded block with only the
     * properties it still has. Null if the block itself no longer exists.
     */
    private static @Nullable BlockState lenientState(HolderLookup<Block> blockLookup, DataFixer fixer, @Nullable Dynamic<?> state, int current) {
        if (state == null) {
            return null;
        }
        BlockState result;
        try {
            result = BlockStateParser.parseForBlock(blockLookup, upgradeState(fixer, nameOf(state), FLATTENING_DATA_VERSION, current), false).blockState();
        } catch (CommandSyntaxException e) {
            return null;
        }
        Map<String, String> properties = state.get("Properties").asMap(k -> k.asString(""), v -> v.asString(""));
        for (Property<?> property : result.getProperties()) {
            String value = properties.get(property.getName());
            if (value != null) {
                result = withValue(result, property, value);
            }
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    private static boolean snowAt(Dynamic<?>[] states, int index) {
        if (index < 0 || states[index] == null) {
            return false;
        }
        String name = nameOf(states[index]);
        return name.equals("minecraft:snow") || name.equals("minecraft:snow_layer");
    }

    private static String nameOf(Dynamic<?> state) {
        return state.get("Name").asString("");
    }

    private static String property(Dynamic<?> state, String property) {
        return state.get("Properties").get(property).asString("");
    }

    /** A block state as "name[key=value,...]". */
    private static String stateString(Dynamic<?> state) {
        StringBuilder text = new StringBuilder(nameOf(state));
        Map<String, String> properties = new TreeMap<>(state.get("Properties").asMap(k -> k.asString(""), v -> v.asString("")));
        if (!properties.isEmpty()) {
            text.append('[');
            properties.forEach((k, v) -> text.append(k).append('=').append(v).append(','));
            text.setCharAt(text.length() - 1, ']');
        }
        return text.toString();
    }

    private static String upgradeState(DataFixer fixer, String state, int from, int to) {
        Tag fixed = fixer.update(References.FLAT_BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, (Tag) StringTag.valueOf(state)), from, to).getValue();
        return fixed.asString().orElse(state);
    }

    private static BlockPos toPos(int[] a) {
        return a == null || a.length != 3 ? BlockPos.ZERO : new BlockPos(a[0], a[1], a[2]);
    }
}
