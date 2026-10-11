package dev.safemc.safeplots.inventory;

import com.mojang.logging.LogUtils;
import dev.safemc.safeplots.world.Worlds;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * One inventory per world group. Each player carries the inventory of the group they are in; when they arrive in a
 * world of another group (portal, /mvtp, /home, /tpa, respawn, login, or an admin moving the world to another group),
 * everything they carry is stored under the group they left and the new group's is loaded.
 *
 * <p>Stored per group: inventory (with armor and offhand), ender chest, XP, health, hunger, effects and game mode.
 * Saved to {@code <world>/safeplots-inventories/<uuid>.dat}. {@code current} in that file names the group the
 * player's live (vanilla-saved) data belongs to, so a player who logs out in a world keeps that world's inventory.
 */
public final class WorldInventories {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Per player: which group the live data belongs to, and the stored data of every other group. */
    private static final class State {
        String current;
        final Map<String, CompoundTag> stored = new HashMap<>();

        State(String current) {
            this.current = current;
        }
    }

    private static @Nullable WorldInventories instance;

    private final MinecraftServer server;
    private final Path dir;
    private final Map<UUID, State> states = new HashMap<>();

    private WorldInventories(MinecraftServer server, Path dir) {
        this.server = server;
        this.dir = dir;
    }

