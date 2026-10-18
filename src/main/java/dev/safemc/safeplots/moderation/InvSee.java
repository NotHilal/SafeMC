package dev.safemc.safeplots.moderation;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * /invsee &lt;player&gt;: opens an online player's inventory in a chest window. Staff can look, take items out and put
 * items in; changes happen live in the player's real inventory. Rows 1-3: the main inventory, row 4: the hotbar,
 * row 5: helmet, chestplate, leggings, boots and the off hand.
 */
public final class InvSee {
    private static final int ROWS = 5;
    /** For each window slot, the slot of the player's inventory it shows, or -1 for a locked gray pane. */
    static final int[] LAYOUT = new int[ROWS * 9];

    static {
        for (int i = 0; i < 27; i++) {
            LAYOUT[i] = 9 + i; // main inventory
        }
        for (int i = 0; i < 9; i++) {
            LAYOUT[27 + i] = i; // hotbar
        }
        int[] bottom = {39, 38, 37, 36, Inventory.SLOT_OFFHAND, -1, -1, -1, -1}; // head, chest, legs, feet, off hand
        System.arraycopy(bottom, 0, LAYOUT, 36, 9);
    }

    private InvSee() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("invsee").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("player", EntityArgument.player()).executes(InvSee::run)));
    }

    private static int run(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer viewer = c.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(c, "player");
        if (target == viewer) {
            c.getSource().sendFailure(Component.literal("That's your own inventory. Press E."));
            return 0;
        }
        open(viewer, target);
        c.getSource().sendSuccess(() -> Component.literal("Opened " + target.getGameProfile().name() + "'s inventory. "
                + "Rows 1-3: inventory, row 4: hotbar, row 5: armor and off hand.").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    public static void open(ServerPlayer viewer, ServerPlayer target) {
        View view = new View(target);
        viewer.openMenu(new SimpleMenuProvider((id, inventory, p) -> new Menu(id, inventory, view),
                Component.literal(target.getGameProfile().name() + "'s inventory")));
    }

    /** The target's live inventory, laid out for a 5-row chest window. */
    static final class View implements Container {
        private final ServerPlayer target;
        private final ItemStack filler;

        View(ServerPlayer target) {
            this.target = target;
            filler = new ItemStack(Items.STAINED_GLASS_PANE.pick(DyeColor.GRAY));
            filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        }

        private Inventory inv() {
            return target.getInventory();
        }

        @Override
        public int getContainerSize() {
            return LAYOUT.length;
        }

        @Override
        public boolean isEmpty() {
            return inv().isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return LAYOUT[slot] < 0 ? filler.copy() : inv().getItem(LAYOUT[slot]);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            return LAYOUT[slot] < 0 ? ItemStack.EMPTY : inv().removeItem(LAYOUT[slot], count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return LAYOUT[slot] < 0 ? ItemStack.EMPTY : inv().removeItemNoUpdate(LAYOUT[slot]);
        }

        @Override
        public void setItem(int slot, ItemStack itemStack) {
            if (LAYOUT[slot] >= 0) {
                inv().setItem(LAYOUT[slot], itemStack);
            }
        }

        @Override
        public void setChanged() {
            inv().setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return !target.isRemoved() && !target.hasDisconnected();
        }

        @Override
        public void clearContent() {
            // never clear someone's inventory from here
        }
    }

    /** Like a 5-row chest, but the gray panes can't be taken or replaced. Closes when the player logs out. */
    public static final class Menu extends AbstractContainerMenu {
        private final View view;

        Menu(int id, Inventory viewerInventory, View view) {
            super(MenuType.GENERIC_9x5, id);
            this.view = view;
            for (int y = 0; y < ROWS; y++) {
                for (int x = 0; x < 9; x++) {
                    int index = x + y * 9;
                    addSlot(new Slot(view, index, 8 + x * 18, 18 + y * 18) {
                        @Override
                        public boolean mayPlace(ItemStack stack) {
                            return LAYOUT[index] >= 0;
                        }

                        @Override
                        public boolean mayPickup(Player player) {
                            return LAYOUT[index] >= 0;
                        }
                    });
                }
            }
            addStandardInventorySlots(viewerInventory, 8, 18 + ROWS * 18 + 13);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slotIndex) {
            ItemStack clicked = ItemStack.EMPTY;
            Slot slot = slots.get(slotIndex);
            if (slot.hasItem() && slot.mayPickup(player)) {
                ItemStack stack = slot.getItem();
                clicked = stack.copy();
                int size = ROWS * 9;
                if (slotIndex < size ? !moveItemStackTo(stack, size, slots.size(), true) : !moveItemStackTo(stack, 0, size, false)) {
                    return ItemStack.EMPTY;
                }
                if (stack.isEmpty()) {
                    slot.setByPlayer(ItemStack.EMPTY);
                } else {
                    slot.setChanged();
                }
            }
            return clicked;
        }

        @Override
        public boolean stillValid(Player player) {
            return view.stillValid(player);
        }
    }
}
