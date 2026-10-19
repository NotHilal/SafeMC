package dev.safemc.safeplots.world;

import dev.safemc.safeplots.moderation.Afk;
import dev.safemc.safeplots.teleport.Teleports;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * In the game hub ({@code /mv setgamehub <world>}) every player has a Back to hub item in the last hotbar slot:
 * right-clicking it goes to the hub right away (no teleport wait or cooldown). Not while they're in a minigame (those have their own leave item there).
 * It can't be dropped or kept: it's taken away outside the game hub.
 */
public final class HubItem {
    public static final int SLOT = 8;
    private static final String MARK = "safeplots_hub_item";
    private static final int USE_COOLDOWN = 10;
    private static final Map<UUID, Integer> LAST_USE = new HashMap<>();

    private HubItem() {}

    public static boolean is(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(MARK);
    }

    static ItemStack create() {
        ItemStack stack = new ItemStack(Items.BED.pick(net.minecraft.world.item.DyeColor.RED));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Back to hub").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GREEN).withBold(true)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Right-click to go back to the hub.").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))));
        CompoundTag mark = new CompoundTag();
        mark.putBoolean(MARK, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(mark));
        return stack;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Worlds worlds = Worlds.get();
        if (worlds != null && event.getServer().getTickCount() % 10 == 0) {
            syncAll(worlds, event.getServer());
        }
    }

    public static void syncAll(Worlds worlds, MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sync(player, worlds.isGameHub(player.level()) && !player.entityTags().contains(Afk.IN_GAME)
                    && player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR);
        }
    }

    /** {@code wanted}: the item in the last slot and nowhere else. Otherwise: none anywhere. */
    static void sync(ServerPlayer player, boolean wanted) {
        Inventory inventory = player.getInventory();
        boolean changed = false;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if ((i != SLOT || !wanted) && is(inventory.getItem(i))) {
                inventory.setItem(i, ItemStack.EMPTY);
                changed = true;
            }
        }
        if (is(player.containerMenu.getCarried())) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            changed = true;
        }
        if (wanted && !is(inventory.getItem(SLOT))) {
            ItemStack before = inventory.getItem(SLOT);
            inventory.setItem(SLOT, create());
            if (!before.isEmpty() && !inventory.add(before)) {
                player.drop(before, false, Prediction.SERVER_ONLY);
            }
            changed = true;
        }
        if (changed) {
            player.inventoryMenu.broadcastChanges();
        }
    }

    private static void use(ServerPlayer player) {
        Worlds worlds = Worlds.get();
        int now = player.level().getServer().getTickCount();
        Integer last = LAST_USE.put(player.getUUID(), now);
        if (worlds == null || last != null && now - last < USE_COOLDOWN) {
            return; // a click on a block can be followed by a click in the air: once is enough
        }
        if (worlds.levelByName(worlds.hub()) == null) {
            player.sendSystemMessage(Component.literal("The hub world (" + worlds.hub() + ") isn't loaded. Ask an admin.").withStyle(ChatFormatting.RED));
            return;
        }
        Teleports.now(player, "the hub", () -> worlds.spawnDestination(worlds.hub()));
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onUse(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player && is(event.getItemStack())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            use(player);
        }
    }

    /** Right-clicking a block with it works too, without placing a bed. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onUseOnBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && is(event.getItemStack())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            use(player);
        }
    }

    /** Dropping it: it's gone from the hand and comes back in its slot. */
    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        if (is(event.getEntity().getItem())) {
            event.setCanceled(true);
        }
    }

    /** One that ended up on the ground some other way (e.g. a death outside the game hub) disappears. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof ItemEntity item && is(item.getItem())) {
            event.setCanceled(true);
        }
    }
}
