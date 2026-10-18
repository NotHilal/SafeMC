package dev.safemc.safeplots.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import dev.safemc.safeplots.holo.Holograms;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * The guide book. Everyone in the hub (when it isn't the main world) always has it in their first hotbar slot: it
 * can't be dropped or put away, and what was in that slot moves to a free one. Its text is in
 * {@code config/safeplots-guide.txt}; /guide reload reads it again. /guide gives a copy anywhere else.
 */
public final class Guide {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MARK = "safeplots_guide";
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("safeplots-guide.txt");

    private static final String DEFAULT = """
            # The guide book. Everyone in the hub has it in their first slot.
            # Lines starting with # are ignored. "title:" (max 32 letters) and "author:" are its cover.
            # "name:" is the colored name shown on the item (in the hotbar and when hovering it).
            # A line with only --- starts a new page. A page fits about 14 lines of 19 letters.
            # Colors like holograms: &0 black (the normal book color), &1 dark blue, &2 dark green, &4 dark red,
            # &5 purple, &6 gold, &9 blue, &l bold, &o italic, &r back to normal. After editing, run /guide reload.
            title: Papatya's Server
            author: Papatya
            name: &f&lGuide &e&lPapatya's &f&lServer
            ---
            &6&lWelcome to
            &6&lPapatya's Server!

            &0Walk into a portal
            to change world.

            &2/hub &0back here
            &2/ppt help &0all
            commands
            ---
            &9&lHub

            &0Parkour: finish it
            for &13 diamonds&0,
            once every 24h!

            &2/tpa <player>
            &0ask to go to them
            &2/tpaccept
            &0accept a request
            ---
            &2&lSurvival

            &2/plot claimhere
            &0protects your
            house.
            &2/sethome&0, &2/home
            &0up to 5 homes
            &2/grave list
            &0your items wait
            where you died.
            ---
            &5&lCreative

            &2/plot claimhere
            &0then &2/plot confirm
            &0to get your plot.
            &2/plot list
            &2/plot trust <name>

            &0Each world has its
            own inventory.
            ---
            &4&lMinigames

            &0BedWars, TNT Run
            and Guess the Build.

            Right-click a sign
            to join.
            &2/ff &0leave
            Your items come
            back after.
            """;

    private static ItemStack book = ItemStack.EMPTY;

    private Guide() {}

    public static void start() {
        try {
            load();
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[SafePlots] Could not read {}", FILE, e);
        }
    }

    private static void load() throws IOException {
        if (!Files.exists(FILE)) {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, DEFAULT, StandardCharsets.UTF_8);
        }
        String title = "Guide";
        String author = "Server";
        String name = null;
        List<Filterable<Component>> pages = new ArrayList<>();
        List<String> page = null;
        for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) {
                continue;
            }
            if (page == null && line.startsWith("title:")) {
                title = line.substring(6).strip();
            } else if (page == null && line.startsWith("author:")) {
                author = line.substring(7).strip();
            } else if (page == null && line.startsWith("name:")) {
                name = line.substring(5).strip();
            } else if (line.strip().equals("---")) {
                if (page != null) {
                    pages.add(Filterable.passThrough(Holograms.text(page)));
                }
                page = new ArrayList<>();
            } else if (page != null) {
                page.add(line);
            }
        }
        if (page != null) {
            pages.add(Filterable.passThrough(Holograms.text(page)));
        }
        if (title.length() > WrittenBookContent.TITLE_MAX_LENGTH) {
            title = title.substring(0, WrittenBookContent.TITLE_MAX_LENGTH);
        }
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), author, 0, pages, true));
        if (name != null && !name.isEmpty()) {
            // Not italic, as item names are once renamed.
            stack.set(DataComponents.CUSTOM_NAME, Component.empty().withStyle(style -> style.withItalic(false)).append(Holograms.colored(name)));
        }
        CompoundTag mark = new CompoundTag();
        mark.putBoolean(MARK, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(mark));
        book = stack;
    }

    public static ItemStack book() {
        return book.copy();
    }

    public static boolean isGuide(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(MARK);
    }

    // ---------------------------------------------------------------- keeping it in the first slot

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Worlds worlds = Worlds.get();
        if (worlds == null || book.isEmpty() || event.getServer().getTickCount() % 5 != 0) {
            return;
        }
        ServerLevel hub = worlds.levelByName(worlds.hub());
        if (hub == null || !Worlds.protectedHub(hub)) {
            return;
        }
        for (ServerPlayer player : hub.players()) {
            if (!player.isSpectator()) {
                keep(player);
            }
        }
    }

    /** Puts the current guide in slot 1 and removes every other copy the player has hold of. */
    public static void keep(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 1; i < inventory.getContainerSize(); i++) {
            if (isGuide(inventory.getItem(i))) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (isGuide(menu.getCarried())) {
            menu.setCarried(ItemStack.EMPTY);
        }
        for (Slot slot : menu.slots) {
            if (slot.container != inventory && isGuide(slot.getItem())) {
                slot.set(ItemStack.EMPTY); // put in a chest
            }
        }
        ItemStack first = inventory.getItem(0);
        if (ItemStack.isSameItemSameComponents(first, book)) {
            return;
        }
        inventory.setItem(0, book());
        if (!first.isEmpty() && !isGuide(first) && !inventory.add(first)) {
            player.drop(first, false, Prediction.SERVER_ONLY);
        }
        menu.broadcastChanges();
    }

    /** A guide on the ground (thrown, or a player died with it) disappears; the hub gives a new one. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity item && isGuide(item.getItem())) {
            event.setCanceled(true);
        }
    }

    // ---------------------------------------------------------------- /guide

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("guide")
                .executes(Guide::give)
                .then(Commands.literal("reload").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(Guide::reload)));
    }

    private static int give(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (book.isEmpty()) {
            c.getSource().sendFailure(Component.literal("The guide couldn't be read. Ask an admin to check config/safeplots-guide.txt."));
            return 0;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (isGuide(inventory.getItem(i))) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        if (Worlds.protectedHub(player.level())) {
            keep(player);
        } else if (!inventory.add(book())) {
            c.getSource().sendFailure(Component.literal("Your inventory is full. Make room and try again."));
            return 0;
        }
        MutableComponent msg = Component.literal("Here's the guide book. Right-click it to read.").withStyle(ChatFormatting.GREEN);
        c.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        try {
            load();
        } catch (IOException | RuntimeException e) {
            c.getSource().sendFailure(Component.literal("Couldn't read " + FILE + ": " + e.getMessage()));
            return 0;
        }
        int pages = book.get(DataComponents.WRITTEN_BOOK_CONTENT).pages().size();
        c.getSource().sendSuccess(() -> Component.literal("✓ Guide reloaded (" + pages + " pages). Players in the hub get the new one right away.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }
}
