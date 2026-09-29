package dev.safemc.safeplots.command;

import dev.safemc.safeplots.plot.PlotManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The selection wand from {@code /plot wand}: a red candle tagged with custom data, so ordinary red candles
 * still work normally. Left-click a block sets position 1, right-click sets position 2. Admins only.
 */
public final class PlotWand {
    private static final String TAG = "safeplots_wand";

    private PlotWand() {}

    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.DYED_CANDLE.red());
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Plot Wand").withStyle(ChatFormatting.RED));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(TAG, true));
        return stack;
    }

    public static boolean isWand(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(TAG);
    }

    /** Shared by the wand and by {@code /plot pos1|pos2}. */
    static void setCorner(PlotManager manager, ServerPlayer player, int corner, BlockPos pos) {
        manager.setCorner(player.getUUID(), corner, PlotManager.dimensionId(player.level()), pos);
        player.sendSystemMessage(Component.literal("Position " + corner + " set to " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ".")
                .withStyle(ChatFormatting.GREEN));
    }

    // Runs before the protection handlers; cancelling here also stops the block breaking or the candle being placed.

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        PlotManager manager = PlotManager.get();
        if (manager == null || event.getLevel().isClientSide() || !isWand(event.getItemStack())
                || !(event.getEntity() instanceof ServerPlayer player) || !PlotManager.isAdmin(player)) {
            return;
        }
        event.setCanceled(true);
        if (event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START) {
            setCorner(manager, player, 1, event.getPos());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        PlotManager manager = PlotManager.get();
        if (manager == null || event.getLevel().isClientSide() || !isWand(event.getItemStack())
                || !(event.getEntity() instanceof ServerPlayer player) || !PlotManager.isAdmin(player)) {
            return;
        }
        event.setCanceled(true);
        setCorner(manager, player, 2, event.getPos());
    }
}
