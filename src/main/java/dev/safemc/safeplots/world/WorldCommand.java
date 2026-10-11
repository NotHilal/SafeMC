package dev.safemc.safeplots.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.safemc.safeplots.inventory.WorldInventories;
import dev.safemc.safeplots.plot.PlotManager;
import dev.safemc.safeplots.teleport.Teleports;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * /mv create|delete|list|setspawn (admins) and /mvtp &lt;world&gt; (everyone, with the usual teleport warm-up).
 * {@code world}, {@code nether} and {@code end} are the vanilla dimensions.
 */
public final class WorldCommand {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final List<String> VANILLA = List.of("world", "nether", "end");

    private static final SuggestionProvider<CommandSourceStack> OUR_WORLDS =
            (ctx, b) -> SharedSuggestionProvider.suggest(worlds().worlds().stream().map(Worlds.World::name), b);
    private static final SuggestionProvider<CommandSourceStack> ALL_WORLDS =
            (ctx, b) -> SharedSuggestionProvider.suggest(Stream.concat(VANILLA.stream(), worlds().worlds().stream().map(Worlds.World::name)), b);

    private static final SuggestionProvider<CommandSourceStack> PORTALS =
            (ctx, b) -> SharedSuggestionProvider.suggest(portals().portals().stream().map(Portals.Entry::name), b);

