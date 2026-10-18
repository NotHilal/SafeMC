package dev.safemc.safeplots.parkour;

import dev.safemc.safeplots.holo.Holograms;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The "Leave parkour" barrier: in the last hotbar slot during a run, gone after it. Right-clicking it stops the
 * run (instead of placing it). It can't be dropped or put in a chest.
 */
public final class LeaveItem {
    public static final int SLOT = 8;
    private static final String MARK = "safeplots_parkour_leave";
    private static final ItemStack ITEM = create();

    private LeaveItem() {}

    private static ItemStack create() {
        ItemStack stack = new ItemStack(Items.BARRIER);
        stack.set(DataComponents.CUSTOM_NAME, Component.empty().withStyle(style -> style.withItalic(false))
                .append(Holograms.colored("&c&lLeave parkour")));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Right-click to stop your run")
                .withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)))));
        CompoundTag mark = new CompoundTag();
        mark.putBoolean(MARK, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(mark));
        return stack;
    }

    public static boolean is(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(MARK);
    }

    /** During a run: the barrier in the last hotbar slot and nowhere else. Otherwise: none at all. */
    static void sync(ServerPlayer player, boolean running) {
        Inventory inventory = player.getInventory();
        boolean changed = false;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if ((i != SLOT || !running) && is(inventory.getItem(i))) {
                inventory.setItem(i, ItemStack.EMPTY);
                changed = true;
            }
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (is(menu.getCarried())) {
            menu.setCarried(ItemStack.EMPTY);
            changed = true;
        }
        for (Slot slot : menu.slots) {
            if (slot.container != inventory && is(slot.getItem())) {
                slot.set(ItemStack.EMPTY);
                changed = true;
            }
        }
        if (running && !is(inventory.getItem(SLOT))) {
            ItemStack old = inventory.getItem(SLOT);
            inventory.setItem(SLOT, ITEM.copy());
            if (!old.isEmpty() && !inventory.add(old)) {
                player.drop(old, false, Prediction.SERVER_ONLY);
            }
            changed = true;
        }
        if (changed) {
            menu.broadcastChanges();
        }
    }

    /** Right-clicking the barrier. */
    public static boolean leave(ServerPlayer player) {
        Parkours parkours = Parkours.get();
        if (parkours != null && parkours.running(player)) {
            parkours.leave(player);
            player.sendSystemMessage(Component.literal("You left the parkour.").withStyle(ChatFormatting.YELLOW));
        }
        sync(player, false);
        return true;
    }

    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player && is(event.getItemStack()) && leave(player)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onUseOnBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && is(event.getItemStack()) && leave(player)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** Thrown away or dropped on death: gone (a run gets new ones). */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity item && is(item.getItem())) {
            event.setCanceled(true);
        }
    }
}
