package dev.safemc.safeplots.parkour;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.safemc.safeplots.plot.PlotManager;
import dev.safemc.safeplots.world.Worlds;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /parkour. Players: checkpoint, leave, and seeing when their next reward is. Admins build courses by standing on
 * the start, checkpoint and finish blocks.
 */
public final class ParkourCommand {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Predicate<CommandSourceStack> ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private ParkourCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("parkour")
                .executes(ParkourCommand::status)
                .then(Commands.literal("checkpoint").executes(ParkourCommand::checkpoint))
                .then(Commands.literal("leave").executes(ParkourCommand::leave))
                .then(Commands.literal("create").requires(ADMIN)
                        .then(Commands.argument("name", StringArgumentType.word()).executes(ParkourCommand::create)))
                .then(Commands.literal("setstart").requires(ADMIN).then(course().executes(ParkourCommand::setStart)))
                .then(Commands.literal("setfinish").requires(ADMIN).then(course().executes(ParkourCommand::setFinish)))
                .then(Commands.literal("addcheckpoint").requires(ADMIN).then(course().executes(ParkourCommand::addCheckpoint)))
                .then(Commands.literal("clearcheckpoints").requires(ADMIN).then(course().executes(ParkourCommand::clearCheckpoints)))
                .then(Commands.literal("reward").requires(ADMIN).then(course()
                        .then(Commands.literal("none").executes(ParkourCommand::noReward))
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Stream.concat(Stream.of("world", "nether", "end"),
                                        Worlds.get().worlds().stream().map(Worlds.World::name)), b))
                                .executes(c -> reward(c, 3))
                                .then(Commands.argument("diamonds", IntegerArgumentType.integer(1, 64))
                                        .executes(c -> reward(c, IntegerArgumentType.getInteger(c, "diamonds")))))))
                .then(Commands.literal("cooldown").requires(ADMIN).then(course()
                        .then(Commands.argument("hours", IntegerArgumentType.integer(0, 24 * 30)).executes(ParkourCommand::cooldown))))
                .then(Commands.literal("fallheight").requires(ADMIN).then(course().executes(ParkourCommand::fallHeight)
                        .then(Commands.literal("auto").executes(ParkourCommand::fallHeightAuto))))
                .then(Commands.literal("resetcooldown").requires(ADMIN).then(course()
                        .then(Commands.argument("player", EntityArgument.player()).executes(ParkourCommand::resetCooldown))))
                .then(Commands.literal("info").requires(ADMIN).then(course().executes(ParkourCommand::info)))
                .then(Commands.literal("list").requires(ADMIN).executes(ParkourCommand::list))
                .then(Commands.literal("delete").requires(ADMIN).then(course().executes(ParkourCommand::delete))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> course() {
        return Commands.argument("name", StringArgumentType.word())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(parkours().courses().stream().map(c -> c.name), b));
    }

    private static Parkours parkours() {
        return Parkours.get();
    }

    private static Parkours.@org.jspecify.annotations.Nullable Course find(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        Parkours.Course course = parkours().course(name);
        if (course == null) {
            fail(c, "No parkour named " + name + ". See /parkour list.");
        }
        return course;
    }

    /** The block the admin stands on (or in, for a pressure plate), in their world. */
    private static BlockPos here(ServerPlayer player) {
        return player.blockPosition();
    }

    private static String at(BlockPos p) {
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    // ---------------------------------------------------------------- players

    private static int status(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (parkours().courses().isEmpty()) {
            return ok(c, "There's no parkour yet.");
        }
        List<String> lines = new ArrayList<>();
        for (Parkours.Course course : parkours().courses()) {
            if (course.rewardWorld == null) {
                lines.add("Parkour " + course.name + ": no reward.");
                continue;
            }
            long left = parkours().cooldownLeft(player.getUUID(), course);
            lines.add("Parkour " + course.name + ": " + course.diamonds + " diamonds into your " + course.rewardWorld + " inventory, "
                    + (left == 0 ? "ready now!" : "next one in " + Parkours.duration(left) + "."));
        }
        lines.add("During a run: /parkour checkpoint goes back, /parkour leave stops.");
        return ok(c, String.join("\n", lines));
    }

    private static int checkpoint(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        return parkours().backToCheckpoint(player) ? ok(c, "Back to your last checkpoint.")
                : fail(c, "You're not doing a parkour. Step on its start to begin.");
    }

    private static int leave(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (!parkours().running(player)) {
            return fail(c, "You're not doing a parkour.");
        }
        parkours().leave(player);
        return ok(c, "Parkour stopped.");
    }

    // ---------------------------------------------------------------- admins

    private static int create(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(c, "name");
        if (!NAME.matcher(name).matches()) {
            return fail(c, "Parkour names may only use letters, numbers, _ and - (max 32).");
        }
        if (parkours().course(name) != null) {
            return fail(c, "A parkour named " + name + " already exists.");
        }
        parkours().create(name, PlotManager.dimensionId(player.level()), here(player));
        return ok(c, "✓ Parkour " + name + " created; its start is where you stand (" + at(here(player)) + ")."
                + " Next: stand on the last block and /parkour setfinish " + name
                + ". Optional: /parkour addcheckpoint " + name + " on each checkpoint, in order."
                + " Then /parkour reward " + name + " <survival world> for the diamonds.");
    }

    private static boolean sameWorld(CommandContext<CommandSourceStack> c, ServerPlayer player, Parkours.Course course) {
        if (!course.dimension.equals(PlotManager.dimensionId(player.level()))) {
            fail(c, "Stand in the world of " + course.name + " for this.");
            return false;
        }
        return true;
    }

    private static int setStart(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Parkours.Course course = find(c);
        if (course == null || !sameWorld(c, player, course)) {
            return 0;
        }
        course.start = here(player);
        parkours().changed(course);
        return ok(c, "✓ Start of " + course.name + " moved to " + at(course.start) + ".");
    }

    private static int setFinish(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Parkours.Course course = find(c);
        if (course == null || !sameWorld(c, player, course)) {
            return 0;
        }
        course.finish = here(player);
        parkours().changed(course);
        return ok(c, "✓ Finish of " + course.name + " set at " + at(course.finish) + "."
                + (course.rewardWorld == null ? " Set the reward with /parkour reward " + course.name + " <survival world>." : ""));
    }

    private static int addCheckpoint(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Parkours.Course course = find(c);
        if (course == null || !sameWorld(c, player, course)) {
            return 0;
        }
        course.checkpoints.add(here(player));
        parkours().changed(course);
        return ok(c, "✓ Checkpoint " + course.checkpoints.size() + " of " + course.name + " at " + at(here(player))
                + ". Players must reach the checkpoints in this order.");
    }

    private static int clearCheckpoints(CommandContext<CommandSourceStack> c) {
        Parkours.Course course = find(c);
        if (course == null) {
            return 0;
        }
        course.checkpoints.clear();
        parkours().changed(course);
        return ok(c, "✓ " + course.name + " has no checkpoints now.");
    }

    private static int reward(CommandContext<CommandSourceStack> c, int diamonds) {
        Parkours.Course course = find(c);
        if (course == null) {
            return 0;
        }
        String world = StringArgumentType.getString(c, "world");
        if (Worlds.get().levelByName(world) == null) {
            return fail(c, "No world named " + world + ".");
        }
        course.rewardWorld = world;
        course.diamonds = diamonds;
        parkours().changed(course);
        return ok(c, "✓ Finishing " + course.name + " gives " + diamonds + " diamonds, put straight into the player's " + world
                + " inventory, once every " + course.cooldownHours + " hours.");
    }

    private static int noReward(CommandContext<CommandSourceStack> c) {
        Parkours.Course course = find(c);
        if (course == null) {
            return 0;
        }
        course.rewardWorld = null;
        parkours().changed(course);
        return ok(c, "✓ " + course.name + " gives no reward now.");
    }

    private static int cooldown(CommandContext<CommandSourceStack> c) {
        Parkours.Course course = find(c);
        if (course == null) {
            return 0;
        }
        course.cooldownHours = IntegerArgumentType.getInteger(c, "hours");
        parkours().changed(course);
        return ok(c, "✓ The reward of " + course.name + " can be won once every " + course.cooldownHours + " hours.");
    }

    private static int fallHeight(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Parkours.Course course = find(c);
        if (course == null || !sameWorld(c, player, course)) {
            return 0;
        }
        course.fallY = here(player).getY();
        parkours().changed(course);
        return ok(c, "✓ Players on " + course.name + " who get down to this height (y " + course.fallY
                + ") or lower go back to their last checkpoint.");
    }

    private static int fallHeightAuto(CommandContext<CommandSourceStack> c) {
        Parkours.Course course = find(c);
        if (course == null) {
            return 0;
        }
        course.fallY = null;
        parkours().changed(course);
        return ok(c, "✓ Players on " + course.name + " go back when they fall 2 blocks below its lowest block (y " + course.fallY() + ").");
    }

    private static int resetCooldown(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Parkours.Course course = find(c);
        if (course == null) {
            return 0;
        }
        ServerPlayer target = EntityArgument.getPlayer(c, "player");
        parkours().resetCooldown(target.getUUID(), course);
        return ok(c, "✓ " + target.getGameProfile().name() + " can win the reward of " + course.name + " again now.");
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        Parkours.Course course = find(c);
        if (course == null) {
            return 0;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Parkour " + course.name + " in " + course.dimension + ":");
        lines.add(" start " + at(course.start) + ", finish " + (course.finish == null ? "NOT SET (/parkour setfinish)" : at(course.finish)));
        for (int i = 0; i < course.checkpoints.size(); i++) {
            lines.add(" checkpoint " + (i + 1) + ": " + at(course.checkpoints.get(i)));
        }
        lines.add(course.rewardWorld == null ? " no reward (/parkour reward " + course.name + " <world>)"
                : " reward: " + course.diamonds + " diamonds into the " + course.rewardWorld + " inventory, every " + course.cooldownHours + "h");
        lines.add(" falling to y " + course.fallY() + " or lower: back to the last checkpoint"
                + (course.fallY == null ? " (auto; /parkour fallheight " + course.name + " to change)" : ""));
        return ok(c, String.join("\n", lines));
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        if (parkours().courses().isEmpty()) {
            return ok(c, "No parkours yet. Stand on the start block and /parkour create <name>.");
        }
        List<String> lines = new ArrayList<>();
        for (Parkours.Course course : parkours().courses()) {
            lines.add(" - " + course.name + ": start " + at(course.start) + ", " + course.checkpoints.size() + " checkpoint(s), "
                    + (course.finish == null ? "no finish yet" : "finish " + at(course.finish)));
        }
        return ok(c, "Parkours:\n" + String.join("\n", lines));
    }

    private static int delete(CommandContext<CommandSourceStack> c) {
        Parkours.Course course = find(c);
        if (course == null) {
            return 0;
        }
        parkours().delete(course);
        return ok(c, "Parkour " + course.name + " deleted.");
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
