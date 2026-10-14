package dev.safemc.safeplots.world;

import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.Map;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Per-world rules: {@code /mv gamemode} (everyone there is put in that game mode), {@code /mv mobs} (no natural
 * mob spawning), {@code /mv tnt} (TNT only explodes in worlds where it was turned on) and {@code /mv alwaysday}
 * (noon and no weather). The game mode applies to admins too; they can still change it with /gamemode while there.
 * <p>The player's own game mode is remembered when they enter a world with a forced one and given back when they
 * arrive in a world without one. That matters when both worlds share an inventory group; otherwise switching
 * inventories already restores it.
 */
public final class WorldRules {
    /** Inside {@link Player#PERSISTED_NBT_TAG}, which NeoForge keeps across deaths. */
    private static final String OWN_MODE = "safeplots_ownGameMode";

    /** Spawns that happen by themselves. Eggs, /summon, breeding, buckets, dispensers, conversions etc. still work. */
    private static final Set<EntitySpawnReason> NATURAL = Set.of(
            EntitySpawnReason.NATURAL, EntitySpawnReason.CHUNK_GENERATION, EntitySpawnReason.SPAWNER,
            EntitySpawnReason.TRIAL_SPAWNER, EntitySpawnReason.STRUCTURE, EntitySpawnReason.PATROL,
            EntitySpawnReason.REINFORCEMENT, EntitySpawnReason.JOCKEY, EntitySpawnReason.EVENT);

    private WorldRules() {}

    /**
     * Applies the game mode rule of the player's current world. {@code previous} is their game mode before they
     * arrived (before any inventory switch); {@code switchedGroup} whether arriving switched their inventory, which
     * already gave them that group's own game mode back.
     */
    public static void apply(ServerPlayer player, GameType previous, boolean switchedGroup) {
        Worlds worlds = Worlds.get();
        if (worlds == null) {
            return;
        }
        Worlds.World world = worlds.worldOf(player.level());
        GameType forced = world == null ? null : world.gameMode();
        CompoundTag data = persisted(player);
        if (forced != null) {
            if (!data.contains(OWN_MODE)) {
                data.putString(OWN_MODE, previous.getName());
            }
            player.setGameMode(forced);
        } else if (data.contains(OWN_MODE)) {
            GameType own = GameType.byName(data.getStringOr(OWN_MODE, ""), player.level().getServer().getDefaultGameType());
            data.remove(OWN_MODE);
            if (!switchedGroup) {
                player.setGameMode(own);
            }
        }
        sendSky(player);
    }

    /** After /mv gamemode changed: players already in the world get the new rule right away. */
    public static void applyToWorld(Worlds worlds, Worlds.World world) {
        var level = worlds.level(world);
        if (level != null) {
            for (ServerPlayer player : level.players()) {
                apply(player, player.gameMode.getGameModeForPlayer(), false);
            }
        }
    }

    private static CompoundTag persisted(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(Player.PERSISTED_NBT_TAG)) {
            data.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        }
        return data.getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        Worlds worlds = Worlds.get();
        if (worlds == null || !NATURAL.contains(event.getSpawnType())) {
            return;
        }
        Worlds.@Nullable World world = worlds.worldOf(event.getLevel().getLevel());
        if (world != null && !world.mobs()) {
            event.setSpawnCancelled(true);
        }
    }

    /** Unless {@code /mv tnt <world> on}, lit TNT and TNT minecarts vanish without exploding. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onExplosion(ExplosionEvent.Start event) {
        Worlds worlds = Worlds.get();
        Entity source = event.getExplosion().getDirectSourceEntity();
        if (worlds == null || !(source instanceof PrimedTnt || source instanceof MinecartTNT) || worlds.tnt(event.getLevel())) {
            return;
        }
        event.setCanceled(true);
        if (source instanceof PrimedTnt tnt && tnt.getOwner() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal("TNT is turned off in this world.").withStyle(ChatFormatting.RED), true);
        }
    }

    // ---------------------------------------------------------------- always day

    /** Noon of the current day, the time players in an always-day world see. */
    private static final int NOON = 6000;
    private static final int DAY = 24000;

    /**
     * Time is shared by all worlds of the server, so an always-day world can't really have its own clock. Instead its
     * players are told the clock stands still at noon; arriving anywhere else, vanilla sends them the real time again.
     * Repeated every second, so /time set and the like don't show for long.
     */
    public static void sendSky(ServerPlayer player) {
        Worlds worlds = Worlds.get();
        ServerLevel level = player.level();
        if (worlds == null || !worlds.alwaysDay(level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        Holder<WorldClock> clock = level.dimensionType().defaultClock().orElse(null);
        if (clock != null) {
            long total = server.clockManager().getInstance(clock).totalTicks();
            ClockNetworkState noon = new ClockNetworkState(Math.floorDiv(total, DAY) * DAY + NOON, 0.0F, 0.0F);
            player.connection.send(new ClientboundSetTimePacket(server.overworld().getGameTime(), Map.of(clock, noon)));
        }
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0.0F));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.0F));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.0F));
    }

    /** After {@code /mv alwaysday} changed: players already there see the change right away. */
    public static void alwaysDayChanged(Worlds worlds, Worlds.World world, boolean on) {
        ServerLevel level = worlds.level(world);
        if (level == null) {
            return;
        }
        if (on) {
            level.setRainLevel(0.0F);
            level.setThunderLevel(0.0F);
        }
        for (ServerPlayer player : level.players()) {
            if (on) {
                sendSky(player);
            } else {
                level.getServer().getPlayerList().sendLevelInfo(player, level); // the real time and weather
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Worlds worlds = Worlds.get();
        if (worlds == null || event.getServer().getTickCount() % 20 != 0) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (worlds.alwaysDay(level)) {
                level.players().forEach(WorldRules::sendSky);
            }
        }
    }
}