    private static final SuggestionProvider<CommandSourceStack> TYPES =
            (ctx, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Worlds.Type.values()).map(Worlds.Type::id), b);

    private WorldCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("mv").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(WorldCommand::list)
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests(TYPES)
                                        .executes(c -> create(c, null))
                                        .then(Commands.argument("seed", StringArgumentType.word())
                                                .executes(c -> create(c, StringArgumentType.getString(c, "seed")))))))
                .then(Commands.literal("import")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("folder", StringArgumentType.string())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                WorldImport.available(ctx.getSource().getServer()).stream().map(WorldCommand::quoteIfNeeded), b))
                                        .executes(c -> importWorld(c, null, null))
                                        .then(Commands.argument("type", StringArgumentType.word()).suggests(TYPES)
                                                .executes(c -> importWorld(c, StringArgumentType.getString(c, "type"), null))
                                                .then(Commands.argument("seed", StringArgumentType.word())
                                                        .executes(c -> importWorld(c, StringArgumentType.getString(c, "type"),
                                                                StringArgumentType.getString(c, "seed"))))))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(OUR_WORLDS)
                                .executes(c -> delete(c, false))
                                .then(Commands.literal("confirm").executes(c -> delete(c, true)))))
                .then(Commands.literal("list").executes(WorldCommand::list))
                .then(Commands.literal("setspawn").executes(WorldCommand::setSpawn))
                .then(Commands.literal("group")
                        .executes(WorldCommand::listGroups)
                        .then(Commands.argument("world", StringArgumentType.word()).suggests(OUR_WORLDS)
                                .executes(c -> showGroup(c))
                                .then(Commands.argument("group", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(groupNames(), b))
                                        .executes(WorldCommand::setGroup))))
                .then(Commands.literal("portal")
                        .executes(WorldCommand::listPortals)
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("world", StringArgumentType.word()).suggests(ALL_WORLDS)
                                                .executes(WorldCommand::createPortal))))
                        .then(Commands.literal("setdest")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(PORTALS)
                                        .executes(WorldCommand::setPortalDest)))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(PORTALS)
                                        .executes(WorldCommand::deletePortal)))
                        .then(Commands.literal("list").executes(WorldCommand::listPortals))));

        d.register(Commands.literal("mvtp")
                .then(Commands.argument("world", StringArgumentType.word()).suggests(ALL_WORLDS)
                        .executes(c -> teleport(c, c.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(c -> teleport(c, EntityArgument.getPlayer(c, "player"))))));
    }

    /** Why {@code name} can't be used for a new world, or null if it can. */
    private static @Nullable String nameProblem(String name) {
        if (!NAME.matcher(name).matches()) {
            return "World names may only use lowercase letters, numbers, _ and - (max 32).";
        }
        if (VANILLA.contains(name) || name.equals(Worlds.MAIN_GROUP)) {
            return name + " is reserved for the vanilla worlds. Pick another name.";
        }
        if (worlds().world(name) != null || WorldImport.inProgress(name)) {
            return "A world named " + name + " already exists.";
        }
        if (worlds().isPendingDelete(name)) {
            return "The old world " + name + " is still being deleted. Restart the server or pick another name.";
        }
        return null;
    }

    private static int create(CommandContext<CommandSourceStack> c, @Nullable String seedText) {
        String name = StringArgumentType.getString(c, "name");
        String problem = nameProblem(name);
        if (problem != null) {
            return fail(c, problem);
        }
        Worlds.Type type = Worlds.Type.parse(StringArgumentType.getString(c, "type"));
        if (type == null) {
            return fail(c, unknownType());
        }
        long seed = seedText == null ? ThreadLocalRandom.current().nextLong() : parseSeed(seedText);
        c.getSource().sendSuccess(() -> Component.literal("Creating world " + name + "...").withStyle(ChatFormatting.YELLOW), false);
        try {
            worlds().create(name, type, seed);
        } catch (RuntimeException e) {
            com.mojang.logging.LogUtils.getLogger().error("[SafePlots] Failed to create world {}", name, e);
            return fail(c, "Could not create the world: " + e.getMessage());
        }
        return ok(c, "✓ World " + name + " created (" + type.id() + ", seed " + seed + "). Go there with /mvtp " + name + ".");
    }

    private static int importWorld(CommandContext<CommandSourceStack> c, @Nullable String typeText, @Nullable String seedText) {
        String name = StringArgumentType.getString(c, "name");
        String folder = StringArgumentType.getString(c, "folder");
        String problem = nameProblem(name);
        if (problem != null) {
            return fail(c, problem);
        }
        MinecraftServer server = c.getSource().getServer();
        java.nio.file.Path save = WorldImport.resolve(server, folder);
        if (save == null) {
            return fail(c, "No folder \"" + folder + "\" in " + WorldImport.importsDir(server)
                    + ". Copy the world folder there (the one with level.dat inside) first.");
        }
        WorldImport.Source source = WorldImport.inspect(save);
        if (source == null) {
            return fail(c, "\"" + folder + "\" doesn't look like a Java world: no region folder in it. Use the folder that contains level.dat.");
        }
        if (WorldImport.tooNew(source)) {
            return fail(c, "That world was saved by a newer Minecraft version than this server. It can't be loaded.");
        }
        Worlds.Type type = typeText == null ? Worlds.Type.NORMAL : Worlds.Type.parse(typeText);
        if (type == null) {
            return fail(c, unknownType());
        }
        // The map's own seed keeps new terrain at its edges matching what's already there.
        long seed = seedText != null ? parseSeed(seedText)
                : source.seed().orElseGet(() -> ThreadLocalRandom.current().nextLong());
        CommandSourceStack src = c.getSource();
        src.sendSuccess(() -> Component.literal("Importing " + folder + " as " + name + " (" + type.id() + ", seed " + seed
                + "). Big maps can take a while...").withStyle(ChatFormatting.YELLOW), false);
        WorldImport.start(server, worlds(), name, type, seed, source, (message, success) -> {
            if (success) {
                src.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), true);
            } else {
                src.sendFailure(Component.literal(message));
            }
        });
        return 1;
    }

    private static String quoteIfNeeded(String folder) {
        return folder.matches("[A-Za-z0-9_.+-]+") ? folder : "\"" + folder.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String unknownType() {
        return "Unknown type. Use one of: " + String.join(", ", Arrays.stream(Worlds.Type.values()).map(Worlds.Type::id).toList()) + ".";
    }

    /** Like the vanilla world-creation screen: a number is used as-is, any other text is hashed. */
    private static long parseSeed(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return text.hashCode();
        }
    }

    private static int delete(CommandContext<CommandSourceStack> c, boolean confirmed) {
        String name = StringArgumentType.getString(c, "name");
        Worlds.World world = worlds().world(name);
        if (world == null) {
            return fail(c, "No world named " + name + "." + (VANILLA.contains(name) ? " Vanilla dimensions can't be deleted." : ""));
        }
        if (!confirmed) {
            c.getSource().sendSuccess(() -> Component.literal("This deletes " + name + " and everything built in it, forever. Players inside are sent to spawn. Type /mv delete "
                    + name + " confirm to do it.").withStyle(ChatFormatting.GOLD), false);
            return 0;
        }
        boolean filesGone = worlds().delete(world);
        return ok(c, "World " + name + " deleted." + (filesGone ? "" : " Its files will be removed at the next restart."));
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        List<String> lines = new ArrayList<>();
        lines.add("Worlds (go with /mvtp <world>):");
        lines.add(" - world, nether, end (vanilla, inventory " + Worlds.MAIN_GROUP + ")");
        for (Worlds.World world : worlds().worlds()) {
            ServerLevel level = worlds().level(world);
            int players = level == null ? 0 : level.players().size();
            lines.add(" - " + world.name() + ": " + world.type().id() + ", seed " + world.seed() + ", inventory " + world.inventoryGroup()
                    + ", " + players + " player" + (players == 1 ? "" : "s"));
        }
        if (worlds().worlds().isEmpty()) {
            lines.add("No extra worlds yet. Create one with /mv create <name> <type> [seed].");
        }
        return ok(c, String.join("\n", lines));
    }

    private static int setSpawn(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Worlds.World world = worlds().worldOf(player.level());
        if (world == null) {
            return fail(c, "You're not in a /mv world. For the main world, use the vanilla /setworldspawn.");
        }
        worlds().setSpawn(world, new Worlds.Spawn(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        return ok(c, "Spawn of " + world.name() + " set here.");
    }

    private static int teleport(CommandContext<CommandSourceStack> c, ServerPlayer player) {
        String name = StringArgumentType.getString(c, "world");
        if (!VANILLA.contains(name) && worlds().world(name) == null) {
            return fail(c, "No world named " + name + ". See /mvtp suggestions or ask an admin.");
        }
        boolean other = c.getSource().getPlayer() != player;
        if (other) {
            // Admin sending someone else: no warm-up.
            Teleports.Destination dest = worlds().spawnDestination(name);
            if (dest == null) {
                return fail(c, "World " + name + " is not loaded.");
            }
            player.stopRiding();
            player.teleportTo(dest.level(), dest.pos().x, dest.pos().y, dest.pos().z, Set.of(), dest.yRot(), dest.xRot(), true);
            player.sendSystemMessage(Component.literal("You were sent to " + name + ".").withStyle(ChatFormatting.GREEN));
            return ok(c, "Sent " + player.getGameProfile().name() + " to " + name + ".");
        }
        return Teleports.start(player, name, () -> worlds().spawnDestination(name)) ? 1 : 0;
    }

    // ---------------------------------------------------------------- inventory groups

    /** Group names in use, for suggestions: main plus every world's group. */
    private static List<String> groupNames() {
        return Stream.concat(Stream.of(Worlds.MAIN_GROUP), worlds().worlds().stream().map(Worlds.World::inventoryGroup)).distinct().sorted().toList();
    }

    private static int listGroups(CommandContext<CommandSourceStack> c) {
        List<String> lines = new ArrayList<>();
        lines.add("Inventory groups (worlds in a group share one inventory):");
        for (String group : groupNames()) {
            List<String> members = new ArrayList<>();
            if (group.equals(Worlds.MAIN_GROUP)) {
                members.addAll(VANILLA);
            }
            worlds().worlds().stream().filter(w -> w.inventoryGroup().equals(group)).forEach(w -> members.add(w.name()));
            lines.add(" - " + group + ": " + String.join(", ", members));
        }
        lines.add("Change one with /mv group <world> <group>.");
        return ok(c, String.join("\n", lines));
    }

    private static int showGroup(CommandContext<CommandSourceStack> c) {
        Worlds.World world = ourWorld(c);
        if (world == null) {
            return 0;
        }
        return ok(c, world.name() + " uses the inventory of group " + world.inventoryGroup() + ".");
    }

    private static int setGroup(CommandContext<CommandSourceStack> c) {
        Worlds.World world = ourWorld(c);
        if (world == null) {
            return 0;
        }
        String group = StringArgumentType.getString(c, "group");
        if (!NAME.matcher(group).matches() || VANILLA.contains(group)) {
            return fail(c, "Group names may only use lowercase letters, numbers, _ and - (max 32). Use " + Worlds.MAIN_GROUP
                    + " to share the main world's inventory.");
        }
        if (group.equals(world.inventoryGroup())) {
            return fail(c, world.name() + " is already in group " + group + ".");
        }
        worlds().setGroup(world, group);
        // Players inside switch right away; everyone else switches when they next arrive there.
        WorldInventories inventories = WorldInventories.get();
        if (inventories != null) {
            c.getSource().getServer().getPlayerList().getPlayers().forEach(inventories::sync);
        }
        return ok(c, world.name() + " now uses the inventory of group " + group + ". What players had in the old group is kept"
                + " and comes back if you move the world back.");
    }

    private static Worlds.@Nullable World ourWorld(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "world");
        Worlds.World world = worlds().world(name);
        if (world == null) {
            fail(c, VANILLA.contains(name) ? "world, nether and end always use group " + Worlds.MAIN_GROUP + "." : "No world named " + name + ".");
        }
        return world;
    }

    // ---------------------------------------------------------------- portals

    private static int createPortal(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(c, "name");
        String target = StringArgumentType.getString(c, "world");
        if (!NAME.matcher(name).matches()) {
            return fail(c, "Portal names may only use lowercase letters, numbers, _ and - (max 32).");
        }
        if (portals().portal(name) != null) {
            return fail(c, "A portal named " + name + " already exists.");
        }
        if (!VANILLA.contains(target) && worlds().world(target) == null) {
            return fail(c, "No world named " + target + ".");
        }
        PlotManager plots = PlotManager.get();
        PlotManager.Selection sel = plots == null ? null : plots.selection(player.getUUID());
        if (sel == null || sel.pos1() == null || sel.pos2() == null || sel.dimension() == null) {
            return fail(c, "Select the portal first: /plot wand (or /plot pos1 and /plot pos2) on two opposite corners.");
        }
        BlockPos a = sel.pos1(), b = sel.pos2();
        Portals.Entry portal = new Portals.Entry(name, sel.dimension(),
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()), target, null);
        String overlap = portals().add(portal);
        if (overlap != null) {
            return fail(c, "That area overlaps portal " + overlap + ".");
        }
        return ok(c, "✓ Portal " + name + " created: " + portal.describe() + ". It leads to the spawn of " + target
                + ". Nether/End portals inside it go there too. Use /mv portal setdest " + name + " to land somewhere else.");
    }

    private static int setPortalDest(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Portals.Entry portal = portals().portal(StringArgumentType.getString(c, "name"));
        if (portal == null) {
            return fail(c, "No portal named " + StringArgumentType.getString(c, "name") + ".");
        }
        String world = worlds().nameOf(player.level());
        if (world == null) {
            return fail(c, "Portals can only lead to world, nether, end or a /mv world.");
        }
        portals().setDestination(portal, world, new Worlds.Spawn(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        return ok(c, "Portal " + portal.name() + " now leads here (" + world + "). Tip: if you stand inside another portal's area,"
                + " players arrive in it and have to step out and back in to use it.");
    }

    private static int deletePortal(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        return portals().remove(name) ? ok(c, "Portal " + name + " deleted. The blocks were not changed.") : fail(c, "No portal named " + name + ".");
    }

    private static int listPortals(CommandContext<CommandSourceStack> c) {
        if (portals().portals().isEmpty()) {
            return ok(c, "There are no portals. Select an area with /plot wand, then /mv portal create <name> <world>.");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Portals (" + portals().portals().size() + "):");
        for (Portals.Entry portal : portals().portals()) {
            String to = portal.dest() == null ? portal.target() + " spawn"
                    : String.format("%s %.0f %.0f %.0f", portal.target(), portal.dest().x(), portal.dest().y(), portal.dest().z());
            lines.add(" - " + portal.name() + ": " + portal.describe() + " -> " + to);
        }
        return ok(c, String.join("\n", lines));
    }

    private static Portals portals() {
        Portals portals = Portals.get();
        if (portals == null) {
            throw new IllegalStateException("SafePlots is not running");
        }
        return portals;
    }

    private static Worlds worlds() {
        Worlds worlds = Worlds.get();
        if (worlds == null) {
            throw new IllegalStateException("SafePlots is not running");
        }
        return worlds;
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
