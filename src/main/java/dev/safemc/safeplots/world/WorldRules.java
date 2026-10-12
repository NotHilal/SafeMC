package dev.safemc.safeplots.world;

import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import org.jspecify.annotations.Nullable;

/**
 * Per-world rules: {@code /mv gamemode} (everyone there is put in that game mode) and {@code /mv mobs} (no natural
 * mob spawning). The game mode applies to admins too; they can still change it with /gamemode while there.
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
}
