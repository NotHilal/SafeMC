package dev.safemc.safeplots.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

/**
 * /mv create|delete|list|setspawn and /mvtp &lt;world&gt; [player] (admins), /hub (everyone, with the usual teleport
 * warm-up). Players get to other worlds by portal or /hub only.
 * {@code world}, {@code nether} and {@code end} are the vanilla dimensions. Also /killall [world] [all] (admins).
 */
public final class WorldCommand {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final List<String> VANILLA = List.of("world", "nether", "end");
    private static final List<String> GAME_MODES = List.of("survival", "creative", "adventure", "spectator", "none");

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
        d.register(Commands.literal("killall").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(c -> killAll(c, null, false))
                .then(Commands.argument("world", StringArgumentType.word()).suggests(ALL_WORLDS)
                        .executes(c -> killAll(c, StringArgumentType.getString(c, "world"), false))
                        .then(Commands.literal("all").executes(c -> killAll(c, StringArgumentType.getString(c, "world"), true)))));
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
                .then(Commands.literal("gamemode")
                        .then(Commands.argument("world", StringArgumentType.word()).suggests(OUR_WORLDS)
                                .executes(WorldCommand::showGameMode)
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(GAME_MODES, b))
                                        .executes(WorldCommand::setGameMode))))
                .then(Commands.literal("mobs")
                        .then(Commands.argument("world", StringArgumentType.word()).suggests(OUR_WORLDS)
                                .executes(WorldCommand::showMobs)
                                .then(Commands.literal("on").executes(c -> setMobs(c, true)))
                                .then(Commands.literal("off").executes(c -> setMobs(c, false)))))
                .then(Commands.literal("alwaysday")
                        .then(Commands.argument("world", StringArgumentType.word()).suggests(OUR_WORLDS)
                                .executes(WorldCommand::showAlwaysDay)
                                .then(Commands.literal("on").executes(c -> setAlwaysDay(c, true)))
                                .then(Commands.literal("off").executes(c -> setAlwaysDay(c, false)))))
                .then(Commands.literal("hunger")
                        .then(Commands.argument("world", StringArgumentType.word()).suggests(OUR_WORLDS)
                                .executes(WorldCommand::showHunger)
                                .then(Commands.literal("on").executes(c -> setHunger(c, true)))
                                .then(Commands.literal("off").executes(c -> setHunger(c, false)))))
                .then(Commands.literal("damage")
                        .then(Commands.argument("world", StringArgumentType.word()).suggests(OUR_WORLDS)
                                .executes(WorldCommand::showDamage)
                                .then(Commands.literal("on").executes(c -> setDamage(c, true)))
                                .then(Commands.literal("off").executes(c -> setDamage(c, false)))))
                .then(Commands.literal("topwinners")
                        .then(Commands.argument("world", StringArgumentType.word()).suggests(OUR_WORLDS)
                                .executes(WorldCommand::showTopWinners)
                                .then(Commands.literal("on").executes(c -> setTopWinners(c, true)))
                                .then(Commands.literal("off").executes(c -> setTopWinners(c, false)))))
                .then(Commands.literal("tnt")
                        .then(Commands.argument("world", StringArgumentType.word()).suggests(ALL_WORLDS)
                                .executes(WorldCommand::showTnt)
                                .then(Commands.literal("on").executes(c -> setTnt(c, true)))
                                .then(Commands.literal("off").executes(c -> setTnt(c, false)))))
                .then(Commands.literal("claims")
                        .then(Commands.argument("world", StringArgumentType.word()).suggests(ALL_WORLDS)
                                .executes(WorldCommand::showClaims)
                                .then(Commands.argument("plots", IntegerArgumentType.integer(0, 100))
                                        .executes(WorldCommand::setClaims))))
                .then(Commands.literal("crystals")
                        .then(Commands.argument("world", StringArgumentType.word()).suggests(ALL_WORLDS)
                                .executes(WorldCommand::showCrystals)
                                .then(Commands.literal("on").executes(c -> setCrystals(c, true)))
                                .then(Commands.literal("off").executes(c -> setCrystals(c, false)))))
                .then(Commands.literal("elevators")
                        .then(Commands.argument("world", StringArgumentType.word()).suggests(ALL_WORLDS)
                                .executes(WorldCommand::showElevators)
                                .then(Commands.literal("on").executes(c -> setElevators(c, true)))
                                .then(Commands.literal("off").executes(c -> setElevators(c, false)))))
                .then(Commands.literal("endportal")
                        .executes(WorldCommand::showEndPortal)
                        .then(Commands.literal("on").executes(c -> setEndPortal(c, true)))
                        .then(Commands.literal("off").executes(c -> setEndPortal(c, false))))
                .then(Commands.literal("sethub")
                        .executes(WorldCommand::showHub)
                        .then(Commands.argument("world", StringArgumentType.word()).suggests(ALL_WORLDS)
                                .executes(WorldCommand::setHub)))
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
                        .then(Commands.literal("water")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(PORTALS)
                                        .executes(WorldCommand::waterPortal)))
                        .then(Commands.literal("drain")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(PORTALS)
                                        .executes(WorldCommand::drainPortal)))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(PORTALS)
                                        .executes(WorldCommand::deletePortal)))
                        .then(Commands.literal("list").executes(WorldCommand::listPortals))));

        d.register(Commands.literal("mvtp").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("world", StringArgumentType.word()).suggests(ALL_WORLDS)
                        .executes(c -> teleport(c, c.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(c -> teleport(c, EntityArgument.getPlayer(c, "player"))))));

        // The hub is the main world unless an admin picked another with /mv sethub.
        d.register(Commands.literal("hub").executes(WorldCommand::hub));
    }

    private static int hub(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (worlds().levelByName(worlds().hub()) == null) {
            return fail(c, "The hub world (" + worlds().hub() + ") isn't loaded. Ask an admin.");
        }
        return Teleports.start(player, "the hub", () -> worlds().spawnDestination(worlds().hub())) ? 1 : 0;
    }

    // ---------------------------------------------------------------- per-world rules

    private static int showGameMode(CommandContext<CommandSourceStack> c) {
        Worlds.World world = ourWorld(c);
        if (world == null) {
            return 0;
        }
        return ok(c, world.gameMode() == null
                ? "Players keep their own game mode in " + world.name() + ". Set one with /mv gamemode " + world.name() + " <mode>."
                : "Everyone is in " + world.gameMode().getName() + " mode in " + world.name() + ".");
    }

    private static int setGameMode(CommandContext<CommandSourceStack> c) {
        Worlds.World world = ourWorld(c);
        if (world == null) {
            return 0;
        }
        String mode = StringArgumentType.getString(c, "mode");
        GameType gameMode = mode.equals("none") ? null : GameType.byName(mode, null);
        if (gameMode == null && !mode.equals("none")) {
            return fail(c, "Unknown game mode " + mode + ". Use survival, creative, adventure, spectator or none.");
        }
        Worlds.World updated = worlds().setGameMode(world, gameMode);
        WorldRules.applyToWorld(worlds(), updated);
        if (gameMode == null) {
            return ok(c, "✓ Players keep their own game mode in " + world.name() + " again.");
        }
        String message = "✓ Everyone arriving in " + world.name() + " is now put in " + gameMode.getName()
                + " mode, admins too. They get their own mode back when they leave.";
        List<String> sharing = sharesInventoryWith(updated);
        if (gameMode == GameType.CREATIVE && !sharing.isEmpty()) {
            message += "\n⚠ " + world.name() + " shares its inventory with " + String.join(", ", sharing)
                    + ", so players could carry creative items there. Give it its own inventory: /mv group " + world.name() + " " + world.name();
        }
        return ok(c, message);
    }

    /** Other worlds that use the same inventory as this one but not the same game mode. */
    private static List<String> sharesInventoryWith(Worlds.World world) {
        List<String> names = new ArrayList<>();
        if (world.inventoryGroup().equals(Worlds.MAIN_GROUP)) {
            names.addAll(VANILLA);
        }
        worlds().worlds().stream()
                .filter(w -> !w.name().equals(world.name()) && w.inventoryGroup().equals(world.inventoryGroup()) && w.gameMode() != world.gameMode())
                .forEach(w -> names.add(w.name()));
        return names;
    }

    private static int showMobs(CommandContext<CommandSourceStack> c) {
        Worlds.World world = ourWorld(c);
        if (world == null) {
            return 0;
        }
        return ok(c, world.mobs() ? "Mobs spawn normally in " + world.name() + "." : "No mobs spawn by themselves in " + world.name() + ".");
    }

    private static int setMobs(CommandContext<CommandSourceStack> c, boolean mobs) {
        Worlds.World world = ourWorld(c);
        if (world == null) {
            return 0;
        }
        worlds().setMobs(world, mobs);
        return ok(c, mobs ? "✓ Mobs spawn normally in " + world.name() + " again."
                : "✓ No mobs spawn by themselves in " + world.name() + " any more (spawn eggs, /summon and breeding still work)."
                + " Mobs already there stay until they despawn.");
    }

    private static Worlds.@Nullable World dayWorld(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "world");
        Worlds.World world = worlds().world(name);
        if (world == null) {
            fail(c, VANILLA.contains(name) ? "For " + name + " use /gamerule advance_time false and /weather clear instead."
                    : "No world named " + name + ".");
        }
        return world;
    }

    private static int showAlwaysDay(CommandContext<CommandSourceStack> c) {
        Worlds.World world = dayWorld(c);
        if (world == null) {
            return 0;
        }
        return ok(c, worlds().alwaysDay(world.name()) ? "It's always day without weather in " + world.name() + "."
                : "Day, night and weather go on normally in " + world.name() + ".");
    }

    private static int setAlwaysDay(CommandContext<CommandSourceStack> c, boolean on) {
        Worlds.World world = dayWorld(c);
        if (world == null) {
            return 0;
        }
        worlds().setAlwaysDay(world.name(), on);
        WorldRules.alwaysDayChanged(worlds(), world, on);
        return ok(c, on ? "✓ It's always day in " + world.name() + " now, with no rain, snow or thunder."
                + " Mobs still spawn as if it were the real time; use /mv mobs " + world.name() + " off to stop them."
                : "✓ Day, night and weather go on normally in " + world.name() + " again.");
    }

    private static Worlds.@Nullable World hungerWorld(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "world");
        Worlds.World world = worlds().world(name);
        if (world == null) {
            fail(c, VANILLA.contains(name) ? "Hunger can only be turned off in worlds made with /mv create or /mv import."
                    : "No world named " + name + ".");
        }
        return world;
    }

    private static int showHunger(CommandContext<CommandSourceStack> c) {
        Worlds.World world = hungerWorld(c);
        if (world == null) {
            return 0;
        }
        return ok(c, worlds().hunger(world.name()) ? "Players get hungry normally in " + world.name() + "."
                : "Hunger is off in " + world.name() + ": the food bar stays full.");
    }

    private static Worlds.@Nullable World damageWorld(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "world");
        Worlds.World world = worlds().world(name);
        if (world == null) {
            fail(c, VANILLA.contains(name) ? "Damage can only be turned off in worlds made with /mv create or /mv import."
                    : "No world named " + name + ".");
        }
        return world;
    }

    private static int showDamage(CommandContext<CommandSourceStack> c) {
        Worlds.World world = damageWorld(c);
        if (world == null) {
            return 0;
        }
        return ok(c, worlds().damage(world.name()) ? "Players take damage normally in " + world.name() + "."
                : "Damage is off in " + world.name() + ": nobody gets hurt there.");
    }

    private static int setDamage(CommandContext<CommandSourceStack> c, boolean on) {
        Worlds.World world = damageWorld(c);
        if (world == null) {
            return 0;
        }
        worlds().setDamage(world.name(), on);
        return ok(c, on ? "✓ Players take damage normally in " + world.name() + " again."
                : "✓ Damage is off in " + world.name() + ": nobody gets hurt (no PvP, mobs, falling, lava...). "
                        + "Falling into the void takes you back to its spawn; /kill still works.");
    }

    private static int showEndPortal(CommandContext<CommandSourceStack> c) {
        return ok(c, worlds().endPortal() ? "End portals work normally. /mv endportal off turns them off."
                : "End portals are off: eyes of ender can't go in the frames and lit portals don't take anyone to the End.");
    }

    private static int setEndPortal(CommandContext<CommandSourceStack> c, boolean on) {
        worlds().setEndPortal(on);
        return ok(c, on ? "✓ End portals work again."
                : "✓ End portals are off: eyes of ender can't go in the frames and lit portals don't take anyone to the End. "
                        + "/mvtp end still works for admins.");
    }

    private static Worlds.@Nullable World topWinnersWorld(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "world");
        Worlds.World world = worlds().world(name);
        if (world == null) {
            fail(c, VANILLA.contains(name) ? "The top-winners sidebar can only be shown in worlds made with /mv create or /mv import."
                    : "No world named " + name + ".");
        }
        return world;
    }

    private static int showTopWinners(CommandContext<CommandSourceStack> c) {
        Worlds.World world = topWinnersWorld(c);
        if (world == null) {
            return 0;
        }
        return ok(c, worlds().topWinners(world.name()) ? "Players in " + world.name() + " see the top winner of each minigame on the side of their screen."
                : "No top-winners sidebar in " + world.name() + ". /mv topwinners " + world.name() + " on adds it.");
    }

    private static int setTopWinners(CommandContext<CommandSourceStack> c, boolean on) {
        Worlds.World world = topWinnersWorld(c);
        if (world == null) {
            return 0;
        }
        worlds().setTopWinners(world.name(), on);
        HubBoard.update(c.getSource().getServer());
        return ok(c, on ? "✓ Players in " + world.name() + " now see the top winner of each minigame on the side of their screen."
                : "✓ The top-winners sidebar is off in " + world.name() + ".");
    }

    private static int setHunger(CommandContext<CommandSourceStack> c, boolean on) {
        Worlds.World world = hungerWorld(c);
        if (world == null) {
            return 0;
        }
        worlds().setHunger(world.name(), on);
        ServerLevel level = worlds().level(world);
        if (!on && level != null) {
            level.players().forEach(WorldRules::feed);
        }
        return ok(c, on ? "✓ Players get hungry normally in " + world.name() + " again."
                : "✓ Hunger is off in " + world.name() + ": the food bar stays full, nobody starves.");
    }

    private static int showTnt(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "world");
        if (!VANILLA.contains(name) && worlds().world(name) == null) {
            return fail(c, "No world named " + name + ".");
        }
        return ok(c, worlds().tnt(name) ? "TNT explodes in " + name + "." : "TNT doesn't explode in " + name + " (the default).");
    }

    private static int setTnt(CommandContext<CommandSourceStack> c, boolean tnt) {
        String name = StringArgumentType.getString(c, "world");
        if (!VANILLA.contains(name) && worlds().world(name) == null) {
            return fail(c, "No world named " + name + ".");
        }
        worlds().setTnt(name, tnt);
        return ok(c, tnt ? "✓ TNT explodes in " + name + " now."
                : "✓ TNT no longer explodes in " + name + ": lit TNT and TNT minecarts just disappear, without damage.");
    }

    private static int showClaims(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "world");
        if (!VANILLA.contains(name) && worlds().world(name) == null) {
            return fail(c, "No world named " + name + ".");
        }
        Integer limit = worlds().claimLimit(name);
        int n = limit == null ? 1 : limit;
        if (n == 0) {
            return ok(c, "Players can't claim plots in " + name + ". Change it with /mv claims " + name + " <number>.");
        }
        return ok(c, "Each player may have " + n + " plot" + (n == 1 ? "" : "s") + " in " + name
                + (limit == null ? " (the default)" : "") + ", with /plot claimhere or signs."
                + " Change it with /mv claims " + name + " <number> (0: no plots).");
    }

    private static int setClaims(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "world");
        if (!VANILLA.contains(name) && worlds().world(name) == null) {
            return fail(c, "No world named " + name + ".");
        }
        int n = IntegerArgumentType.getInteger(c, "plots");
        worlds().setClaimLimit(name, n == 1 ? null : n);
        if (n == 0) {
            return ok(c, "✓ Players can't claim plots in " + name + " now. Plots already there stay;"
                    + " admins can still make plots and give them with /plot setowner.");
        }
        return ok(c, "✓ Each player may now have " + n + " plot" + (n == 1 ? "" : "s") + " in " + name
                + " (with /plot claimhere or signs). Players with a higher /plot setlimit keep theirs."
                + " Plots they already have stay if you lower it later.");
    }

    private static int showCrystals(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "world");
        if (!VANILLA.contains(name) && worlds().world(name) == null) {
            return fail(c, "No world named " + name + ".");
        }
        return ok(c, worlds().crystals(name) ? "End crystals explode in " + name + "."
                : "End crystals don't explode in " + name + " (the default).");
    }

    private static int setCrystals(CommandContext<CommandSourceStack> c, boolean on) {
        String name = StringArgumentType.getString(c, "world");
        if (!VANILLA.contains(name) && worlds().world(name) == null) {
            return fail(c, "No world named " + name + ".");
        }
        worlds().setCrystals(name, on);
        return ok(c, on ? "✓ End crystals explode in " + name + " now."
                : "✓ End crystals no longer explode in " + name + ": they just break, without damage.");
    }

    private static int showElevators(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "world");
        if (!VANILLA.contains(name) && worlds().world(name) == null) {
            return fail(c, "No world named " + name + ".");
        }
        return ok(c, worlds().elevators(name) ? "Coal blocks are elevators in " + name + " (jump: up, sneak: down)."
                : "Coal blocks are normal in " + name + ". Turn elevators on with /mv elevators " + name + " on.");
    }

    private static int setElevators(CommandContext<CommandSourceStack> c, boolean on) {
        String name = StringArgumentType.getString(c, "world");
        if (!VANILLA.contains(name) && worlds().world(name) == null) {
            return fail(c, "No world named " + name + ".");
        }
        worlds().setElevators(name, on);
        return ok(c, on ? "✓ Coal blocks are elevators in " + name + " now: jump on one to go to the next one straight above,"
                + " sneak to go down. They never take anyone into a plot they can't build in."
                : "✓ Coal blocks are normal in " + name + " now.");
    }

    private static int showHub(CommandContext<CommandSourceStack> c) {
        return ok(c, "The hub is " + worlds().hub() + ". /hub goes to its spawn and new players start there."
                + " Change it with /mv sethub <world>.");
    }

    private static int setHub(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "world");
        if (!VANILLA.contains(name) && worlds().world(name) == null) {
            return fail(c, "No world named " + name + ".");
        }
        worlds().setHub(name);
        return ok(c, "✓ The hub is now " + name + ". /hub goes to its spawn (set it with /mv setspawn while standing there),"
                + " and players joining for the first time start there.");
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
        lines.add("Worlds:");
        for (String name : Stream.concat(VANILLA.stream(), worlds().worlds().stream().map(Worlds.World::name)).toList()) {
            ServerLevel level = worlds().levelByName(name);
            List<String> players = level == null ? List.of() : level.players().stream().map(p -> p.getGameProfile().name()).sorted().toList();
            lines.add(" - " + name + ": " + (players.isEmpty() ? "nobody" : String.join(", ", players)));
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
            return fail(c, "No world named " + name + ". See /mv list.");
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
            fail(c, VANILLA.contains(name) ? "world, nether and end are the vanilla worlds: this only works for worlds made with /mv create or /mv import"
                    + " (they always use inventory group " + Worlds.MAIN_GROUP + ")." : "No world named " + name + ".");
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
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()), target, null, false);
        String overlap = portals().add(portal);
        if (overlap != null) {
            return fail(c, "That area overlaps portal " + overlap + ".");
        }
        return ok(c, "✓ Portal " + name + " created: " + portal.describe() + ". It leads to the spawn of " + target
                + ". Nether/End portals inside it go there too. Use /mv portal setdest " + name + " to land somewhere else,"
                + " or /mv portal water " + name + " to fill it with water.");
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

    private static int waterPortal(CommandContext<CommandSourceStack> c) {
        Portals.Entry portal = portals().portal(StringArgumentType.getString(c, "name"));
        if (portal == null) {
            return fail(c, "No portal named " + StringArgumentType.getString(c, "name") + ".");
        }
        ServerLevel level = portals().level(portal);
        if (level == null) {
            return fail(c, "The world of portal " + portal.name() + " isn't loaded.");
        }
        if (Portals.waterEvaporates(level, portal)) {
            return fail(c, "Water evaporates in this world, so it can't hold a water portal.");
        }
        if (portal.volume() > Portals.MAX_WATER_BLOCKS) {
            return fail(c, "Portal " + portal.name() + " is too big to fill with water (" + portal.volume() + " blocks, max "
                    + Portals.MAX_WATER_BLOCKS + "). Select just the opening of the frame and create it again.");
        }
        int filled = portals().fillWater(portal, level);
        return ok(c, "✓ Portal " + portal.name() + " is now a water portal (" + filled + " blocks filled). The water stays inside"
                + " the portal area; players who walk in are sent on right away. Undo with /mv portal drain " + portal.name() + ".");
    }

    private static int drainPortal(CommandContext<CommandSourceStack> c) {
        Portals.Entry portal = portals().portal(StringArgumentType.getString(c, "name"));
        if (portal == null) {
            return fail(c, "No portal named " + StringArgumentType.getString(c, "name") + ".");
        }
        int drained = portals().drainWater(portal, portals().level(portal));
        return ok(c, "Portal " + portal.name() + " drained (" + drained + " water blocks removed). It's a normal portal again.");
    }

    private static int deletePortal(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        Portals.Entry portal = portals().portal(name);
        if (portal == null) {
            return fail(c, "No portal named " + name + ".");
        }
        // Without the portal the water would flow out, so a water portal is drained first.
        int drained = portal.water() ? portals().drainWater(portal, portals().level(portal)) : 0;
        portals().remove(name);
        return ok(c, drained > 0 ? "Portal " + name + " deleted and its water removed. Other blocks were not changed."
                : "Portal " + name + " deleted. The blocks were not changed.");
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
            lines.add(" - " + portal.name() + (portal.water() ? " (water)" : "") + ": " + portal.describe() + " -> " + to);
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

    /**
     * Removes the hostile mobs (or with {@code everyMob}, all mobs) of a world, without drops. Pets, mobs with a
     * name tag, leashed mobs and BedWars shop keepers are always spared. Only loaded chunks have entities to remove.
     */
    private static int killAll(CommandContext<CommandSourceStack> c, @Nullable String name, boolean everyMob) {
        ServerLevel level;
        if (name == null) {
            level = c.getSource().getLevel();
            name = worlds().nameOf(level);
            if (name == null) {
                name = level.dimension().identifier().toString();
            }
        } else {
            level = worlds().levelByName(name);
            if (level == null) {
                return fail(c, "No world named " + name + ".");
            }
        }
        List<Entity> doomed = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Mob mob && (everyMob || mob instanceof Enemy) && !spared(mob)) {
                doomed.add(mob);
            }
        }
        doomed.forEach(Entity::discard);
        int n = doomed.size();
        return ok(c, "✓ Removed " + n + (everyMob ? " mob" : " hostile mob") + (n == 1 ? "" : "s") + " in " + name
                + ". Pets, named and leashed mobs were spared; mobs in unloaded areas aren't affected.");
    }

    private static boolean spared(Mob mob) {
        return mob.hasCustomName() || mob.isLeashed() || mob.entityTags().contains("safebedwars")
                || (mob instanceof OwnableEntity pet && pet.getOwnerReference() != null);
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
