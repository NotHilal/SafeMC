package dev.safemc.safeplots.grave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/**
 * When a player dies, their drops and XP go into a grave (their own head) instead of onto the ground.
 * Only they can reclaim it, by right-clicking or breaking the head. Nothing else can destroy it.
 */
public final class GraveEvents {
    // XP is decided just before the item drops, in the same call; remember it for the grave.
    private static final Map<UUID, Integer> PENDING_XP = new HashMap<>();

    private GraveEvents() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onXp(LivingExperienceDropEvent event) {
        if (GraveManager.get() != null && event.getEntity() instanceof ServerPlayer player && event.getDroppedExperience() > 0) {
            PENDING_XP.put(player.getUUID(), event.getDroppedExperience());
            event.setDroppedExperience(0);
        }
    }

    // LOW priority so other mods (e.g. ones that keep certain items) can take their items out first.
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDrops(LivingDropsEvent event) {
        GraveManager manager = GraveManager.get();
        if (manager == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        int xp = PENDING_XP.getOrDefault(player.getUUID(), 0);
        PENDING_XP.remove(player.getUUID());
        List<ItemStack> items = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            if (!drop.getItem().isEmpty()) {
                items.add(drop.getItem().copy());
            }
        }
        Grave grave = manager.create(player, items, xp);
        if (grave == null) {
            return;
        }
        event.setCanceled(true); // the items are now in the grave
        player.sendSystemMessage(Component.literal("☠ Your items are in your grave at " + grave.describePos()
                + ". Only you can open it. See /grave list").withStyle(ChatFormatting.GOLD));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (handleTouch(event.getEntity(), event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START
                && handleTouch(event.getEntity(), event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    /** Covers breaking by any means that goes through a player (creative, instant break, other mods' tools). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BreakBlockEvent event) {
        if (event.getLevel() instanceof Level level && handleTouch(event.getPlayer(), level, event.getPos())) {
            event.setCanceled(true);
        }
    }

    /** Returns true if the position is a grave (the event must then be cancelled). Claims it for its owner. */
    private static boolean handleTouch(Player player, Level level, BlockPos pos) {
        GraveManager manager = GraveManager.get();
        if (manager == null || level.isClientSide()) {
            return false;
        }
        Grave grave = manager.at(level, pos);
        if (grave == null) {
            return false;
        }
        if (player instanceof ServerPlayer sp) {
            if (grave.owner().equals(sp.getUUID())) {
                manager.giveBack(grave, sp);
                sp.sendSystemMessage(Component.literal("✓ You got your items back.").withStyle(ChatFormatting.GREEN));
            } else {
                sp.sendSystemMessage(Component.literal("This is " + grave.ownerName() + "'s grave. Only they can open it.")
                        .withStyle(ChatFormatting.RED), true);
            }
        }
        return true;
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        GraveManager manager = GraveManager.get();
        if (manager != null) {
            event.getAffectedBlocks().removeIf(pos -> manager.at(event.getLevel(), pos) != null);
        }
    }

    /** The Wither and the ender dragon destroy blocks directly, not through an explosion. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMobDestroyBlock(LivingDestroyBlockEvent event) {
        GraveManager manager = GraveManager.get();
        if (manager != null && manager.at(event.getEntity().level(), event.getPos()) != null) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPiston(PistonEvent.Pre event) {
        GraveManager manager = GraveManager.get();
        if (manager == null || !(event.getLevel() instanceof Level level)) {
            return;
        }
        PistonStructureResolver structure = event.getStructureHelper();
        if (structure == null || !structure.resolve()) {
            return;
        }
        for (BlockPos pos : structure.getToPush()) {
            if (manager.at(level, pos) != null) {
                event.setCanceled(true);
                return;
            }
        }
        for (BlockPos pos : structure.getToDestroy()) {
            if (manager.at(level, pos) != null) {
                event.setCanceled(true);
                return;
            }
        }
    }
}
