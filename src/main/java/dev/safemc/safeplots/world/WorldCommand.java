package dev.safemc.safeplots.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
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

    private WorldCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("mv").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(WorldCommand::list)
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Worlds.Type.values()).map(Worlds.Type::id), b))
                                        .executes(c -> create(c, null))
                                        .then(Commands.argument("seed", StringArgumentType.word())
                                                .executes(c -> create(c, StringArgumentType.getString(c, "seed")))))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(OUR_WORLDS)
                                .executes(c -> delete(c, false))
                                .then(Commands.literal("confirm").executes(c -> delete(c, true)))))
                .then(Commands.literal("list").executes(WorldCommand::list))
                .then(Commands.literal("setspawn").executes(WorldCommand::setSpawn)));

        d.register(Commands.literal("mvtp")
                .then(Commands.argument("world", StringArgumentType.word()).suggests(ALL_WORLDS)
                        .executes(c -> teleport(c, c.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(c -> teleport(c, EntityArgument.getPlayer(c, "player"))))));
    }

    private static int create(CommandContext<CommandSourceStack> c, @Nullable String seedText) {
        String name = StringArgumentType.getString(c, "name");
        if (!NAME.matcher(name).matches()) {
            return fail(c, "World names may only use lowercase letters, numbers, _ and - (max 32).");
        }
        if (VANILLA.contains(name)) {
            return fail(c, name + " is the name of a vanilla dimension. Pick another name.");
        }
        if (worlds().world(name) != null) {
            return fail(c, "A world named " + name + " already exists.");
        }
        if (worlds().isPendingDelete(name)) {
            return fail(c, "The old world " + name + " is still being deleted. Restart the server or pick another name.");
        }
        Worlds.Type type = Worlds.Type.parse(StringArgumentType.getString(c, "type"));
        if (type == null) {
            return fail(c, "Unknown type. Use one of: " + String.join(", ", Arrays.stream(Worlds.Type.values()).map(Worlds.Type::id).toList()) + ".");
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
        lines.add(" - world, nether, end (vanilla)");
        for (Worlds.World world : worlds().worlds()) {
            ServerLevel level = worlds().level(world);
            int players = level == null ? 0 : level.players().size();
            lines.add(" - " + world.name() + ": " + world.type().id() + ", seed " + world.seed() + ", " + players + " player" + (players == 1 ? "" : "s"));
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
        MinecraftServer server = c.getSource().getServer();
        if (!VANILLA.contains(name) && worlds().world(name) == null) {
            return fail(c, "No world named " + name + ". See /mvtp suggestions or ask an admin.");
        }
        boolean other = c.getSource().getPlayer() != player;
        if (other) {
            // Admin sending someone else: no warm-up.
            Teleports.Destination dest = destination(server, name);
            if (dest == null) {
                return fail(c, "World " + name + " is not loaded.");
            }
            player.stopRiding();
            player.teleportTo(dest.level(), dest.pos().x, dest.pos().y, dest.pos().z, Set.of(), dest.yRot(), dest.xRot(), true);
            player.sendSystemMessage(Component.literal("You were sent to " + name + ".").withStyle(ChatFormatting.GREEN));
            return ok(c, "Sent " + player.getGameProfile().name() + " to " + name + ".");
        }
        return Teleports.start(player, name, () -> destination(server, name)) ? 1 : 0;
    }

    /** The spawn of a world, evaluated when the warm-up ends (the world may have been deleted meanwhile). */
    private static Teleports.@Nullable Destination destination(MinecraftServer server, String name) {
        ServerLevel vanilla = switch (name) {
            case "world" -> server.overworld();
            case "nether" -> server.getLevel(Level.NETHER);
            case "end" -> server.getLevel(Level.END);
            default -> null;
        };
        if (vanilla != null) {
            if (vanilla == server.overworld()) {
                LevelData.RespawnData spawn = server.getRespawnData();
                BlockPos pos = vanilla.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.pos());
                return new Teleports.Destination(vanilla, Vec3.atBottomCenterOf(pos), spawn.yaw(), spawn.pitch());
            }
            Worlds.Spawn spawn = worlds().vanillaSpawn(vanilla);
            return new Teleports.Destination(vanilla, Worlds.pos(spawn), spawn.yRot(), spawn.xRot());
        }
        Worlds.World world = worlds().world(name);
        ServerLevel level = world == null ? null : worlds().level(world);
        if (level == null) {
            return null;
        }
        Worlds.Spawn spawn = worlds().spawn(world, level);
        return new Teleports.Destination(level, Worlds.pos(spawn), spawn.yRot(), spawn.xRot());
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
