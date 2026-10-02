package dev.safemc.safeplots.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.safemc.safeplots.plot.Plot;
import dev.safemc.safeplots.plot.PlotBorders;
import dev.safemc.safeplots.plot.PlotManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

public final class PlotCommand {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Predicate<CommandSourceStack> ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private static final SuggestionProvider<CommandSourceStack> ALL_PLOTS = (ctx, builder) -> {
        PlotManager manager = PlotManager.get();
        return SharedSuggestionProvider.suggest(manager == null ? List.of() : manager.plots().stream().map(Plot::name).toList(), builder);
    };

    private static final SuggestionProvider<CommandSourceStack> OWN_PLOTS = (ctx, builder) -> {
        PlotManager manager = PlotManager.get();
        ServerPlayer player = ctx.getSource().getPlayer();
        if (manager == null || player == null) {
            return builder.buildFuture();
        }
        return SharedSuggestionProvider.suggest(manager.plotsOwnedBy(player.getUUID()).stream().map(Plot::name).toList(), builder);
    };

    /** Plots whose borders the player may show: their own and trusted plots, or every plot for admins. */
    private static final SuggestionProvider<CommandSourceStack> VIEWABLE_PLOTS = (ctx, builder) -> {
        PlotManager manager = PlotManager.get();
        ServerPlayer player = ctx.getSource().getPlayer();
        if (manager == null || player == null) {
            return builder.buildFuture();
        }
        return SharedSuggestionProvider.suggest(viewablePlots(manager, player).stream().map(Plot::name).toList(), builder);
    };

