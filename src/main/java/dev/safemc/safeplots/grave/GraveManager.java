package dev.safemc.safeplots.grave;

import com.mojang.logging.LogUtils;
import dev.safemc.safeplots.plot.PlotManager;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** All graves on the server, saved to {@code <world>/safeplots-graves.dat}. Server thread only. */
public final class GraveManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "safeplots-graves.dat";
    private static final int SEARCH_UP = 16;

    private static @Nullable GraveManager instance;

    private final MinecraftServer server;
    private final Path file;
    private final Map<Integer, Grave> graves = new TreeMap<>();
    private final Map<String, Map<Long, Grave>> byPos = new HashMap<>();
    private int nextId = 1;

    private GraveManager(MinecraftServer server, Path file) {
        this.server = server;
        this.file = file;
    }

    public static @Nullable GraveManager get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME).normalize();
        GraveManager manager = new GraveManager(server, file);
        try {
            manager.load();
        } catch (IOException | RuntimeException e) {
            // Same policy as plots: stop instead of running and later overwriting everyone's items.
            throw new IllegalStateException("[SafePlots] Could not read " + file
                    + ". Fix or restore the file (the server was stopped to protect the graves).", e);
        }
        instance = manager;
        LOGGER.info("[SafePlots] Loaded {} graves", manager.graves.size());
    }

    public static void stop() {
        if (instance != null) {
            instance.save();
        }
        instance = null;
    }

    // ---------------------------------------------------------------- lookups

    public @Nullable Grave at(Level level, BlockPos pos) {
        Map<Long, Grave> map = byPos.get(PlotManager.dimensionId(level));
        return map == null ? null : map.get(pos.asLong());
    }

    public @Nullable Grave byId(int id) {
        return graves.get(id);
    }

    public List<Grave> ownedBy(UUID player) {
        return graves.values().stream().filter(g -> g.owner().equals(player)).toList();
    }

    // ---------------------------------------------------------------- create / claim

    /**
     * Creates a grave at (or just above) the death position and places the owner's head there.
     * Returns null if the player had nothing to store.
     */
    public @Nullable Grave create(ServerPlayer player, List<ItemStack> items, int xp) {
        if (items.isEmpty() && xp <= 0) {
            return null;
        }
        ServerLevel level = player.level();
        BlockPos pos = findSpot(level, player.blockPosition());
        Grave grave = new Grave(nextId++, player.getUUID(), player.getGameProfile().name(), PlotManager.dimensionId(level),
                pos, System.currentTimeMillis(), Math.max(0, xp), List.copyOf(items));
        graves.put(grave.id(), grave);
        index(grave);
        save();

        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(player.getGameProfile()));
        int rotation = Math.floorMod(Math.round(player.getYRot() * 16.0F / 360.0F), 16);
        level.setBlockAndUpdate(pos, Blocks.PLAYER_HEAD.defaultBlockState().setValue(SkullBlock.ROTATION, rotation));
        if (level.getBlockEntity(pos) != null) {
            level.getBlockEntity(pos).applyComponentsFromItemStack(head);
            level.getBlockEntity(pos).setChanged();
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, 3);
        }
        return grave;
    }

    /** Gives everything back to the player (armor goes back on if the slot is free) and removes the grave. */
    public void giveBack(Grave grave, ServerPlayer player) {
        remove(grave);
        for (ItemStack original : grave.items()) {
            ItemStack stack = original.copy();
            EquipmentSlot slot = player.getEquipmentSlotForItem(stack);
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && player.getItemBySlot(slot).isEmpty()) {
                player.setItemSlot(slot, stack);
                continue;
            }
            if (!player.getInventory().add(stack) || !stack.isEmpty()) {
                // Inventory full: drop at their feet, and only they can pick it up.
                ItemEntity drop = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), stack);
                drop.setTarget(player.getUUID());
                drop.setNoPickUpDelay();
                player.level().addFreshEntity(drop);
            }
        }
        if (grave.xp() > 0) {
            player.giveExperiencePoints(grave.xp());
        }
    }

    /** Removes the grave record and its head block (if the head is still there). */
    public void remove(Grave grave) {
        graves.remove(grave.id());
        Map<Long, Grave> map = byPos.get(grave.dimension());
        if (map != null) {
            map.remove(grave.pos().asLong());
        }
        save();
        ServerLevel level = PlotManager.get() == null ? null : PlotManager.get().level(grave.dimension());
        if (level != null && level.isLoaded(grave.pos()) && level.getBlockState(grave.pos()).is(Blocks.PLAYER_HEAD)) {
            level.removeBlock(grave.pos(), false);
        }
    }

    /** First free spot at or above the death position: air, plants, snow or liquid, never an existing block. */
    private BlockPos findSpot(ServerLevel level, BlockPos death) {
        int y = Math.max(level.getMinY(), Math.min(level.getMaxY(), death.getY()));
        BlockPos start = new BlockPos(death.getX(), y, death.getZ());
        for (int dy = 0; dy <= SEARCH_UP && start.getY() + dy <= level.getMaxY(); dy++) {
            BlockPos pos = start.above(dy);
            BlockState state = level.getBlockState(pos);
            boolean free = state.isAir() || state.canBeReplaced() || (!state.getFluidState().isEmpty() && !state.hasBlockEntity());
            if (free && !state.hasBlockEntity() && at(level, pos) == null) {
                return pos;
            }
        }
        // No free spot nearby (e.g. died inside blocks): use the first spot that isn't a container or grave.
        for (int dy = 0; start.getY() + dy <= level.getMaxY(); dy++) {
            BlockPos pos = start.above(dy);
            if (!level.getBlockState(pos).hasBlockEntity() && at(level, pos) == null) {
                return pos;
            }
        }
        return start;
    }

    // ---------------------------------------------------------------- storage

    private void index(Grave grave) {
        byPos.computeIfAbsent(grave.dimension(), d -> new HashMap<>()).put(grave.pos().asLong(), grave);
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        nextId = root.getIntOr("nextId", 1);
        for (Tag t : root.getListOrEmpty("graves")) {
            CompoundTag g = (CompoundTag) t;
            List<ItemStack> items = new ArrayList<>();
            for (Tag itemTag : g.getListOrEmpty("items")) {
                ItemStack.CODEC.parse(ops, itemTag)
                        .resultOrPartial(err -> LOGGER.error("[SafePlots] Skipping unreadable item in grave {}: {}", g.getIntOr("id", -1), err))
                        .ifPresent(items::add);
            }
            int[] pos = g.getIntArray("pos").orElseThrow();
            int[] owner = g.getIntArray("owner").orElseThrow();
            Grave grave = new Grave(g.getIntOr("id", nextId), UUIDUtil.uuidFromIntArray(owner), g.getStringOr("name", "?"),
                    g.getStringOr("dimension", "minecraft:overworld"), new BlockPos(pos[0], pos[1], pos[2]),
                    g.getLongOr("createdAt", 0L), g.getIntOr("xp", 0), items);
            graves.put(grave.id(), grave);
            index(grave);
            nextId = Math.max(nextId, grave.id() + 1);
        }
    }

    public void save() {
        RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        CompoundTag root = new CompoundTag();
        root.putInt("nextId", nextId);
        ListTag list = new ListTag();
        for (Grave grave : graves.values()) {
            CompoundTag g = new CompoundTag();
            g.putInt("id", grave.id());
            g.putIntArray("owner", UUIDUtil.uuidToIntArray(grave.owner()));
            g.putString("name", grave.ownerName());
            g.putString("dimension", grave.dimension());
            g.putIntArray("pos", new int[] {grave.pos().getX(), grave.pos().getY(), grave.pos().getZ()});
            g.putLong("createdAt", grave.createdAt());
            g.putInt("xp", grave.xp());
            ListTag items = new ListTag();
            for (ItemStack stack : grave.items()) {
                ItemStack.CODEC.encodeStart(ops, stack)
                        .resultOrPartial(err -> LOGGER.error("[SafePlots] Could not save an item in grave {}: {}", grave.id(), err))
                        .ifPresent(items::add);
            }
            g.put("items", items);
            list.add(g);
        }
        root.put("graves", list);
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("[SafePlots] Failed to save {}", file, e);
        }
    }
}
