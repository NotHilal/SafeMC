package dev.safemc.safeplots.schematic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/**
 * Writes a schematic as a Sponge v3 {@code .schem} file, the format WorldEdit, FAWE and Axiom use, so a copied build
 * can be pasted again later, here or on another server.
 */
public final class SchematicWriter {
    private SchematicWriter() {}

    public static void write(Schematic s, Path file) throws IOException {
        CompoundTag schematic = new CompoundTag();
        schematic.putInt("Version", 3);
        schematic.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        schematic.putShort("Width", (short) s.width());
        schematic.putShort("Height", (short) s.height());
        schematic.putShort("Length", (short) s.length());
        schematic.putIntArray("Offset", new int[] {s.offset().getX(), s.offset().getY(), s.offset().getZ()});

        // Palette entries that write the same (e.g. several unknown blocks read as air) share one id.
        CompoundTag palette = new CompoundTag();
        Map<String, Integer> ids = new HashMap<>();
        int[] remap = new int[s.palette().length];
        for (int i = 0; i < remap.length; i++) {
            String state = BlockStateParser.serialize(s.palette()[i]);
            Integer id = ids.get(state);
            if (id == null) {
                id = ids.size();
                ids.put(state, id);
                palette.putInt(state, id);
            }
            remap[i] = id;
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream(s.volume());
        for (int block : s.blocks()) {
            int value = remap[block];
            while ((value & ~0x7F) != 0) {
                data.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            data.write(value);
        }

        ListTag blockEntities = new ListTag();
        for (Map.Entry<Integer, CompoundTag> e : s.blockEntities().entrySet()) {
            CompoundTag tag = e.getValue().copy();
            String id = tag.getStringOr("id", "");
            tag.remove("id");
            BlockPos cell = s.cell(e.getKey());
            CompoundTag entry = new CompoundTag();
            entry.putIntArray("Pos", new int[] {cell.getX(), cell.getY(), cell.getZ()});
            entry.putString("Id", id);
            entry.put("Data", tag);
            blockEntities.add(entry);
        }

        CompoundTag blocks = new CompoundTag();
        blocks.put("Palette", palette);
        blocks.putByteArray("Data", data.toByteArray());
        blocks.put("BlockEntities", blockEntities);
        schematic.put("Blocks", blocks);

        CompoundTag root = new CompoundTag();
        root.put("Schematic", schematic);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(root, temp);
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
