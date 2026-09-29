package dev.safemc.safeplots.grave;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * Everything a player dropped when they died. The items live in the save file, not in the head block,
 * so nothing is lost if the block is removed; the head is only a marker to click.
 */
public record Grave(int id, UUID owner, String ownerName, String dimension, BlockPos pos, long createdAt, int xp, List<ItemStack> items) {
    public String describePos() {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " (" + dimension + ")";
    }
}
