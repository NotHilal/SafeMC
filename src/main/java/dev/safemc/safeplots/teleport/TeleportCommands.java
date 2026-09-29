package dev.safemc.safeplots.teleport;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.safemc.safeplots.moderation.Vanish;
import dev.safemc.safeplots.plot.PlotManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** /sethome, /home, /delhome (one home per player), /tpa, /tpahere, /tpaccept, /tpdeny. */
public final class TeleportCommands {
    private TeleportCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("sethome").executes(TeleportCommands::setHome));
        d.register(Commands.literal("home").executes(TeleportCommands::home));
        d.register(Commands.literal("delhome").executes(TeleportCommands::delHome));

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

    // ---------------------------------------------------------------- home (one per player)

    private static int setHome(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        boolean replaced = homes().setHome(player.getUUID(), new HomeManager.Home(PlotManager.dimensionId(player.level()),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        return ok(c, (replaced ? "Home moved here." : "Home set.") + " Use /home to come back.");
    }

    private static int home(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        HomeManager.Home home = homes().home(player.getUUID());
        if (home == null) {
            return fail(c, "You don't have a home yet. Set it with /sethome.");
        }
        return Teleports.start(player, "your home", () -> {
            ServerLevel level = PlotManager.get() == null ? null : PlotManager.get().level(home.dimension());
            return level == null ? null : new Teleports.Destination(level, new Vec3(home.x(), home.y(), home.z()), home.yRot(), home.xRot());
        }) ? 1 : 0;
    }

    private static int delHome(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        return homes().deleteHome(player.getUUID()) ? ok(c, "Home deleted.") : fail(c, "You don't have a home.");
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
