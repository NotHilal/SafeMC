package dev.safemc.safeplots.moderation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * AFK: {@code /afk}, or by itself after a few minutes of doing nothing ({@code /afk timeout}). An AFK player's name is
 * gray with [AFK] in the tab list, and everyone gets a gray line in chat when it starts and ends. It ends when they move,
 * look around, chat, use a command or click; after {@code /afk} moving only counts after 3 seconds, so they can stop
 * walking first. Players in a minigame (tagged {@link #IN_GAME} by the minigame mods) don't go AFK by themselves, and
 * vanished admins never show as AFK. The timeout is saved in {@code <world>/safeplots-afk.json}.
 */
public final class Afk {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The minigame mods put this tag on players while they're in a game. */
    public static final String IN_GAME = "safemc_ingame";
    private static final int DEFAULT_TIMEOUT_MINUTES = 5;
    /** After /afk, moving only ends it after this long. */
    private static final int GRACE_TICKS = 3 * 20;

    private static final class State {
        long lastActive;
        long graceUntil;
        boolean afk;
        @Nullable Vec3 pos;
        float yRot;
        float xRot;
    }

    private static @Nullable Afk instance;

    private final MinecraftServer server;
    private final Path file;
    private final Map<UUID, State> states = new HashMap<>();
    private int timeoutMinutes = DEFAULT_TIMEOUT_MINUTES;

    private Afk(MinecraftServer server, Path file) {
        this.server = server;
        this.file = file;
    }

    public static void start(MinecraftServer server) {
        Afk afk = new Afk(server, server.getWorldPath(LevelResource.ROOT).resolve("safeplots-afk.json").normalize());
        try {
            if (Files.exists(afk.file)) {
                try (Reader reader = Files.newBufferedReader(afk.file, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("timeoutMinutes")) {
                        afk.timeoutMinutes = Math.max(0, root.get("timeoutMinutes").getAsInt());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[SafePlots] Could not read {}", afk.file, e);
        }
        instance = afk;
    }

    public static void stop() {
        instance = null;
    }

    public static @Nullable Afk get() {
        return instance;
    }

    public int timeoutMinutes() {
        return timeoutMinutes;
    }

    public boolean isAfk(UUID player) {
        State state = states.get(player);
        return state != null && state.afk;
    }

    private State state(ServerPlayer player) {
        return states.computeIfAbsent(player.getUUID(), id -> {
            State s = new State();
            s.lastActive = server.getTickCount();
            return s;
        });
    }

    private static boolean vanished(ServerPlayer player) {
        ModerationManager manager = ModerationManager.get();
        return manager != null && manager.isVanished(player.getUUID());
    }

    /** Turns AFK on or off, with the chat line (not for vanished admins) and the tab list name. */
    public void set(ServerPlayer player, boolean afk) {
        State state = state(player);
        if (state.afk == afk) {
            return;
        }
        state.afk = afk;
        if (afk) {
            state.graceUntil = server.getTickCount() + GRACE_TICKS;
            state.pos = player.position();
            state.yRot = player.getYRot();
            state.xRot = player.getXRot();
        } else {
            state.lastActive = server.getTickCount();
        }
        player.refreshTabListName();
        if (vanished(player)) {
            return;
        }
        Component line = Component.literal(player.getGameProfile().name() + (afk ? " is now AFK" : " is no longer AFK"))
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (!Vanish.hiddenFrom(player, viewer)) {
                viewer.sendSystemMessage(line);
            }
        }
    }

    /** They did something: the AFK timer starts again, and AFK ends ({@code movedOnly}: not during the grace after /afk). */
    void active(ServerPlayer player, boolean movedOnly) {
        State state = state(player);
        long now = server.getTickCount();
        if (state.afk && movedOnly && now < state.graceUntil) {
            return;
        }
        state.lastActive = now;
        if (state.afk) {
            set(player, false);
        }
    }

    /** For the self-test: as if they did nothing for that long. */
    public void pretendIdle(ServerPlayer player, int minutes) {
        state(player).lastActive = server.getTickCount() - minutes * 60L * 20L;
    }

    /** For the self-test: as if the 3 seconds after /afk were over. */
    public void endGrace(ServerPlayer player) {
        state(player).graceUntil = 0;
    }

    /** Once a second: who moved, and who has done nothing for too long. */
    public void tick() {
        long now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            State state = state(player);
            Vec3 pos = player.position();
            boolean moved = state.pos != null && (pos.distanceToSqr(state.pos) > 0.01
                    || Math.abs(player.getYRot() - state.yRot) > 1 || Math.abs(player.getXRot() - state.xRot) > 1);
            state.pos = pos;
            state.yRot = player.getYRot();
            state.xRot = player.getXRot();
            if (moved) {
                active(player, true);
            }
            if (state.afk && vanished(player)) {
                set(player, false); // vanished admins are never AFK
            } else if (!state.afk && timeoutMinutes > 0 && now - state.lastActive >= timeoutMinutes * 60L * 20L
                    && !player.entityTags().contains(IN_GAME) && !vanished(player)) {
                set(player, true);
            }
        }
    }

    private void setTimeout(int minutes) {
        timeoutMinutes = minutes;
        JsonObject root = new JsonObject();
        root.addProperty("timeoutMinutes", minutes);
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                writer.write(root.toString());
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[SafePlots] Could not save {}", file, e);
        }
    }

    // ---------------------------------------------------------------- command

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("afk")
                .executes(Afk::toggle)
                .then(Commands.literal("timeout").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(Afk::showTimeout)
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(0, 1440)).executes(Afk::changeTimeout))));
    }

    private static int toggle(CommandContext<CommandSourceStack> c) {
        Afk afk = instance;
        if (afk == null || !(c.getSource().getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        if (vanished(player)) {
            c.getSource().sendFailure(Component.literal("You're vanished, so nobody would see you as AFK."));
            return 0;
        }
        afk.set(player, !afk.isAfk(player.getUUID()));
        return 1;
    }

    private static int showTimeout(CommandContext<CommandSourceStack> c) {
        Afk afk = instance;
        if (afk == null) {
            return 0;
        }
        c.getSource().sendSystemMessage(Component.literal(afk.timeoutMinutes == 0
                ? "Nobody goes AFK by themselves (only with /afk). /afk timeout <minutes> turns it on."
                : "Players go AFK after " + afk.timeoutMinutes + " minute" + (afk.timeoutMinutes == 1 ? "" : "s")
                        + " of doing nothing. /afk timeout <minutes> changes it (0: only with /afk).").withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int changeTimeout(CommandContext<CommandSourceStack> c) {
        Afk afk = instance;
        if (afk == null) {
            return 0;
        }
        int minutes = IntegerArgumentType.getInteger(c, "minutes");
        afk.setTimeout(minutes);
        c.getSource().sendSuccess(() -> Component.literal(minutes == 0 ? "✓ Players only go AFK with /afk now."
                : "✓ Players now go AFK after " + minutes + " minute" + (minutes == 1 ? "" : "s") + " of doing nothing.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Afk afk = instance;
        if (afk != null && event.getServer().getTickCount() % 20 == 0) {
            afk.tick();
        }
    }

    /** [AFK] in gray before the name in the tab list. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onTabName(PlayerEvent.TabListNameFormat event) {
        Afk afk = instance;
        if (afk == null || !afk.isAfk(event.getEntity().getUUID())) {
            return;
        }
        Component name = event.getDisplayName() != null ? event.getDisplayName() : event.getEntity().getName();
        event.setDisplayName(Component.literal("[AFK] ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(name.getString()).withStyle(ChatFormatting.GRAY)));
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        Afk afk = instance;
        if (afk != null) {
            afk.active(event.getPlayer(), false);
        }
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        Afk afk = instance;
        if (afk == null || !(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String command = event.getParseResults().getReader().getString().strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (!command.equals("afk") && !command.startsWith("afk ")) {
            afk.active(player, false); // /afk itself turns it on or off
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        clicked(event.getEntity());
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        clicked(event.getEntity());
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        clicked(event.getEntity());
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        clicked(event.getEntity());
    }

    private static void clicked(net.minecraft.world.entity.player.Player player) {
        Afk afk = instance;
        if (afk != null && player instanceof ServerPlayer sp) {
            afk.active(sp, false);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Afk afk = instance;
        if (afk != null) {
            afk.states.remove(event.getEntity().getUUID());
        }
    }
}
