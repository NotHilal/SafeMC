package dev.safemc.safeplots.schematic;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A loaded schematic. {@code blocks} holds a palette index per cell in Sponge order
 * ({@code index = x + z * width + y * width * length}). {@code offset} is where the schematic's corner goes,
 * relative to where you stand when pasting, exactly as WorldEdit would place it.
 * Block entity tags are keyed by cell index and contain {@code id} but no position.
 * {@code fixShapes}: the file doesn't store how blocks connect (old .schematic), so glass panes, fences, walls and
 * stairs are joined to their neighbours after pasting.
 */
public record Schematic(String name, int width, int height, int length, BlockPos offset, BlockState[] palette, int[] blocks,
                        Map<Integer, CompoundTag> blockEntities, int skippedEntities, int unknownBlocks, boolean fixShapes) {
    public Schematic(String name, int width, int height, int length, BlockPos offset, BlockState[] palette, int[] blocks,
                     Map<Integer, CompoundTag> blockEntities, int skippedEntities, int unknownBlocks) {
        this(name, width, height, length, offset, palette, blocks, blockEntities, skippedEntities, unknownBlocks, false);
    }

    public int volume() {
        return blocks.length;
    }

    /** Position of a cell inside the schematic (0..width-1 etc.). */
    public BlockPos cell(int index) {
        int layer = width * length;
        int y = index / layer;
        int rest = index - y * layer;
        int z = rest / width;
        return new BlockPos(rest - z * width, y, z);
    }

    public String size() {
        return width + " x " + height + " x " + length;
    }
}
