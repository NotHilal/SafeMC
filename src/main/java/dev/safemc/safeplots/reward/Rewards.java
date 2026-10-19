package dev.safemc.safeplots.reward;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.safemc.safeplots.inventory.WorldInventories;
import dev.safemc.safeplots.world.Worlds;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.server.network.Filterable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Winner Chests: winning a minigame (SafeBedwars, SafeTntRun, SafeGuessBuild, SafeHideSeek run {@code /rewards win}; SafeKitPvP on a 10 kill streak) puts one into the
 * player's inventory of the reward world, at most a few a day. Right-clicking one there spins a wheel that lands on a
 * prize. Prizes, chances, the world and the daily limit are in {@code config/safeplots-rewards.txt}; who got how many
 * today and chests still waiting for room are in {@code <world>/safeplots-rewards.json}.
 */
public final class Rewards {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG = FMLPaths.CONFIGDIR.get().resolve("safeplots-rewards.txt");
    private static final String MARK = "safeplots_winner_chest";
    private static final String DEFAULT_CONFIG = """
            # Winner Chests: winning a minigame (BedWars, TNT Run, Guess the Build) gives one.
            # After editing, run /rewards reload.
            #
            # The /mv world (or world, nether, end) whose inventory gets the chests. They can only be opened there.
            world: world
            # How many chests a player can get per day (resets at midnight, server time).
            daily: 2
            #
            # The wheel: chance (weight)  item  count  [for books: enchantments, one is picked at random]
            # The chance of a prize is its weight divided by all weights together (here they add up to 100, so %).
            # papatya_book is the rarest reward: a book signed by Papatya with the winner's name, to bring to her.
            16 coal 25
            15 cooked_beef 15
            14 iron_ingot 20
            12 experience_bottle 12
            10 golden_apple 5
            6 diamond 6
            6 enchanted_book 1 efficiency 4 | fortune 3 | protection 4 | mending 1
            5 totem_of_undying 1
            5 netherite_ingot 1
            5 netherite_upgrade_smithing_template 1
            5 enchanted_golden_apple 2
            1 papatya_book 1
            """;
    /** The wheel slows down: ticks between steps. About 3 seconds in all. */
    private static final int[] STEPS = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 5, 6, 7, 9};
    /** The middle slot of the wheel row; the prize stops here. */
    private static final int CENTER = 13;

    public record Option(Holder<Enchantment> enchantment, int level) {}

    /** The config name of the rarest reward. */
    public static final String RAREST = "papatya_book";

    /** {@code rarest}: Papatya's signed book instead of a normal item. */
    public record Prize(int weight, Item item, int count, List<Option> options, boolean rarest) {
        ItemStack create(RandomSource random) {
            return create(random, null);
        }

        /** With {@code winner}, the rarest reward's book has their name in it. */
        ItemStack create(RandomSource random, @Nullable String winner) {
            if (rarest) {
                return rarestBook(winner);
            }
            ItemStack stack = new ItemStack(item, count);
            if (!options.isEmpty()) {
                Option o = options.get(random.nextInt(options.size()));
                EnchantmentHelper.updateEnchantments(stack, m -> m.set(o.enchantment(), o.level()));
            }
            return stack;
        }

        public String describe() {
            if (rarest) {
                return "Papatya's signed book (the rarest reward)";
            }
            String name = count + " " + item.getName(new ItemStack(item)).getString();
            if (options.isEmpty()) {
                return name;
            }
            List<String> books = options.stream().map(o -> Enchantment.getFullname(o.enchantment(), o.level()).getString()).toList();
            return name + " (" + String.join(" / ", books) + ")";
        }
    }

    /**
     * The rarest reward: a book signed by Papatya telling the winner to come and give it to her. Vanilla books are
     * signed with the writer's own name, so players can't make one with her name on it.
     */
    public static ItemStack rarestBook(@Nullable String winner) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        Component page = Component.literal("★ The Rarest Reward ★\n\n").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal((winner == null ? "Congratulations" : "Congratulations " + winner) + "!\n\n")
                        .withStyle(s -> s.withColor(ChatFormatting.DARK_PURPLE).withBold(false)))
                .append(Component.literal("You got the rarest reward of all the Winner Chests.\n\n"
                        + "Come find me and give this book to me!\n\n").withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(false)))
                .append(Component.literal("- Papatya").withStyle(s -> s.withColor(ChatFormatting.DARK_RED).withBold(false).withItalic(true)));
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("The Rarest Reward"), "Papatya", 0,
                List.of(Filterable.passThrough(page)), true));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Papatya's Rarest Reward")
                .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true).withItalic(false)));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    private static final class Day {
        LocalDate day = LocalDate.MIN;
        int today;
        int pending;
    }

    private static @Nullable Rewards instance;

    private final MinecraftServer server;
    private final Path file;
    private final Map<UUID, Day> days = new HashMap<>();
    private final Map<UUID, Spin> spins = new HashMap<>();
    private final RandomSource random = RandomSource.create();
    private String world = "world";
    private int daily = 2;
    private List<Prize> prizes = List.of();

    private Rewards(MinecraftServer server, Path file) {
        this.server = server;
        this.file = file;
    }

    public static @Nullable Rewards get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        Rewards manager = new Rewards(server, server.getWorldPath(LevelResource.ROOT).resolve("safeplots-rewards.json").normalize());
        try {
            manager.load();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("[SafePlots] Could not read " + manager.file + ". Fix or restore the file.", e);
        }
        String problem = manager.reload();
        if (problem != null) {
            LOGGER.error("[SafePlots] {}: {}", CONFIG, problem);
        }
        instance = manager;
    }

    /** Before the worlds go: wheels still spinning give their prize now. */
    public static void stop() {
        Rewards manager = instance;
        if (manager != null) {
            new ArrayList<>(manager.spins.values()).forEach(Spin::finish);
        }
        instance = null;
    }

    public String world() {
        return world;
    }

    public int daily() {
        return daily;
    }

    public List<Prize> prizes() {
        return prizes;
    }

    public int totalWeight() {
        return prizes.stream().mapToInt(Prize::weight).sum();
    }

    public int today(UUID id) {
        Day d = days.get(id);
        return d == null || !d.day.equals(LocalDate.now()) ? 0 : d.today;
    }

    public int pending(UUID id) {
        Day d = days.get(id);
        return d == null ? 0 : d.pending;
    }

    // ---------------------------------------------------------------- config

    /** Reads the config file again (writing the default one first if there is none). Null if fine, else what's wrong. */
    public @Nullable String reload() {
        List<String> lines;
        try {
            if (!Files.exists(CONFIG)) {
                Files.createDirectories(CONFIG.getParent());
                Files.writeString(CONFIG, DEFAULT_CONFIG, StandardCharsets.UTF_8);
            }
            lines = Files.readAllLines(CONFIG, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "could not read the file: " + e.getMessage();
        }
        String newWorld = world;
        int newDaily = daily;
        List<Prize> list = new ArrayList<>();
        var enchantments = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String where = "line " + (n + 1) + " (" + line + "): ";
            if (line.startsWith("world:")) {
                newWorld = line.substring(6).strip();
                continue;
            }
            if (line.startsWith("daily:")) {
                try {
                    newDaily = Math.max(0, Integer.parseInt(line.substring(6).strip()));
                } catch (NumberFormatException e) {
                    return where + "daily must be a number";
                }
                continue;
            }
            String[] bar = line.split("\\|");
            String[] head = bar[0].strip().split("\\s+");
            if (head.length != 3 && head.length != 5) {
                return where + "expected: weight item count";
            }
            int weight;
            int count;
            try {
                weight = Integer.parseInt(head[0]);
                count = Integer.parseInt(head[2]);
            } catch (NumberFormatException e) {
                return where + "weight and count must be numbers";
            }
            if (head[1].equals(RAREST)) {
                if (head.length != 3 || bar.length > 1 || count != 1 || weight <= 0) {
                    return where + "write it like: 1 " + RAREST + " 1";
                }
                list.add(new Prize(weight, Items.WRITTEN_BOOK, 1, List.of(), true));
                continue;
            }
            Identifier itemId = Identifier.tryParse(head[1]);
            Item item = itemId == null ? null : BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
            if (item == null || item == Items.AIR) {
                return where + "unknown item " + head[1];
            }
            if (weight <= 0 || count <= 0 || count > item.getDefaultMaxStackSize()) {
                return where + "weight must be above 0 and count 1 to " + item.getDefaultMaxStackSize();
            }
            List<Option> options = new ArrayList<>();
            List<String> enchantTexts = new ArrayList<>();
            if (head.length == 5) {
                enchantTexts.add(head[3] + " " + head[4]);
            }
            for (int i = 1; i < bar.length; i++) {
                enchantTexts.add(bar[i].strip());
            }
            for (String text : enchantTexts) {
                String[] parts = text.split("\\s+");
                Identifier id = parts.length == 2 ? Identifier.tryParse(parts[0]) : null;
                var holder = id == null ? null : enchantments.get(id).orElse(null);
                if (holder == null) {
                    return where + "unknown enchantment " + text + " (write it like: efficiency 4)";
                }
                try {
                    options.add(new Option(holder, Integer.parseInt(parts[1])));
                } catch (NumberFormatException e) {
                    return where + "the enchantment level must be a number";
                }
            }
            list.add(new Prize(weight, item, count, List.copyOf(options), false));
        }
        if (list.isEmpty()) {
            return "no prizes in the file";
        }
        world = newWorld;
        daily = newDaily;
        prizes = List.copyOf(list);
        return null;
    }

    /** The reward world, or null if it isn't loaded (or doesn't exist). */
    public @Nullable ServerLevel level() {
        Worlds worlds = Worlds.get();
        return worlds == null ? null : worlds.levelByName(world);
    }

    private @Nullable String group() {
        ServerLevel level = level();
        return level == null ? null : Worlds.get().inventoryGroup(level);
    }

    // ---------------------------------------------------------------- the chest item

    public static ItemStack chest(int count) {
        ItemStack stack = new ItemStack(Items.CHEST, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Winner Chest")
                .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD).withBold(true)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Right-click to spin the wheel!").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.YELLOW)),
                Component.literal("Won in a minigame").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        CompoundTag mark = new CompoundTag();
        mark.putBoolean(MARK, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(mark));
        return stack;
    }

    public static boolean isChest(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(MARK);
    }

    // ---------------------------------------------------------------- earning

    /** A minigame win: one chest if they're under today's limit. */
    public void win(ServerPlayer player) {
        Day d = day(player.getUUID());
        if (d.today >= daily) {
            player.sendSystemMessage(Component.literal("Daily chest limit reached (" + daily + "/" + daily + "). Come back tomorrow!")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        d.today++;
        String counter = " (" + d.today + "/" + daily + " today)";
        if (deliver(player, 1)) {
            player.sendSystemMessage(Component.literal("+1 Winner Chest added to your " + world + " inventory!" + counter)
                    .withStyle(ChatFormatting.GOLD));
        } else {
            d.pending++;
            player.sendSystemMessage(Component.literal("You won a Winner Chest!" + counter + " Your " + world
                    + " inventory is full: it's given as soon as there is room.").withStyle(ChatFormatting.GOLD));
        }
        save();
    }

    /** Chests from an admin: no daily limit. */
    public void give(ServerPlayer player, int count) {
        Day d = day(player.getUUID());
        if (deliver(player, count)) {
            player.sendSystemMessage(Component.literal("+" + count + " Winner Chest" + (count == 1 ? "" : "s") + " added to your "
                    + world + " inventory!").withStyle(ChatFormatting.GOLD));
        } else {
            d.pending += count;
            player.sendSystemMessage(Component.literal("You got " + count + " Winner Chest" + (count == 1 ? "" : "s") + "! Your "
                    + world + " inventory is full: they're given as soon as there is room.").withStyle(ChatFormatting.GOLD));
        }
        save();
    }

    private boolean deliver(ServerPlayer player, int count) {
        String group = group();
        WorldInventories inventories = WorldInventories.get();
        return group != null && inventories != null && inventories.give(player, group, chest(count));
    }

    private Day day(UUID id) {
        Day d = days.computeIfAbsent(id, k -> new Day());
        LocalDate now = LocalDate.now();
        if (!d.day.equals(now)) {
            d.day = now;
            d.today = 0;
        }
        return d;
    }

    /** Forgets today's chests and waiting ones (for tests). */
    public void forget(UUID id) {
        days.remove(id);
        save();
    }

    /** Chests that didn't fit: tried again every 5 seconds for everyone online. */
    public void deliverPending() {
        boolean changed = false;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Day d = days.get(p.getUUID());
            if (d != null && d.pending > 0 && deliver(p, d.pending)) {
                p.sendSystemMessage(Component.literal("+" + d.pending + " Winner Chest" + (d.pending == 1 ? "" : "s")
                        + " added to your " + world + " inventory!").withStyle(ChatFormatting.GOLD));
                d.pending = 0;
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    // ---------------------------------------------------------------- the wheel

    /** The index of the prize won: each prize's chance is its weight. */
    private int roll() {
        int pick = random.nextInt(totalWeight());
        for (int i = 0; i < prizes.size(); i++) {
            pick -= prizes.get(i).weight();
            if (pick < 0) {
                return i;
            }
        }
        return prizes.size() - 1;
    }

    /** Right-click with a chest: in the reward world it spins the wheel, anywhere else it says where to go. */
    public boolean open(ServerPlayer player, ItemStack held) {
        if (!isChest(held)) {
            return false;
        }
        if (spins.containsKey(player.getUUID())) {
            return true;
        }
        String group = group();
        WorldInventories inventories = WorldInventories.get();
        if (group == null || inventories == null || !group.equals(inventories.currentGroup(player)) || prizes.isEmpty()) {
            player.sendSystemMessage(Component.literal("Open your Winner Chest in " + world + ".").withStyle(ChatFormatting.YELLOW));
            return true;
        }
        held.shrink(1);
        int won = roll();
        Spin spin = new Spin(player, won, prizes.get(won).create(random, player.getGameProfile().name()));
        spins.put(player.getUUID(), spin);
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new WheelMenu(id, inventory, spin),
                Component.literal("Winner Chest").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
        return true;
    }

    public boolean spinning(UUID id) {
        return spins.containsKey(id);
    }

    /** One server tick of every wheel. */
    public void tickSpins() {
        new ArrayList<>(spins.values()).forEach(Spin::tick);
    }

    private final class Spin {
        final ServerPlayer player;
        final ItemStack prize;
        final SimpleContainer container = new SimpleContainer(27);
        final List<ItemStack> reel = new ArrayList<>();
        int step;
        int wait;
        boolean given;
        int closeIn = -1;

        Spin(ServerPlayer player, int won, ItemStack prize) {
            this.player = player;
            this.prize = prize;
            // The prizes go by in the order of the file, each once per turn of the wheel, so every prize is seen the
            // same number of times; the chances only decide where it stops. It's turned so the prize won ends up in
            // the middle slot on the last step.
            int landing = STEPS.length + 4, n = prizes.size();
            int start = Math.floorMod(won - landing, n);
            for (int i = 0; i < STEPS.length + 9; i++) {
                reel.add(i == landing ? prize.copy() : prizes.get((start + i) % n).create(random));
            }
            ItemStack frame = pane(Items.STAINED_GLASS_PANE.pick(DyeColor.GRAY));
            for (int i = 0; i < 27; i++) {
                container.setItem(i, frame.copy());
            }
            container.setItem(CENTER - 9, pane(Items.STAINED_GLASS_PANE.pick(DyeColor.YELLOW)));
            container.setItem(CENTER + 9, pane(Items.STAINED_GLASS_PANE.pick(DyeColor.YELLOW)));
            show();
        }

        private void show() {
            for (int i = 0; i < 9; i++) {
                container.setItem(9 + i, reel.get(step + i).copy());
            }
        }

        void tick() {
            if (given) {
                if (closeIn > 0 && --closeIn == 0 && player.containerMenu instanceof WheelMenu menu && menu.spin == this) {
                    player.closeContainer();
                }
                return;
            }
            if (wait-- > 0) {
                return;
            }
            if (step == STEPS.length) {
                finish();
                return;
            }
            wait = STEPS[step] - 1;
            step++;
            show();
            player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.6F, 1.6F);
        }

        /** The prize goes into the inventory (or at their feet if it's full). Once. */
        void finish() {
            if (given) {
                return;
            }
            given = true;
            closeIn = 40;
            for (int i = 0; i < 9; i++) {
                if (9 + i != CENTER) {
                    container.setItem(9 + i, pane(Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK)));
                }
            }
            container.setItem(CENTER, prize.copy());
            container.setItem(CENTER - 9, pane(Items.STAINED_GLASS_PANE.pick(DyeColor.LIME)));
            container.setItem(CENTER + 9, pane(Items.STAINED_GLASS_PANE.pick(DyeColor.LIME)));
            ItemStack stack = prize.copy();
            if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                player.drop(stack, false, Prediction.SERVER_ONLY);
            }
            player.inventoryMenu.broadcastChanges();
            player.sendSystemMessage(Component.literal("You won ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(prize.getCount() + "x ").withStyle(ChatFormatting.GOLD))
                    .append(prize.getHoverName().copy().withStyle(ChatFormatting.GOLD))
                    .append(Component.literal("!").withStyle(ChatFormatting.YELLOW)));
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
            spins.remove(player.getUUID());
        }
    }

    private static ItemStack pane(Item item) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        return stack;
    }

    /** The wheel window: nothing can be taken out or put in. Closing it early still gives the prize. */
    private static final class WheelMenu extends ChestMenu {
        final Spin spin;

        WheelMenu(int id, Inventory inventory, Spin spin) {
            super(MenuType.GENERIC_9x3, id, inventory, spin.container, 3);
            this.spin = spin;
        }

        @Override
        public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
            sendAllDataToRemote(); // undo whatever the client already moved
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slotIndex) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            spin.finish();
        }
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickItem event) {
        Rewards manager = instance;
        if (manager != null && event.getEntity() instanceof ServerPlayer player && isChest(event.getItemStack())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            manager.open(player, event.getItemStack());
        }
    }

    /** Clicking a block with it: the same, and it's never placed as a chest. */
    @SubscribeEvent
    public static void onUseOnBlock(PlayerInteractEvent.RightClickBlock event) {
        if (isChest(event.getItemStack())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            Rewards manager = instance;
            if (manager != null && event.getEntity() instanceof ServerPlayer player) {
                manager.open(player, event.getItemStack());
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Rewards manager = instance;
        if (manager == null) {
            return;
        }
        manager.tickSpins();
        if (event.getServer().getTickCount() % 100 == 0) {
            manager.deliverPending();
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Rewards manager = instance;
        if (manager != null && manager.spins.get(event.getEntity().getUUID()) instanceof Spin spin) {
            spin.finish();
        }
    }

    // ---------------------------------------------------------------- file

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Day d = new Day();
            d.day = LocalDate.parse(o.get("day").getAsString());
            d.today = o.get("today").getAsInt();
            d.pending = o.has("pending") ? o.get("pending").getAsInt() : 0;
            days.put(UUID.fromString(e.getKey()), d);
        }
    }

    private void save() {
        LocalDate now = LocalDate.now();
        JsonObject players = new JsonObject();
        for (Iterator<Map.Entry<UUID, Day>> it = new TreeMap<>(days).entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Day> e = it.next();
            Day d = e.getValue();
            if (!d.day.equals(now) && d.pending == 0) {
                days.remove(e.getKey()); // nothing worth keeping from an earlier day
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("day", d.day.toString());
            o.addProperty("today", d.today);
            o.addProperty("pending", d.pending);
            players.add(e.getKey().toString(), o);
        }
        JsonObject root = new JsonObject();
        root.add("players", players);
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("[SafePlots] Could not save {}", file, e);
        }
    }
}
