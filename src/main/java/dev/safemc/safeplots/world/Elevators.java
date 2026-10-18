package dev.safemc.safeplots.world;

import dev.safemc.safeplots.plot.Plot;
import dev.safemc.safeplots.plot.PlotManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Elevators: standing on a coal block, jumping takes you to the next coal block straight above, and
 * sneaking to the next one straight below (up to {@link #RANGE} blocks away, with room to stand on it).
 * Off everywhere by default; {@code /mv elevators <world> on|off} for each world. Never into a plot the player can't
 * build in.
 */
public final class Elevators {
    public static final int RANGE = 64;
    private static final int COOLDOWN_TICKS = 8;

    private static final Set<UUID> sneaking = new HashSet<>();
    private static final Map<UUID, Long> lastRide = new HashMap<>();

    private Elevators() {}

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ride(player, true);
        }
    }

    /** Sneaking: only the moment the key goes down, so holding it doesn't keep going. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            boolean down = player.isShiftKeyDown();
            if (down && sneaking.add(player.getUUID())) {
                ride(player, false);
            } else if (!down) {
                sneaking.remove(player.getUUID());
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        sneaking.remove(event.getEntity().getUUID());
        lastRide.remove(event.getEntity().getUUID());
    }

    /** For tests: the next ride doesn't wait for the cooldown. */
    public static void resetCooldown(ServerPlayer player) {
        lastRide.remove(player.getUUID());
    }

    /** Moves the player to the next elevator up or down. False if they aren't on one or there's none that way. */
    public static boolean ride(ServerPlayer player, boolean up) {
        ServerLevel level = player.level();
        Worlds worlds = Worlds.get();
        if (worlds == null || !worlds.elevators(level) || player.isSpectator() || !player.onGround()) {
            return false;
        }
        BlockPos from = player.getOnPos();
        if (isCarpet(level.getBlockState(from))) {
            from = from.below(); // a carpet on the coal block
        }
        if (!level.getBlockState(from).is(Blocks.COAL_BLOCK)) {
            return false;
        }
        long now = level.getGameTime();
        Long last = lastRide.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS) {
            return false;
        }
        BlockPos to = next(level, from, up);
        if (to == null) {
            return false;
        }
        lastRide.put(player.getUUID(), now);
        PlotManager plots = PlotManager.get();
        Plot plot = plots == null ? null : plots.plotAt(level, to.above());
        if (plot != null && !plot.isMember(player.getUUID()) && !plots.hasBypass(player)) {
            // Otherwise a coal block dug in under someone's house would be a way in through the floor.
            player.sendSystemMessage(Component.literal("That elevator leads into " + plot.name() + ", a plot you can't build in.")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        double carpet = isCarpet(level.getBlockState(to.above())) ? 1.0 / 16 : 0;
        player.teleportTo(level, to.getX() + 0.5, to.getY() + 1 + carpet, to.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
        player.setDeltaMovement(0, 0, 0);
        player.resetFallDistance();
        level.playSound(null, to.above(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.5F, up ? 1.4F : 0.9F);
        return true;
    }

    private static boolean isCarpet(BlockState state) {
        return state.getBlock() instanceof CarpetBlock || state.getBlock() instanceof MossyCarpetBlock;
    }

    /** The nearest coal block straight above or below with two blocks of room on top (a carpet on it is fine). */
    public static @Nullable BlockPos next(ServerLevel level, BlockPos from, boolean up) {
        for (int d = 1; d <= RANGE; d++) {
            BlockPos pos = from.above(up ? d : -d);
            if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY()) {
                return null;
            }
            if (level.getBlockState(pos).is(Blocks.COAL_BLOCK)
                    && (isCarpet(level.getBlockState(pos.above())) || level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty())
                    && level.getBlockState(pos.above(2)).getCollisionShape(level, pos.above(2)).isEmpty()) {
                return pos;
            }
        }
        return null;
    }
}
