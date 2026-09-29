package dev.safemc.safeplots.teleport;

import dev.safemc.safeplots.moderation.ModerationManager;
import dev.safemc.safeplots.plot.PlotManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Warm-up, cooldown and /tpa requests. A teleport starts a 3 second warm-up that is cancelled if the player
 * moves or takes damage, so it can't be used to escape a fight; afterwards there's a 30 second cooldown.
 * Admins teleport instantly.
 */
public final class Teleports {
    public static final int WARMUP_TICKS = 60;
    public static final long COOLDOWN_MS = 30_000;
    public static final long REQUEST_TIMEOUT_MS = 60_000;

    public record Destination(ServerLevel level, Vec3 pos, float yRot, float xRot) {}

    /** {@code here == false}: "from" goes to "to" (/tpa). {@code here == true}: "to" goes to "from" (/tpahere). */
    public record Request(UUID from, String fromName, UUID to, boolean here, long createdAt) {}

    private static final class Pending {
        final Vec3 start;
        final Supplier<@Nullable Destination> destination;
        final String what;
        int ticksLeft = WARMUP_TICKS;

        Pending(Vec3 start, Supplier<@Nullable Destination> destination, String what) {
            this.start = start;
            this.destination = destination;
            this.what = what;
        }
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Long> LAST_TELEPORT = new HashMap<>();
    private static final List<Request> REQUESTS = new ArrayList<>();

    private Teleports() {}

    // ---------------------------------------------------------------- teleporting

    /** Starts a teleport (instantly for admins). {@code destination} is evaluated when the warm-up ends. */
    public static boolean start(ServerPlayer player, String what, Supplier<@Nullable Destination> destination) {
        if (PlotManager.isAdmin(player)) {
            return finish(player, what, destination);
        }
        long wait = LAST_TELEPORT.getOrDefault(player.getUUID(), 0L) + COOLDOWN_MS - System.currentTimeMillis();
        if (wait > 0) {
            player.sendSystemMessage(Component.literal("You can teleport again in " + ((wait + 999) / 1000) + " seconds.").withStyle(ChatFormatting.RED));
            return false;
        }
        PENDING.put(player.getUUID(), new Pending(player.position(), destination, what));
        player.sendSystemMessage(Component.literal("Teleporting to " + what + " in " + (WARMUP_TICKS / 20) + " seconds. Don't move.")
                .withStyle(ChatFormatting.YELLOW));
        return true;
    }

    private static boolean finish(ServerPlayer player, String what, Supplier<@Nullable Destination> destination) {
        ModerationManager moderation = ModerationManager.get();
        if (moderation != null && moderation.isFrozen(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You can't teleport while frozen.").withStyle(ChatFormatting.RED));
            return false;
        }
        Destination dest = destination.get();
        if (dest == null) {
            player.sendSystemMessage(Component.literal("Teleport cancelled: " + what + " is no longer available.").withStyle(ChatFormatting.RED));
            return false;
        }
        player.stopRiding();
        player.teleportTo(dest.level(), dest.pos().x, dest.pos().y, dest.pos().z, Set.of(), dest.yRot(), dest.xRot(), true);
        LAST_TELEPORT.put(player.getUUID(), System.currentTimeMillis());
        player.sendSystemMessage(Component.literal("Teleported to " + what + ".").withStyle(ChatFormatting.GREEN));
        return true;
    }

    public static Destination destinationOf(ServerPlayer player) {
        return new Destination(player.level(), player.position(), player.getYRot(), player.getXRot());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = System.currentTimeMillis();
        REQUESTS.removeIf(r -> now - r.createdAt() > REQUEST_TIMEOUT_MS);
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Pending pending = entry.getValue();
            if (player == null) {
                it.remove();
                continue;
            }
            if (player.position().distanceToSqr(pending.start) > 0.25) {
                it.remove();
                player.sendSystemMessage(Component.literal("Teleport cancelled because you moved.").withStyle(ChatFormatting.RED));
                continue;
            }
            if (--pending.ticksLeft <= 0) {
                it.remove();
                finish(player, pending.what, pending.destination);
            } else if (pending.ticksLeft % 20 == 0) {
                player.sendSystemMessage(Component.literal("Teleporting in " + (pending.ticksLeft / 20) + "...").withStyle(ChatFormatting.YELLOW), true);
            }
        }
    }

    /** Taking damage during the warm-up cancels it. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && PENDING.remove(player.getUUID()) != null) {
            player.sendSystemMessage(Component.literal("Teleport cancelled because you took damage.").withStyle(ChatFormatting.RED));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        PENDING.remove(id);
        REQUESTS.removeIf(r -> r.from().equals(id) || r.to().equals(id));
    }

    public static boolean isPending(UUID player) {
        return PENDING.containsKey(player);
    }

    // ---------------------------------------------------------------- /tpa requests

    public static void addRequest(Request request) {
        // One request per pair: a new one replaces the old.
        REQUESTS.removeIf(r -> r.from().equals(request.from()) && r.to().equals(request.to()));
        REQUESTS.add(request);
    }

    /** The newest request to {@code to}, optionally only from {@code from}. Removes it. */
    public static @Nullable Request takeRequest(UUID to, @Nullable UUID from) {
        for (int i = REQUESTS.size() - 1; i >= 0; i--) {
            Request r = REQUESTS.get(i);
            if (r.to().equals(to) && (from == null || r.from().equals(from))) {
                REQUESTS.remove(i);
                return r;
            }
        }
        return null;
    }
}
