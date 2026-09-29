package dev.safemc.safeplots.moderation;

import dev.safemc.safeplots.plot.PlotManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jspecify.annotations.Nullable;

/** Enforces mutes and freezes, and keeps vanish consistent when players join. */
public final class ModerationEvents {
    /** Chat-like commands a muted player can't use. */
    private static final Set<String> MESSAGE_COMMANDS = Set.of("msg", "tell", "w", "me", "teammsg", "tm", "say", "r", "reply");
    /** The only commands a frozen player may use: talking to staff. */
    private static final Set<String> FROZEN_ALLOWED = Set.of("msg", "tell", "w", "r", "reply");

    private static final Map<UUID, Vec3> FREEZE_ANCHOR = new HashMap<>();

    private ModerationEvents() {}

    // ---------------------------------------------------------------- mute

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChat(ServerChatEvent event) {
        if (mutedMessage(event.getPlayer()) != null) {
            event.setCanceled(true);
            event.getPlayer().sendSystemMessage(Component.literal(mutedMessage(event.getPlayer())).withStyle(ChatFormatting.RED));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCommand(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String root = event.getParseResults().getReader().getString().trim();
        root = (root.startsWith("/") ? root.substring(1) : root).split(" ", 2)[0].toLowerCase();

        String muted = mutedMessage(player);
        if (muted != null && MESSAGE_COMMANDS.contains(root)) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal(muted).withStyle(ChatFormatting.RED));
            return;
        }
        if (frozen(player) && !FROZEN_ALLOWED.contains(root)) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal("You are frozen. You can only use /msg to talk to staff.").withStyle(ChatFormatting.RED));
        }
    }

    private static @Nullable String mutedMessage(ServerPlayer player) {
        ModerationManager manager = ModerationManager.get();
        ModerationManager.Mute mute = manager == null ? null : manager.mute(player.getUUID());
        if (mute == null) {
            return null;
        }
        String time = mute.until() == 0 ? "" : " for " + Durations.format(mute.until() - System.currentTimeMillis());
        return "You are muted" + time + "." + (mute.reason().isEmpty() ? "" : " Reason: " + mute.reason());
    }

    // ---------------------------------------------------------------- freeze

    private static boolean frozen(Player player) {
        ModerationManager manager = ModerationManager.get();
        return manager != null && player instanceof ServerPlayer && manager.isFrozen(player.getUUID());
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ModerationManager manager = ModerationManager.get();
        if (manager == null) {
            return;
        }
        if (manager.isVanished(player.getUUID()) && player.tickCount % 40 == 0) {
            player.sendSystemMessage(Component.literal("You are vanished").withStyle(ChatFormatting.GRAY), true);
        }
        if (!manager.isFrozen(player.getUUID())) {
            FREEZE_ANCHOR.remove(player.getUUID());
            return;
        }
        if (player.isPassenger()) {
            player.stopRiding();
        }
        Vec3 anchor = FREEZE_ANCHOR.computeIfAbsent(player.getUUID(), id -> groundBelow(player));
        if (player.position().distanceToSqr(anchor) > 0.01) {
            player.connection.teleport(anchor.x, anchor.y, anchor.z, player.getYRot(), player.getXRot());
        }
        player.connection.resetFlyingTicks(); // standing still in the air must not get them kicked for flying
        if (player.tickCount % 40 == 0) {
            player.sendSystemMessage(Component.literal("You are frozen by staff. Please wait.").withStyle(ChatFormatting.AQUA), true);
        }
    }

    /** Freezing someone mid-air puts them on the ground below instead. */
    private static Vec3 groundBelow(ServerPlayer player) {
        ServerLevel level = player.level();
        BlockPos pos = player.blockPosition();
        for (int i = 0; i < 64 && pos.getY() > level.getMinY(); i++) {
            BlockPos below = pos.below();
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                return new Vec3(player.getX(), pos.getY(), player.getZ());
            }
            pos = below;
        }
        return player.position();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        cancelIfFrozen(event.getEntity(), event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        cancelIfFrozen(event.getEntity(), event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        cancelIfFrozen(event.getEntity(), event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
        cancelIfFrozen(event.getEntity(), event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        cancelIfFrozen(event.getEntity(), event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BreakBlockEvent event) {
        cancelIfFrozen(event.getPlayer(), event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancelIfFrozen(player, event);
        }
    }

    /** Frozen players can't be hurt either, so nobody can kill them while they can't move. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancelIfFrozen(player, event);
        }
    }

    private static void cancelIfFrozen(Player player, ICancellableEvent event) {
        if (!player.level().isClientSide() && frozen(player)) {
            event.setCanceled(true);
        }
    }

    // ---------------------------------------------------------------- login / logout

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Vanish.hideVanishedFrom(player);
            if (frozen(player)) {
                player.sendSystemMessage(Component.literal("You are frozen by staff. Please wait.").withStyle(ChatFormatting.AQUA));
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        FREEZE_ANCHOR.remove(event.getEntity().getUUID());
        ModerationManager manager = ModerationManager.get();
        if (manager != null) {
            manager.setVanished(event.getEntity().getUUID(), false); // vanish lasts until logout
        }
    }

    /** /freeze on an online player: pin them where they are right now. Offline players are pinned when they join. */
    static void anchorNow(ServerPlayer player) {
        FREEZE_ANCHOR.put(player.getUUID(), groundBelow(player));
    }

    static void clearAnchor(UUID player) {
        FREEZE_ANCHOR.remove(player);
    }
}