    public static @Nullable WorldInventories get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        instance = new WorldInventories(server, server.getWorldPath(LevelResource.ROOT).resolve("safeplots-inventories").normalize());
    }

    public static void stop() {
        instance = null; // every change is saved when it happens
    }

    /** The group whose items the player is carrying right now. */
    public String currentGroup(ServerPlayer player) {
        return state(player).current;
    }

    /**
     * Swaps the player's inventory if they are in a world of another group than the one they carry.
     * Safe to call any time; does nothing when the groups match.
     */
    public void sync(ServerPlayer player) {
        Worlds worlds = Worlds.get();
        if (worlds == null || player.isRemoved() || player.isDeadOrDying()) {
            return;
        }
        String target = worlds.inventoryGroup(player.level());
        State state = state(player);
        if (state.current.equals(target)) {
            return;
        }
        String from = state.current;
        // Items on the cursor or in the 2x2 crafting grid go back into the inventory first, so nothing is left behind.
        player.closeContainer();

        CompoundTag leaving = capture(player);
        CompoundTag arriving = state.stored.get(target);
        state.stored.put(from, leaving);
        state.stored.remove(target);
        state.current = target;
        // Write our file before touching the player: if this fails, they keep what they carry and nothing is lost.
        if (!save(player.getUUID(), state)) {
            state.current = from;
            state.stored.remove(from);
            if (arriving != null) {
                state.stored.put(target, arriving);
            }
            player.sendSystemMessage(Component.literal("Could not switch your inventory to this world (see the server log). "
                    + "You still have the one from " + from + ".").withStyle(ChatFormatting.RED));
            return;
        }
        apply(player, arriving);
        // Save the vanilla player file right away too, so a crash can't pair the old items with the new group.
        server.getPlayerList().getPlayerIo().save(player);
        LOGGER.info("[SafePlots] {} switched inventory {} -> {}", player.getGameProfile().name(), from, target);
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (instance != null && event.getEntity() instanceof ServerPlayer player) {
            instance.sync(player); // e.g. their world was moved to another group while they were offline
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (instance != null && event.getEntity() instanceof ServerPlayer player) {
            instance.sync(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (instance != null && event.getEntity() instanceof ServerPlayer player) {
            instance.sync(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (instance != null) {
            instance.states.remove(event.getEntity().getUUID());
        }
    }

    /**
     * Items, minecarts, donkeys with chests etc. can't go through a portal into a world with another inventory;
     * otherwise throwing things through a portal would move them between inventories. Players are swapped instead.
     */
    @SubscribeEvent
    public static void onTravel(EntityTravelToDimensionEvent event) {
        Worlds worlds = Worlds.get();
        if (instance == null || worlds == null || event.getEntity() instanceof ServerPlayer
                || event.getEntity().level().isClientSide()) {
            return;
        }
        var target = instance.server.getLevel(event.getDimension());
        if (target != null && !worlds.inventoryGroup(event.getEntity().level()).equals(worlds.inventoryGroup(target))) {
            event.setCanceled(true);
        }
    }

    // ---------------------------------------------------------------- capture / apply

    private CompoundTag capture(ServerPlayer player) {
        RegistryOps<Tag> ops = ops();
        CompoundTag tag = new CompoundTag();
        tag.put("inventory", writeItems(player.getInventory(), ops));
        tag.put("enderChest", writeItems(player.getEnderChestInventory(), ops));
        tag.putInt("xpLevel", player.experienceLevel);
        tag.putFloat("xpProgress", player.experienceProgress);
        tag.putInt("xpTotal", player.totalExperience);
        tag.putFloat("health", player.getHealth());
        tag.putInt("food", player.getFoodData().getFoodLevel());
        tag.putFloat("saturation", player.getFoodData().getSaturationLevel());
        ListTag effects = new ListTag();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            MobEffectInstance.CODEC.encodeStart(ops, effect).ifSuccess(effects::add);
        }
        tag.put("effects", effects);
        tag.putString("gameMode", player.gameMode.getGameModeForPlayer().getName());
        return tag;
    }

    /** Loads a stored group, or a fresh start (empty, full health, the server's default game mode) on a first visit. */
    private void apply(ServerPlayer player, @Nullable CompoundTag tag) {
        RegistryOps<Tag> ops = ops();
        CompoundTag t = tag != null ? tag : new CompoundTag();

        readItems(player.getInventory(), t.getListOrEmpty("inventory"), ops);
        readItems(player.getEnderChestInventory(), t.getListOrEmpty("enderChest"), ops);

        GameType mode = tag != null ? GameType.byName(t.getStringOr("gameMode", ""), server.getDefaultGameType()) : server.getDefaultGameType();
        player.setGameMode(mode);

        // Effects before health: health boost / absorption change the maximum.
        player.removeAllEffects();
        for (Tag e : t.getListOrEmpty("effects")) {
            MobEffectInstance.CODEC.parse(ops, e).ifSuccess(player::addEffect);
        }
        player.setHealth(Math.min(player.getMaxHealth(), t.getFloatOr("health", player.getMaxHealth())));
        FoodData food = player.getFoodData();
        food.setFoodLevel(t.getIntOr("food", 20));
        food.setSaturation(t.getFloatOr("saturation", 5.0F));

        player.experienceLevel = t.getIntOr("xpLevel", 0);
        player.experienceProgress = t.getFloatOr("xpProgress", 0.0F);
        player.totalExperience = t.getIntOr("xpTotal", 0);
        player.connection.send(new ClientboundSetExperiencePacket(player.experienceProgress, player.totalExperience, player.experienceLevel));

        player.resetSentInfo(); // resend health and food
        player.inventoryMenu.broadcastChanges();
    }

    private static ListTag writeItems(Container container, RegistryOps<Tag> ops) {
        ListTag list = new ListTag();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            int s = slot;
            ItemStack.CODEC.encodeStart(ops, stack)
                    .resultOrPartial(err -> LOGGER.error("[SafePlots] Could not store an item: {}", err))
                    .ifPresent(item -> {
                        CompoundTag entry = new CompoundTag();
                        entry.putInt("slot", s);
                        entry.put("item", item);
                        list.add(entry);
                    });
        }
        return list;
    }

    private static void readItems(Container container, ListTag list, RegistryOps<Tag> ops) {
        container.clearContent();
        for (Tag t : list) {
            CompoundTag entry = (CompoundTag) t;
            int slot = entry.getIntOr("slot", -1);
            Tag item = entry.get("item");
            if (slot < 0 || slot >= container.getContainerSize() || item == null) {
                continue;
            }
            ItemStack.CODEC.parse(ops, item)
                    .resultOrPartial(err -> LOGGER.error("[SafePlots] Skipping unreadable stored item: {}", err))
                    .ifPresent(stack -> container.setItem(slot, stack));
        }
    }

    // ---------------------------------------------------------------- storage

    private RegistryOps<Tag> ops() {
        return server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
    }

    private Path file(UUID player) {
        return dir.resolve(player + ".dat");
    }

    /** Loads (or starts) a player's state. A player we've never seen carries the inventory of where they are. */
    private State state(ServerPlayer player) {
        State cached = states.get(player.getUUID());
        if (cached != null) {
            return cached;
        }
        State state = load(player.getUUID());
        if (state == null) {
            Worlds worlds = Worlds.get();
            state = new State(worlds == null ? Worlds.MAIN_GROUP : worlds.inventoryGroup(player.level()));
            save(player.getUUID(), state);
        }
        states.put(player.getUUID(), state);
        return state;
    }

    private @Nullable State load(UUID player) {
        Path file = file(player);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            State state = new State(root.getStringOr("current", Worlds.MAIN_GROUP));
            CompoundTag groups = root.getCompoundOrEmpty("groups");
            for (String group : groups.keySet()) {
                groups.getCompound(group).ifPresent(tag -> state.stored.put(group, tag));
            }
            return state;
        } catch (IOException | RuntimeException e) {
            // Keep the broken file for an admin to recover the stored inventories, and start over from what they carry.
            Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            LOGGER.error("[SafePlots] Could not read {}; moved it to {}. The player's other worlds start empty.", file, aside, e);
            try {
                Files.move(file, aside);
            } catch (IOException moveError) {
                LOGGER.error("[SafePlots] Could not move {} aside", file, moveError);
            }
            return null;
        }
    }

    private boolean save(UUID player, State state) {
        CompoundTag root = new CompoundTag();
        root.putString("current", state.current);
        CompoundTag groups = new CompoundTag();
        state.stored.forEach(groups::put);
        root.put("groups", groups);
        Path file = file(player);
        try {
            Files.createDirectories(dir);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("[SafePlots] Failed to save {}", file, e);
            return false;
        }
    }
}
