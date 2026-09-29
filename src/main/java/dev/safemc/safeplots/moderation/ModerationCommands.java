package dev.safemc.safeplots.moderation;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import java.util.Date;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanListEntry;
import org.jspecify.annotations.Nullable;

/**
 * /vanish, /freeze, /unfreeze, /mute, /unmute, /tempban. All require op level 2 (same as the /plot admin commands).
 * Temp bans are ordinary vanilla bans with an end date, so /pardon and banned-players.json work as usual.
 */
public final class ModerationCommands {
    private static final Predicate<CommandSourceStack> STAFF = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private ModerationCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("vanish").requires(STAFF).executes(ModerationCommands::vanish));
        d.register(Commands.literal("freeze").requires(STAFF)
                .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(c -> freeze(c, true))));
        d.register(Commands.literal("unfreeze").requires(STAFF)
                .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(c -> freeze(c, false))));
        d.register(Commands.literal("mute").requires(STAFF)
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(c -> mute(c, "perm", ""))
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .executes(c -> mute(c, StringArgumentType.getString(c, "duration"), ""))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(c -> mute(c, StringArgumentType.getString(c, "duration"), StringArgumentType.getString(c, "reason")))))));
        d.register(Commands.literal("unmute").requires(STAFF)
                .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(ModerationCommands::unmute)));
        d.register(Commands.literal("tempban").requires(STAFF)
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .executes(c -> tempban(c, ""))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(c -> tempban(c, StringArgumentType.getString(c, "reason")))))));
    }

    private static int vanish(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        boolean vanish = !manager().isVanished(player.getUUID());
        Vanish.set(player, vanish);
        return ok(c, vanish
                ? "You are vanished. Non-admins can't see you or find you in the tab list. Run /vanish again to reappear."
                : "You are visible again.");
    }

    private static int freeze(CommandContext<CommandSourceStack> c, boolean freeze) throws CommandSyntaxException {
        NameAndId target = single(c);
        if (target == null) {
            return 0;
        }
        ModerationManager manager = manager();
        if (manager.isFrozen(target.id()) == freeze) {
            return fail(c, target.name() + (freeze ? " is already frozen." : " is not frozen."));
        }
        manager.setFrozen(target.id(), target.name(), freeze);
        ServerPlayer online = c.getSource().getServer().getPlayerList().getPlayer(target.id());
        if (freeze && online != null) {
            ModerationEvents.anchorNow(online);
        } else {
            ModerationEvents.clearAnchor(target.id());
        }
        if (online != null) {
            online.sendSystemMessage(Component.literal(freeze
                    ? "You have been frozen by staff. Don't log out; you'll still be frozen when you come back."
                    : "You are no longer frozen.").withStyle(ChatFormatting.AQUA));
        }
        return ok(c, freeze
                ? target.name() + " is frozen" + (online == null ? " (offline: frozen when they join)." : ".")
                : target.name() + " is no longer frozen.");
    }

    private static int mute(CommandContext<CommandSourceStack> c, String duration, String reason) throws CommandSyntaxException {
        NameAndId target = single(c);
        if (target == null) {
            return 0;
        }
        long until = 0;
        if (!duration.equalsIgnoreCase("perm")) {
            long ms = Durations.parse(duration);
            if (ms < 0) {
                return fail(c, "Invalid duration \"" + duration + "\". Use e.g. 30m, 2h, 7d, 1w, or perm.");
            }
            until = System.currentTimeMillis() + ms;
        }
        manager().setMute(target.id(), new ModerationManager.Mute(target.name(), until, reason));
        String time = until == 0 ? " permanently" : " for " + Durations.format(until - System.currentTimeMillis());
        ServerPlayer online = c.getSource().getServer().getPlayerList().getPlayer(target.id());
        if (online != null) {
            online.sendSystemMessage(Component.literal("You have been muted" + time + "." + (reason.isEmpty() ? "" : " Reason: " + reason))
                    .withStyle(ChatFormatting.RED));
        }
        return ok(c, target.name() + " is muted" + time + ".");
    }

    private static int unmute(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        NameAndId target = single(c);
        if (target == null) {
            return 0;
        }
        if (manager().mute(target.id()) == null) {
            return fail(c, target.name() + " is not muted.");
        }
        manager().setMute(target.id(), null);
        ServerPlayer online = c.getSource().getServer().getPlayerList().getPlayer(target.id());
        if (online != null) {
            online.sendSystemMessage(Component.literal("You can chat again.").withStyle(ChatFormatting.GREEN));
        }
        return ok(c, target.name() + " is no longer muted.");
    }

    private static int tempban(CommandContext<CommandSourceStack> c, String reason) throws CommandSyntaxException {
        NameAndId target = single(c);
        if (target == null) {
            return 0;
        }
        String duration = StringArgumentType.getString(c, "duration");
        long ms = Durations.parse(duration);
        if (ms < 0) {
            return fail(c, "Invalid duration \"" + duration + "\". Use e.g. 30m, 2h, 7d, 1w.");
        }
        String why = reason.isEmpty() ? "Temporarily banned" : reason;
        Date expires = new Date(System.currentTimeMillis() + ms);
        c.getSource().getServer().getPlayerList().getBans()
                .add(new UserBanListEntry(target, new Date(), c.getSource().getTextName(), expires, why));
        ServerPlayer online = c.getSource().getServer().getPlayerList().getPlayer(target.id());
        if (online != null) {
            online.connection.disconnect(Component.literal("You are banned for " + Durations.format(ms) + ".\nReason: " + why));
        }
        return ok(c, target.name() + " is banned for " + Durations.format(ms) + ". Use /pardon " + target.name() + " to lift it early.");
    }

    // ---------------------------------------------------------------- helpers

    private static ModerationManager manager() {
        ModerationManager manager = ModerationManager.get();
        if (manager == null) {
            throw new IllegalStateException("SafePlots is not running");
        }
        return manager;
    }

    private static @Nullable NameAndId single(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(c, "player");
        if (profiles.size() != 1) {
            fail(c, "Please name exactly one player.");
            return null;
        }
        return profiles.iterator().next();
    }

    private static int ok(CommandContext<CommandSourceStack> c, String message) {
        c.getSource().sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> c, String message) {
        c.getSource().sendFailure(Component.literal(message));
        return 0;
    }
}
