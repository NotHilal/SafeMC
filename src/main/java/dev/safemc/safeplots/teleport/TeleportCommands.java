package dev.safemc.safeplots.teleport;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.safemc.safeplots.moderation.Vanish;
import dev.safemc.safeplots.plot.PlotManager;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** /sethome, /home, /delhome, /homes (up to 5 named homes per player), /tpa, /tpahere, /tpaccept, /tpdeny. */
public final class TeleportCommands {
    private TeleportCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("sethome").executes(c -> setHome(c, null))
                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> setHome(c, StringArgumentType.getString(c, "name")))));
        d.register(Commands.literal("home").executes(c -> home(c, null))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(TeleportCommands::suggestHomes)
                        .executes(c -> home(c, StringArgumentType.getString(c, "name")))));
        d.register(Commands.literal("delhome").executes(c -> delHome(c, null))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(TeleportCommands::suggestHomes)
                        .executes(c -> delHome(c, StringArgumentType.getString(c, "name")))));
        d.register(Commands.literal("homes").executes(TeleportCommands::listHomes));

        d.register(Commands.literal("tpa")
                .then(Commands.argument("player", EntityArgument.player()).executes(c -> request(c, false))));
        d.register(Commands.literal("tpahere")
                .then(Commands.argument("player", EntityArgument.player()).executes(c -> request(c, true))));
        d.register(Commands.literal("tpaccept")
                .executes(c -> answer(c, true, false))
                .then(Commands.argument("player", EntityArgument.player()).executes(c -> answer(c, true, true))));
        d.register(Commands.literal("tpdeny")
                .executes(c -> answer(c, false, false))
                .then(Commands.argument("player", EntityArgument.player()).executes(c -> answer(c, false, true))));
    }

    // ---------------------------------------------------------------- homes (up to 5 per player)

    private static int setHome(CommandContext<CommandSourceStack> c, @Nullable String given) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        String name = given == null ? HomeManager.DEFAULT_NAME : HomeManager.normalize(given);
        if (name == null) {
            return fail(c, "Home names are 1-16 letters, digits, _ or -.");
        }
        HomeManager.SetResult result = homes().setHome(player.getUUID(), name, new HomeManager.Home(PlotManager.dimensionId(player.level()),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        String back = name.equals(HomeManager.DEFAULT_NAME) ? "/home" : "/home " + name;
        return switch (result) {
            case SET -> ok(c, "Home \"" + name + "\" set (" + homes().homes(player.getUUID()).size() + "/" + HomeManager.MAX_HOMES
                    + "). Use " + back + " to come back.");
            case MOVED -> ok(c, "Home \"" + name + "\" moved here. Use " + back + " to come back.");
            case LIMIT -> fail(c, "You already have " + HomeManager.MAX_HOMES + " homes (" + names(player)
                    + "). Move one with /sethome <name> or delete one with /delhome <name>.");
        };
    }

    private static int home(CommandContext<CommandSourceStack> c, @Nullable String given) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        String name = resolve(c, player, given, "home");
        if (name == null) {
            return 0;
        }
        HomeManager.Home home = homes().home(player.getUUID(), name);
        return Teleports.start(player, name.equals(HomeManager.DEFAULT_NAME) ? "your home" : "home " + name, () -> {
            ServerLevel level = PlotManager.get() == null ? null : PlotManager.get().level(home.dimension());
            return level == null ? null : new Teleports.Destination(level, new Vec3(home.x(), home.y(), home.z()), home.yRot(), home.xRot());
        }) ? 1 : 0;
    }

    private static int delHome(CommandContext<CommandSourceStack> c, @Nullable String given) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        String name = resolve(c, player, given, "delhome");
        if (name == null) {
            return 0;
        }
        homes().deleteHome(player.getUUID(), name);
        return ok(c, "Home \"" + name + "\" deleted.");
    }

    private static int listHomes(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Map<String, HomeManager.Home> mine = homes().homes(player.getUUID());
        if (mine.isEmpty()) {
            return fail(c, "You don't have a home yet. Set one with /sethome [name].");
        }
        MutableComponent msg = Component.literal("Your homes (" + mine.size() + "/" + HomeManager.MAX_HOMES + "): ").withStyle(ChatFormatting.GREEN);
        boolean first = true;
        for (Map.Entry<String, HomeManager.Home> e : mine.entrySet()) {
            if (!first) {
                msg.append(Component.literal(", ").withStyle(ChatFormatting.GREEN));
            }
            first = false;
            HomeManager.Home h = e.getValue();
            String where = h.dimension() + " " + (int) Math.floor(h.x()) + " " + (int) Math.floor(h.y()) + " " + (int) Math.floor(h.z());
            msg.append(Component.literal(e.getKey()).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new ClickEvent.RunCommand("/home " + e.getKey()))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(where + "\nClick to go there")))));
        }
        c.getSource().sendSuccess(() -> msg, false);
        return mine.size();
    }

    /**
     * The home a command means: the given name, or with no name "home", or the player's only home.
     * Sends the error and returns null if there is no such home.
     */
    private static @Nullable String resolve(CommandContext<CommandSourceStack> c, ServerPlayer player, @Nullable String given, String command) {
        Map<String, HomeManager.Home> mine = homes().homes(player.getUUID());
        if (mine.isEmpty()) {
            fail(c, "You don't have a home yet. Set one with /sethome [name].");
            return null;
        }
        if (given != null) {
            String name = HomeManager.normalize(given);
            if (name == null || !mine.containsKey(name)) {
                fail(c, "You don't have a home called \"" + given + "\". Your homes: " + names(player) + ".");
                return null;
            }
            return name;
        }
        if (mine.containsKey(HomeManager.DEFAULT_NAME)) {
            return HomeManager.DEFAULT_NAME;
        }
        if (mine.size() == 1) {
            return mine.keySet().iterator().next();
        }
        fail(c, "Which one? Your homes: " + names(player) + ". Add the name, e.g. /" + command + " " + mine.keySet().iterator().next() + ".");
        return null;
    }

    private static String names(ServerPlayer player) {
        return String.join(", ", homes().homes(player.getUUID()).keySet());
    }

    private static CompletableFuture<Suggestions> suggestHomes(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        ServerPlayer player = c.getSource().getPlayer();
        HomeManager manager = HomeManager.get();
        if (player == null || manager == null) {
            return b.buildFuture();
        }
        return SharedSuggestionProvider.suggest(manager.homes(player.getUUID()).keySet(), b);
    }

    // ---------------------------------------------------------------- tpa

    private static int request(CommandContext<CommandSourceStack> c, boolean here) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(c, "player");
        if (target == player) {
            return fail(c, "You can't send a request to yourself.");
        }
        if (Vanish.hiddenFrom(target, player)) {
            return fail(c, "No player was found"); // same message as for an offline player
        }
        Teleports.addRequest(new Teleports.Request(player.getUUID(), player.getGameProfile().name(), target.getUUID(), here, System.currentTimeMillis()));
        String name = player.getGameProfile().name();
        MutableComponent msg = Component.literal(here ? name + " wants you to teleport to them. " : name + " wants to teleport to you. ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("[Accept]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/tpaccept " + name))))
                .append(Component.literal(" "))
                .append(Component.literal("[Deny]").withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/tpdeny " + name))))
                .append(Component.literal(" (expires in " + Teleports.REQUEST_TIMEOUT_MS / 1000 + "s)").withStyle(ChatFormatting.GRAY));
        target.sendSystemMessage(msg);
        return ok(c, "Request sent to " + target.getGameProfile().name() + ".");
    }

    private static int answer(CommandContext<CommandSourceStack> c, boolean accept, boolean named) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ServerPlayer from = named ? EntityArgument.getPlayer(c, "player") : null;
        Teleports.Request request = Teleports.takeRequest(player.getUUID(), from == null ? null : from.getUUID());
        if (request == null) {
            return fail(c, "You have no pending teleport request" + (from == null ? "." : " from " + from.getGameProfile().name() + "."));
        }
        ServerPlayer requester = c.getSource().getServer().getPlayerList().getPlayer(request.from());
        if (requester == null) {
            return fail(c, request.fromName() + " is no longer online.");
        }
        String myName = player.getGameProfile().name();
        if (!accept) {
            requester.sendSystemMessage(Component.literal(myName + " denied your teleport request.").withStyle(ChatFormatting.RED));
            return ok(c, "Request denied.");
        }
        requester.sendSystemMessage(Component.literal(myName + " accepted your teleport request.").withStyle(ChatFormatting.GREEN));
        // The one who moves goes to the other's position at the end of the warm-up.
        ServerPlayer mover = request.here() ? player : requester;
        ServerPlayer anchor = request.here() ? requester : player;
        var server = c.getSource().getServer();
        Teleports.start(mover, anchor.getGameProfile().name(), () -> {
            ServerPlayer now = server.getPlayerList().getPlayer(anchor.getUUID());
            return now == null ? null : Teleports.destinationOf(now);
        });
        return ok(c, "Request accepted.");
    }

    // ---------------------------------------------------------------- helpers

    private static HomeManager homes() {
        HomeManager homes = HomeManager.get();
        if (homes == null) {
            throw new IllegalStateException("SafePlots is not running");
        }
        return homes;
    }

    private static int ok(CommandContext<CommandSourceStack> c, String message) {
        c.getSource().sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> c, String message) {
        c.getSource().sendFailure(Component.literal(message));
        return 0;
    }
}