    private PlotCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ppt")
                .executes(HelpCommand::run)
                .then(Commands.literal("help").executes(HelpCommand::run)));
        dispatcher.register(Commands.literal("plot")
                // ---- player commands
                .then(Commands.literal("trust")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(c -> trust(c, true, null))
                                .then(Commands.argument("plot", StringArgumentType.word()).suggests(OWN_PLOTS)
                                        .executes(c -> trust(c, true, StringArgumentType.getString(c, "plot"))))))
                .then(Commands.literal("untrust")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(c -> trust(c, false, null))
                                .then(Commands.argument("plot", StringArgumentType.word()).suggests(OWN_PLOTS)
                                        .executes(c -> trust(c, false, StringArgumentType.getString(c, "plot"))))))
                .then(Commands.literal("showlimits")
                        .executes(c -> showLimits(c, null))
                        .then(Commands.argument("plot", StringArgumentType.word()).suggests(VIEWABLE_PLOTS)
                                .executes(c -> showLimits(c, StringArgumentType.getString(c, "plot")))))
                .then(Commands.literal("abandon")
                        .executes(c -> abandon(c, null))
                        .then(Commands.argument("plot", StringArgumentType.word()).suggests(OWN_PLOTS)
                                .executes(c -> abandon(c, StringArgumentType.getString(c, "plot")))))
                .then(Commands.literal("movesign")
                        .executes(c -> moveSign(c, null))
                        .then(Commands.argument("plot", StringArgumentType.word()).suggests(OWN_PLOTS)
                                .executes(c -> moveSign(c, StringArgumentType.getString(c, "plot")))))
                .then(Commands.literal("cancel").executes(PlotCommand::cancel))
                // ---- admin commands
                .then(Commands.literal("wand").requires(ADMIN).executes(PlotCommand::wand))
                .then(Commands.literal("pos1").requires(ADMIN).executes(c -> corner(c, 1)))
                .then(Commands.literal("pos2").requires(ADMIN).executes(c -> corner(c, 2)))
                .then(Commands.literal("create").requires(ADMIN)
                        .then(heightOptions(Commands.argument("name", StringArgumentType.word()), PlotCommand::create)))
                .then(Commands.literal("redefine").requires(ADMIN)
                        .then(heightOptions(Commands.argument("name", StringArgumentType.word()).suggests(ALL_PLOTS), PlotCommand::redefine)))
                .then(Commands.literal("setheight").requires(ADMIN)
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ALL_PLOTS)
                                .then(Commands.argument("minY", IntegerArgumentType.integer())
                                        .executes(c -> setHeight(c, IntegerArgumentType.getInteger(c, "minY"), null))
                                        .then(Commands.argument("maxY", IntegerArgumentType.integer())
                                                .executes(c -> setHeight(c, IntegerArgumentType.getInteger(c, "minY"),
                                                        IntegerArgumentType.getInteger(c, "maxY")))))))
                .then(Commands.literal("delete").requires(ADMIN)
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ALL_PLOTS).executes(PlotCommand::delete)))
                .then(Commands.literal("sign").requires(ADMIN)
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ALL_PLOTS).executes(PlotCommand::sign)))
                .then(Commands.literal("setowner").requires(ADMIN)
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ALL_PLOTS)
                                .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(PlotCommand::setOwner))))
                .then(Commands.literal("removeowner").requires(ADMIN)
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ALL_PLOTS).executes(PlotCommand::removeOwner)))
                .then(Commands.literal("setlimit").requires(ADMIN)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("number", IntegerArgumentType.integer(0))
                                        .executes(c -> limit(c, false, IntegerArgumentType.getInteger(c, "number"))))))
                .then(Commands.literal("addlimit").requires(ADMIN)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer())
                                        .executes(c -> limit(c, true, IntegerArgumentType.getInteger(c, "amount"))))))
                .then(Commands.literal("list").requires(ADMIN)
                        .executes(PlotCommand::listAll)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(PlotCommand::listPlayer)))
                .then(Commands.literal("info").requires(ADMIN)
                        .executes(c -> info(c, null))
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ALL_PLOTS)
                                .executes(c -> info(c, StringArgumentType.getString(c, "name")))))
                .then(Commands.literal("bypass").requires(ADMIN).executes(PlotCommand::bypass)));
    }

    // ---------------------------------------------------------------- player commands

    private static int trust(CommandContext<CommandSourceStack> c, boolean trust, @Nullable String plotName) throws CommandSyntaxException {
        PlotManager manager = manager();
        ServerPlayer player = c.getSource().getPlayerOrException();
        NameAndId target = singlePlayer(c, "player");
        if (target == null) {
            return 0;
        }
        Plot plot = ownedPlot(c, manager, player, plotName);
        if (plot == null) {
            return 0;
        }
        if (target.id().equals(plot.owner())) {
            return fail(c, "You already own this plot.");
        }
        if (trust == plot.trusted().contains(target.id())) {
            return fail(c, target.name() + (trust ? " is already trusted in " : " is not trusted in ") + plot.name() + ".");
        }
        manager.rememberName(target);
        manager.setTrusted(plot, target.id(), trust);
        return ok(c, trust
                ? target.name() + " can now build in " + plot.name() + "."
                : target.name() + " no longer has access to " + plot.name() + ".");
    }

    /** Owners move their plot's claim sign to another sign inside the plot (see ClaimSigns#link for the rules). */
    private static int moveSign(CommandContext<CommandSourceStack> c, @Nullable String plotName) throws CommandSyntaxException {
        PlotManager manager = manager();
        ServerPlayer player = c.getSource().getPlayerOrException();
        Plot plot = ownedPlot(c, manager, player, plotName);
        if (plot == null) {
            return 0;
        }
        manager.setPendingSignLink(player.getUUID(), plot.name());
        return ok(c, "Now place a sign inside " + plot.name() + ", or right-click one that's already there, to make it the plot's sign."
                + " The old sign is blanked. Use /plot cancel to stop.");
    }

    private static int abandon(CommandContext<CommandSourceStack> c, @Nullable String plotName) throws CommandSyntaxException {
        PlotManager manager = manager();
        ServerPlayer player = c.getSource().getPlayerOrException();
        Plot plot = plotName != null ? manager.plot(plotName) : manager.plotAt(player.level(), player.blockPosition());
        if (plot == null) {
            return fail(c, plotName != null ? "No plot named " + plotName + "." : "You are not standing in a plot.");
        }
        if (!player.getUUID().equals(plot.owner())) {
            return fail(c, "You don't own " + plot.name() + ".");
        }
        manager.setOwner(plot, null);
        return ok(c, "You abandoned " + plot.name() + ". It is available again; the buildings were left as they are.");
    }

    /** Toggles the particle outline. Running it again for the same plot (or with no name) hides it. */
    private static int showLimits(CommandContext<CommandSourceStack> c, @Nullable String plotName) throws CommandSyntaxException {
        PlotManager manager = manager();
        ServerPlayer player = c.getSource().getPlayerOrException();
        String current = PlotBorders.showing(player.getUUID());
        if (current != null && (plotName == null || plotName.equals(current))) {
            PlotBorders.hide(player.getUUID());
            return ok(c, "Stopped showing the borders of " + current + ".");
        }

        Plot plot;
        if (plotName != null) {
            plot = manager.plot(plotName);
            if (plot == null) {
                return fail(c, "No plot named " + plotName + ".");
            }
        } else {
            // The plot you're standing in, else your only plot.
            plot = manager.plotAt(player.level(), player.blockPosition());
            if (plot == null || !PlotBorders.canView(player, plot)) {
                List<Plot> mine = manager.plots().stream().filter(p -> p.isMember(player.getUUID())).toList();
                if (mine.size() != 1) {
                    return fail(c, mine.isEmpty()
                            ? "You don't have a plot. Stand in one or use /plot showlimits <plot>."
                            : "You have several plots (" + String.join(", ", mine.stream().map(Plot::name).toList())
                                    + "). Use /plot showlimits <plot>.");
                }
                plot = mine.getFirst();
            }
        }
        if (!PlotBorders.canView(player, plot)) {
            return fail(c, "You can only show the borders of your own plots.");
        }
        PlotBorders.show(player, plot);
        String where = plot.dimension().equals(PlotManager.dimensionId(player.level())) ? "" : " (it is in " + plot.dimension() + ")";
        return ok(c, "Showing the borders of " + plot.name() + where + ". Run /plot showlimits again to hide them.");
    }

    private static List<Plot> viewablePlots(PlotManager manager, ServerPlayer player) {
        boolean admin = PlotManager.isAdmin(player);
        return manager.plots().stream().filter(p -> admin || p.isMember(player.getUUID())).toList();
    }

    // ---------------------------------------------------------------- admin commands

    private static int corner(CommandContext<CommandSourceStack> c, int corner) throws CommandSyntaxException {
        PlotManager manager = manager();
        ServerPlayer player = c.getSource().getPlayerOrException();
        // The block you're looking at, or where you stand if you aren't looking at one.
        BlockPos pos = player.pick(player.blockInteractionRange(), 1.0F, false) instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : player.blockPosition();
        PlotWand.setCorner(manager, player, corner, pos);
        return 1;
    }

    private static int wand(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ItemStack wand = PlotWand.create();
        if (!player.getInventory().add(wand)) {
            return fail(c, "Your inventory is full.");
        }
        return ok(c, "Plot Wand: left-click a block for position 1, right-click a block for position 2.");
    }

    /**
     * How tall a plot is. EXACT (the default) uses the two selected corners as they are; FULL is the whole
     * world height; otherwise an explicit range, where a missing maxY means the build limit.
     */
    private record Height(boolean exact, @Nullable Integer minY, @Nullable Integer maxY) {
        static final Height FULL = new Height(false, null, null);
        static final Height EXACT = new Height(true, null, null);
    }

    private interface HeightAction {
        int run(CommandContext<CommandSourceStack> c, Height height) throws CommandSyntaxException;
    }

    /** Adds {@code [full | height <minY> [maxY]]} after a plot name argument. */
    private static RequiredArgumentBuilder<CommandSourceStack, String> heightOptions(
            RequiredArgumentBuilder<CommandSourceStack, String> name, HeightAction action) {
        return name.executes(c -> action.run(c, Height.EXACT))
                .then(Commands.literal("full").executes(c -> action.run(c, Height.FULL)))
                .then(Commands.literal("height")
                        .then(Commands.argument("minY", IntegerArgumentType.integer())
                                .executes(c -> action.run(c, new Height(false, IntegerArgumentType.getInteger(c, "minY"), null)))
                                .then(Commands.argument("maxY", IntegerArgumentType.integer())
                                        .executes(c -> action.run(c, new Height(false, IntegerArgumentType.getInteger(c, "minY"),
                                                IntegerArgumentType.getInteger(c, "maxY")))))));
    }

    private static int create(CommandContext<CommandSourceStack> c, Height height) throws CommandSyntaxException {
        PlotManager manager = manager();
        ServerPlayer player = c.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(c, "name");
        if (!NAME.matcher(name).matches()) {
            return fail(c, "Plot names may only use letters, numbers, _ and - (max 32).");
        }
        if (manager.plot(name) != null) {
            return fail(c, "A plot named " + name + " already exists.");
        }
        PlotManager.Selection sel = manager.selection(player.getUUID());
        BlockPos[] corners = corners(c, manager, sel, height);
        if (corners == null) {
            return 0;
        }
        Plot plot = new Plot(name, sel.dimension(), corners[0], corners[1]);
        String overlap = manager.create(plot);
        if (overlap != null) {
            return fail(c, "Cannot create plot: this region overlaps with " + overlap + ".");
        }
        return ok(c, "✓ Plot " + name + " created: " + plot.describeBounds() + ". Link a claim sign with /plot sign " + name + ".");
    }

    /** New corners from the current selection; owner, trusted players and sign stay. */
    private static int redefine(CommandContext<CommandSourceStack> c, Height height) throws CommandSyntaxException {
        PlotManager manager = manager();
        ServerPlayer player = c.getSource().getPlayerOrException();
        Plot plot = namedPlot(c, manager);
        if (plot == null) {
            return 0;
        }
        PlotManager.Selection sel = manager.selection(player.getUUID());
        if (sel.dimension() != null && !sel.dimension().equals(plot.dimension())) {
            return fail(c, "Your selection is in a different dimension than " + plot.name() + ".");
        }
        BlockPos[] corners = corners(c, manager, sel, height);
        if (corners == null) {
            return 0;
        }
        return resized(c, manager, plot, corners[0], corners[1]);
    }

    /** Keeps the plot's X/Z and only changes its height. */
    private static int setHeight(CommandContext<CommandSourceStack> c, int minY, @Nullable Integer maxY) {
        PlotManager manager = manager();
        Plot plot = namedPlot(c, manager);
        if (plot == null) {
            return 0;
        }
        ServerLevel level = manager.level(plot.dimension());
        if (level == null) {
            return fail(c, "The plot's dimension is not loaded.");
        }
        int[] ys = heightRange(c, level, minY, maxY);
        if (ys == null) {
            return 0;
        }
        BlockPos min = plot.min(), max = plot.max();
        return resized(c, manager, plot, new BlockPos(min.getX(), ys[0], min.getZ()), new BlockPos(max.getX(), ys[1], max.getZ()));
    }

    private static int resized(CommandContext<CommandSourceStack> c, PlotManager manager, Plot plot, BlockPos a, BlockPos b) {
        String overlap = manager.resize(plot, a, b);
        if (overlap != null) {
            return fail(c, "Cannot change plot: the new region overlaps with " + overlap + ".");
        }
        return ok(c, "✓ Plot " + plot.name() + " is now " + plot.describeBounds() + ". The blocks were not changed.");
    }

    /** Applies a height mode to the selected corners. Sends the error and returns null if something is wrong. */
    private static BlockPos @Nullable [] corners(CommandContext<CommandSourceStack> c, PlotManager manager, PlotManager.Selection sel, Height height) {
        if (sel.pos1() == null || sel.pos2() == null || sel.dimension() == null) {
            fail(c, "Set both corners first with /plot wand, or /plot pos1 and /plot pos2.");
            return null;
        }
        ServerLevel level = manager.level(sel.dimension());
        if (level == null) {
            fail(c, "The selected dimension is not loaded.");
            return null;
        }
        BlockPos a = sel.pos1(), b = sel.pos2();
        if (height.exact()) {
            return new BlockPos[] {a, b};
        }
        // "full": the whole column from bedrock to build limit, so nobody can build over or dig under.
        int[] ys = height.minY() == null
                ? new int[] {level.getMinY(), level.getMaxY()}
                : heightRange(c, level, height.minY(), height.maxY());
        if (ys == null) {
            return null;
        }
        return new BlockPos[] {new BlockPos(a.getX(), ys[0], a.getZ()), new BlockPos(b.getX(), ys[1], b.getZ())};
    }

    private static int @Nullable [] heightRange(CommandContext<CommandSourceStack> c, ServerLevel level, int minY, @Nullable Integer maxY) {
        int top = maxY == null ? level.getMaxY() : maxY;
        if (minY < level.getMinY() || top > level.getMaxY() || minY > top) {
            fail(c, "Heights must be between " + level.getMinY() + " and " + level.getMaxY() + ", with minY below maxY.");
            return null;
        }
        return new int[] {minY, top};
    }

    private static int delete(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        PlotManager manager = manager();
        Plot plot = namedPlot(c, manager);
        if (plot == null) {
            return 0;
        }
        manager.delete(plot);
        return ok(c, "Plot " + plot.name() + " deleted. The blocks in it were not changed.");
    }

    private static int sign(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        PlotManager manager = manager();
        ServerPlayer player = c.getSource().getPlayerOrException();
        Plot plot = namedPlot(c, manager);
        if (plot == null) {
            return 0;
        }
        manager.setPendingSignLink(player.getUUID(), plot.name());
        return ok(c, "Now place a sign, or right-click an existing one, to link it to " + plot.name() + ". Use /plot cancel to stop.");
    }

    private static int cancel(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        String pending = manager().takePendingSignLink(player.getUUID());
        return pending == null ? fail(c, "You are not linking a sign.") : ok(c, "Stopped linking a sign to " + pending + ".");
    }

    private static int setOwner(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        PlotManager manager = manager();
        Plot plot = namedPlot(c, manager);
        NameAndId target = singlePlayer(c, "player");
        if (plot == null || target == null) {
            return 0;
        }
        // Admin assignment ignores the claim limit on purpose.
        manager.rememberName(target);
        manager.setOwner(plot, target.id());
        return ok(c, target.name() + " now owns " + plot.name() + ".");
    }

    private static int removeOwner(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        PlotManager manager = manager();
        Plot plot = namedPlot(c, manager);
        if (plot == null) {
            return 0;
        }
        if (plot.owner() == null) {
            return fail(c, plot.name() + " has no owner.");
        }
        manager.setOwner(plot, null);
        return ok(c, plot.name() + " is available again. The blocks in it were not changed.");
    }

    private static int limit(CommandContext<CommandSourceStack> c, boolean add, int value) throws CommandSyntaxException {
        PlotManager manager = manager();
        NameAndId target = singlePlayer(c, "player");
        if (target == null) {
            return 0;
        }
        int newLimit = Math.max(0, add ? manager.maxClaims(target.id()) + value : value);
        manager.rememberName(target);
        manager.setMaxClaims(target.id(), newLimit);
        int owned = manager.plotsOwnedBy(target.id()).size();
        String note = owned > newLimit ? " They keep the " + owned + " plots they already own." : "";
        return ok(c, target.name() + " can now own " + newLimit + " plot" + (newLimit == 1 ? "" : "s") + " (owns " + owned + ")." + note);
    }

    private static int listAll(CommandContext<CommandSourceStack> c) {
        PlotManager manager = manager();
        Collection<Plot> plots = manager.plots();
        if (plots.isEmpty()) {
            return ok(c, "There are no plots yet.");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Plots (" + plots.size() + "):");
        for (Plot plot : plots) {
            String owner = plot.owner() == null ? "AVAILABLE" : manager.nameOf(plot.owner());
            lines.add(" - " + plot.name() + ": " + owner + " (" + plot.dimension() + " " + plot.min().getX() + ", " + plot.min().getZ() + ")");
        }
        return ok(c, String.join("\n", lines));
    }

    private static int listPlayer(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        PlotManager manager = manager();
        NameAndId target = singlePlayer(c, "player");
        if (target == null) {
            return 0;
        }
        List<Plot> owned = manager.plotsOwnedBy(target.id());
        String names = owned.isEmpty() ? "none" : String.join(", ", owned.stream().map(Plot::name).toList());
        return ok(c, target.name() + " owns " + owned.size() + "/" + manager.maxClaims(target.id()) + " plots: " + names);
    }

    private static int info(CommandContext<CommandSourceStack> c, @Nullable String name) throws CommandSyntaxException {
        PlotManager manager = manager();
        Plot plot;
        if (name != null) {
            plot = manager.plot(name);
            if (plot == null) {
                return fail(c, "No plot named " + name + ".");
            }
        } else {
            ServerPlayer player = c.getSource().getPlayerOrException();
            plot = manager.plotAt(player.level(), player.blockPosition());
            if (plot == null) {
                return fail(c, "You are not standing in a plot. Use /plot info <name>.");
            }
        }
        UUID owner = plot.owner();
        String trusted = plot.trusted().isEmpty() ? "none"
                : String.join(", ", plot.trusted().stream().map(manager::nameOf).toList());
        BlockPos sign = plot.sign();
        return ok(c, String.join("\n",
                "Plot " + plot.name(),
                " Status: " + (owner == null ? "AVAILABLE" : "CLAIMED"),
                " Owner: " + (owner == null ? "none" : manager.nameOf(owner) + " (" + owner + ")"),
                " Dimension: " + plot.dimension(),
                " Area: " + plot.describeBounds(),
                " Trusted: " + trusted,
                " Sign: " + (sign == null ? "not linked" : sign.getX() + ", " + sign.getY() + ", " + sign.getZ())));
    }

    private static int bypass(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        boolean enabled = manager().toggleBypass(player.getUUID());
        return ok(c, enabled ? "Plot protection bypass enabled." : "Plot protection bypass disabled.");
    }

    // ---------------------------------------------------------------- helpers

    private static PlotManager manager() {
        PlotManager manager = PlotManager.get();
        if (manager == null) {
            throw new IllegalStateException("SafePlots is not running");
        }
        return manager;
    }

    private static @Nullable Plot namedPlot(CommandContext<CommandSourceStack> c, PlotManager manager) {
        String name = StringArgumentType.getString(c, "name");
        Plot plot = manager.plot(name);
        if (plot == null) {
            fail(c, "No plot named " + name + ".");
        }
        return plot;
    }

    /**
     * The plot a trust command applies to: the named plot, else the plot you stand in,
     * else your only plot. Owners (and admins with bypass) only.
     */
    private static @Nullable Plot ownedPlot(CommandContext<CommandSourceStack> c, PlotManager manager, ServerPlayer player, @Nullable String plotName) {
        Plot plot;
        if (plotName != null) {
            plot = manager.plot(plotName);
            if (plot == null) {
                fail(c, "No plot named " + plotName + ".");
                return null;
            }
        } else {
            plot = manager.plotAt(player.level(), player.blockPosition());
            if (plot == null || !player.getUUID().equals(plot.owner())) {
                List<Plot> owned = manager.plotsOwnedBy(player.getUUID());
                if (owned.size() != 1) {
                    fail(c, owned.isEmpty() ? "You don't own a plot." : "Stand in the plot, or name it: /plot trust <player> <plot>");
                    return null;
                }
                plot = owned.getFirst();
            }
        }
        if (!player.getUUID().equals(plot.owner()) && !manager.hasBypass(player)) {
            fail(c, "You don't own " + plot.name() + ".");
            return null;
        }
        return plot;
    }

    private static @Nullable NameAndId singlePlayer(CommandContext<CommandSourceStack> c, String arg) throws CommandSyntaxException {
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(c, arg);
        if (profiles.size() != 1) {
            fail(c, "Please name exactly one player.");
            return null;
        }
        return profiles.iterator().next();
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
