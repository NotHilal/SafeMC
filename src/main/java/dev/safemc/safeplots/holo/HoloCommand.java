package dev.safemc.safeplots.holo;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.safemc.safeplots.world.Worlds;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * /holo: floating text. Admins only. Text takes {@code &} color codes ({@code &6gold}, {@code &l}bold,
 * {@code &#FF55AA}) and {@code \n} for a new line.
 */
public final class HoloCommand {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final int MAX_LINES = 20;

    private HoloCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("holo").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(HoloCommand::list)
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(HoloCommand::create))))
                .then(Commands.literal("edit")
                        .then(holo().then(Commands.argument("text", StringArgumentType.greedyString()).executes(HoloCommand::edit))))
                .then(Commands.literal("addline")
                        .then(holo().then(Commands.argument("text", StringArgumentType.greedyString()).executes(HoloCommand::addLine))))
                .then(Commands.literal("setline")
                        .then(holo().then(Commands.argument("line", IntegerArgumentType.integer(1, MAX_LINES))
                                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(HoloCommand::setLine)))))
                .then(Commands.literal("removeline")
                        .then(holo().then(Commands.argument("line", IntegerArgumentType.integer(1, MAX_LINES)).executes(HoloCommand::removeLine))))
                .then(Commands.literal("movehere").then(holo().executes(HoloCommand::moveHere)))
                .then(Commands.literal("height")
                        .then(holo().then(Commands.argument("blocks", DoubleArgumentType.doubleArg(-50, 50)).executes(HoloCommand::height))))
                .then(Commands.literal("scale")
                        .then(holo().then(Commands.argument("size", FloatArgumentType.floatArg(0.25F, 10F)).executes(HoloCommand::scale))))
                .then(Commands.literal("background")
                        .then(holo().then(Commands.literal("on").executes(c -> background(c, true)))
                                .then(Commands.literal("off").executes(c -> background(c, false)))))
                .then(Commands.literal("tp").then(holo().executes(HoloCommand::tp)))
                .then(Commands.literal("delete").then(holo().executes(HoloCommand::delete)))
                .then(Commands.literal("list").executes(HoloCommand::list)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> holo() {
        return Commands.argument("name", StringArgumentType.word())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(holos().holos().stream().map(h -> h.name), b));
    }

    private static Holograms holos() {
        return Holograms.get();
    }

    private static Holograms.@Nullable Holo find(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        Holograms.Holo holo = holos().holo(name);
        if (holo == null) {
            fail(c, "No hologram named " + name + ". See /holo list.");
        }
        return holo;
    }

    private static int create(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(c, "name");
        if (!NAME.matcher(name).matches()) {
            return fail(c, "Hologram names may only use letters, numbers, _ and - (max 32).");
        }
        if (holos().holo(name) != null) {
            return fail(c, "A hologram named " + name + " already exists. Change it with /holo edit, or /holo delete it first.");
        }
        String text = StringArgumentType.getString(c, "text");
        if (Holograms.splitLines(text).size() > MAX_LINES) {
            return fail(c, "At most " + MAX_LINES + " lines.");
        }
        holos().create(name, player.level(), player.getX(), player.getEyeY(), player.getZ(), text);
        return ok(c, "✓ Hologram " + name + " created at your eye height. Step back to see it. Add lines with /holo addline "
                + name + " <text>, move it up or down with /holo height " + name + " <blocks>.");
    }

    private static int edit(CommandContext<CommandSourceStack> c) {
        Holograms.Holo holo = find(c);
        if (holo == null) {
            return 0;
        }
        var lines = Holograms.splitLines(StringArgumentType.getString(c, "text"));
        if (lines.size() > MAX_LINES) {
            return fail(c, "At most " + MAX_LINES + " lines.");
        }
        holo.lines.clear();
        holo.lines.addAll(lines);
        holos().changed(holo);
        return ok(c, "✓ Hologram " + holo.name + " changed.");
    }

    private static int addLine(CommandContext<CommandSourceStack> c) {
        Holograms.Holo holo = find(c);
        if (holo == null) {
            return 0;
        }
        var lines = Holograms.splitLines(StringArgumentType.getString(c, "text"));
        if (holo.lines.size() + lines.size() > MAX_LINES) {
            return fail(c, "At most " + MAX_LINES + " lines.");
        }
        holo.lines.addAll(lines);
        holos().changed(holo);
        return ok(c, "✓ Line added to " + holo.name + " (" + holo.lines.size() + " lines now).");
    }

    private static int setLine(CommandContext<CommandSourceStack> c) {
        Holograms.Holo holo = find(c);
        if (holo == null) {
            return 0;
        }
        int line = IntegerArgumentType.getInteger(c, "line");
        if (line > holo.lines.size()) {
            return fail(c, holo.name + " has " + holo.lines.size() + " line(s). Use /holo addline to add one.");
        }
        holo.lines.set(line - 1, StringArgumentType.getString(c, "text").replace("\\n", " "));
        holos().changed(holo);
        return ok(c, "✓ Line " + line + " of " + holo.name + " changed.");
    }

    private static int removeLine(CommandContext<CommandSourceStack> c) {
        Holograms.Holo holo = find(c);
        if (holo == null) {
            return 0;
        }
        int line = IntegerArgumentType.getInteger(c, "line");
        if (line > holo.lines.size()) {
            return fail(c, holo.name + " has " + holo.lines.size() + " line(s).");
        }
        if (holo.lines.size() == 1) {
            return fail(c, "That's its only line. Use /holo delete " + holo.name + " to remove the hologram.");
        }
        holo.lines.remove(line - 1);
        holos().changed(holo);
        return ok(c, "✓ Line " + line + " removed from " + holo.name + ".");
    }

    private static int moveHere(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Holograms.Holo holo = find(c);
        if (holo == null) {
            return 0;
        }
        holos().place(holo, player.level(), player.getX(), player.getEyeY(), player.getZ());
        return ok(c, "✓ Hologram " + holo.name + " moved to your eye height.");
    }

    private static int height(CommandContext<CommandSourceStack> c) {
        Holograms.Holo holo = find(c);
        if (holo == null) {
            return 0;
        }
        ServerLevel level = holos().level(holo);
        if (level == null) {
            return fail(c, "The world of " + holo.name + " isn't loaded.");
        }
        double blocks = DoubleArgumentType.getDouble(c, "blocks");
        holos().place(holo, level, holo.x, holo.y + blocks, holo.z);
        return ok(c, "✓ Hologram " + holo.name + " moved " + (blocks >= 0 ? "up " : "down ") + Math.abs(blocks) + " blocks.");
    }

    private static int scale(CommandContext<CommandSourceStack> c) {
        Holograms.Holo holo = find(c);
        if (holo == null) {
            return 0;
        }
        holo.scale = FloatArgumentType.getFloat(c, "size");
        holos().changed(holo);
        return ok(c, "✓ Hologram " + holo.name + " is now size " + holo.scale + " (1 is normal).");
    }

    private static int background(CommandContext<CommandSourceStack> c, boolean on) {
        Holograms.Holo holo = find(c);
        if (holo == null) {
            return 0;
        }
        holo.background = on;
        holos().changed(holo);
        return ok(c, on ? "✓ " + holo.name + " has a dark background box now." : "✓ " + holo.name + " has no background box now.");
    }

    private static int tp(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Holograms.Holo holo = find(c);
        if (holo == null) {
            return 0;
        }
        ServerLevel level = holos().level(holo);
        if (level == null) {
            return fail(c, "The world of " + holo.name + " isn't loaded.");
        }
        player.teleportTo(level, holo.x, holo.y - player.getEyeHeight(), holo.z + 3, Set.of(), 180.0F, 0.0F, true);
        return ok(c, "Teleported in front of " + holo.name + ".");
    }

    private static int delete(CommandContext<CommandSourceStack> c) {
        Holograms.Holo holo = find(c);
        if (holo == null) {
            return 0;
        }
        holos().delete(holo);
        return ok(c, "Hologram " + holo.name + " deleted.");
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        if (holos().holos().isEmpty()) {
            return ok(c, "There are no holograms. Make one with /holo create <name> <text>, e.g. /holo create welcome &6&lWelcome!");
        }
        MutableComponent out = Component.literal("Holograms (" + holos().holos().size() + "), click one to go there:").withStyle(ChatFormatting.GREEN);
        Worlds worlds = Worlds.get();
        for (Holograms.Holo holo : holos().holos()) {
            ServerLevel level = holos().level(holo);
            String world = level == null ? holo.dimension : worlds == null || worlds.nameOf(level) == null ? holo.dimension : worlds.nameOf(level);
            MutableComponent line = Component.literal("\n - " + holo.name + " ").withStyle(s -> s.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/holo tp " + holo.name)));
            line.append(Component.literal("(" + world + " " + (int) Math.floor(holo.x) + ", " + (int) Math.floor(holo.y) + ", "
                    + (int) Math.floor(holo.z) + "): ").withStyle(ChatFormatting.GRAY));
            line.append(Holograms.colored(holo.lines.getFirst()));
            out.append(line);
        }
        c.getSource().sendSuccess(() -> out, false);
        return holos().holos().size();
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
