package dev.safemc.safeplots.schematic;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
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
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads Sponge schematics ({@code .schem}, versions 1 to 3), the format WorldEdit 7+, FAWE, Axiom and most
 * schematic sites use. Blocks and block entities saved by older Minecraft versions are upgraded with the game's
 * own data fixers. Thread-safe: it only reads registries.
 */
public final class SchematicReader {
    /** Sponge v1 files have no DataVersion; WorldEdit treats them as 1.13.2. */
    private static final int V1_DATA_VERSION = 1631;

    private SchematicReader() {}

    public static Schematic read(Path file, String name, HolderLookup.Provider registries, long maxVolume) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        CompoundTag s = root.getCompound("Schematic").orElse(root); // v3 nests everything in "Schematic"
        if (s.contains("Materials")) {
            throw new IOException("this is an old MCEdit/WorldEdit 6 .schematic (pre-1.13 block ids). Open it with WorldEdit 7"
                    + " or a converter and save it as .schem");
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

    private static String upgradeState(DataFixer fixer, String state, int from, int to) {
        Tag fixed = fixer.update(References.FLAT_BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, (Tag) StringTag.valueOf(state)), from, to).getValue();
        return fixed.asString().orElse(state);
    }

    private static BlockPos toPos(int[] a) {
        return a == null || a.length != 3 ? BlockPos.ZERO : new BlockPos(a[0], a[1], a[2]);
    }
}
