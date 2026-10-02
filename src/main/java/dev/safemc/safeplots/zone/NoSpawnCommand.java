package dev.safemc.safeplots.zone;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.safemc.safeplots.plot.PlotManager;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /nomobspawn create|delete|list|here. Admins only. Zones use the same selection as plots
 * (/plot wand, /plot pos1, /plot pos2) and cover every height.
 */
public final class NoSpawnCommand {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private NoSpawnCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("nomobspawn").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(NoSpawnCommand::list)
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word()).executes(NoSpawnCommand::create)))
                .then(Commands.literal("delete")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(zones().zones().stream().map(NoSpawnZones.Zone::name), b))
                                .executes(NoSpawnCommand::delete)))
                .then(Commands.literal("list").executes(NoSpawnCommand::list))
                .then(Commands.literal("here").executes(NoSpawnCommand::here)));
    }

    private static int create(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(c, "name");
        if (!NAME.matcher(name).matches()) {
            return fail(c, "Zone names may only use letters, numbers, _ and - (max 32).");
        }
        if (zones().zone(name) != null) {
            return fail(c, "A zone named " + name + " already exists. Delete it first to redraw it.");
        }
        PlotManager plots = PlotManager.get();
        PlotManager.Selection sel = plots == null ? null : plots.selection(player.getUUID());
        if (sel == null || sel.pos1() == null || sel.pos2() == null || sel.dimension() == null) {
            return fail(c, "Select two corners first with /plot wand (or /plot pos1 and /plot pos2).");
        }
        NoSpawnZones.Zone zone = new NoSpawnZones.Zone(name, sel.dimension(),
                Math.min(sel.pos1().getX(), sel.pos2().getX()), Math.min(sel.pos1().getZ(), sel.pos2().getZ()),
                Math.max(sel.pos1().getX(), sel.pos2().getX()), Math.max(sel.pos1().getZ(), sel.pos2().getZ()));
        zones().add(zone);
        return ok(c, "✓ No-mob-spawn zone " + name + " created: " + zone.describe()
                + ". Hostile mobs won't spawn there anymore (any height). Mobs already inside stay; kill them once.");
    }

    private static int delete(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        return zones().remove(name) ? ok(c, "Zone " + name + " deleted. Hostile mobs can spawn there again.")
                : fail(c, "No zone named " + name + ".");
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        if (zones().zones().isEmpty()) {
            return ok(c, "There are no no-mob-spawn zones. Select an area with /plot wand, then /nomobspawn create <name>.");
        }
        List<String> lines = new ArrayList<>();
        lines.add("No-mob-spawn zones (" + zones().zones().size() + "):");
        for (NoSpawnZones.Zone zone : zones().zones()) {
            lines.add(" - " + zone.name() + ": " + zone.describe());
        }
        return ok(c, String.join("\n", lines));
    }

    private static int here(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        NoSpawnZones.Zone zone = zones().zoneAt(player.level(), player.getX(), player.getZ());
        return zone == null ? ok(c, "You are not in a no-mob-spawn zone.")
                : ok(c, "You are in no-mob-spawn zone " + zone.name() + ": " + zone.describe() + ".");
    }

    private static NoSpawnZones zones() {
        NoSpawnZones zones = NoSpawnZones.get();
        if (zones == null) {
            throw new IllegalStateException("SafePlots is not running");
        }
        return zones;
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
