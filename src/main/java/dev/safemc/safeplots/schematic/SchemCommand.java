package dev.safemc.safeplots.schematic;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;

/**
 * /schem list|load|info|rotate|paste|undo. Admins only. Put {@code .schem} files (from WorldEdit, Axiom,
 * schematic websites, ...) in {@code <server>/schematics/}, load one, then paste it where you stand.
 */
public final class SchemCommand {
    private static final int LIST_LIMIT = 60;

    private SchemCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("schem").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(SchemCommand::list)
                .then(Commands.literal("list").executes(SchemCommand::list))
                .then(Commands.literal("load")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        schematics().available().stream().map(SchemCommand::quoteIfNeeded), b))
                                .executes(SchemCommand::load)))
                .then(Commands.literal("info").executes(SchemCommand::info))
                .then(Commands.literal("rotate")
                        .then(Commands.argument("degrees", IntegerArgumentType.integer(-270, 270))
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(List.of("90", "180", "270", "-90"), b))
                                .executes(SchemCommand::rotate)))
                .then(Commands.literal("paste")
                        .executes(c -> paste(c, false))
                        .then(Commands.literal("noair").executes(c -> paste(c, true))))
                .then(Commands.literal("undo").executes(SchemCommand::undo)));
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        List<String> names = schematics().available();
        if (names.isEmpty()) {
            return ok(c, "No schematics yet. Put .schem files in " + schematics().dir() + ", then /schem load <name>.");
        }
        String shown = String.join(", ", names.subList(0, Math.min(names.size(), LIST_LIMIT)));
        return ok(c, "Schematics (" + names.size() + "): " + shown + (names.size() > LIST_LIMIT ? ", ..." : "")
                + "\nLoad one with /schem load <name>.");
    }

    private static int load(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(c, "name");
        Path file = schematics().resolve(name);
        if (file == null) {
            return fail(c, "No schematic \"" + name + "\" in " + schematics().dir() + ". See /schem list.");
        }
        CommandSourceStack src = c.getSource();
        MinecraftServer server = src.getServer();
        var registries = server.registryAccess();
        src.sendSuccess(() -> Component.literal("Loading " + name + "...").withStyle(ChatFormatting.YELLOW), false);
        // Reading and decoding a big file takes a moment; do it off the server thread.
        CompletableFuture.supplyAsync(() -> {
            try {
                return SchematicReader.read(file, name, registries, Schematics.MAX_VOLUME);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }).whenComplete((schematic, error) -> server.execute(() -> {
            Schematics manager = Schematics.get();
            if (manager == null) {
                return;
            }
            if (error != null) {
                Throwable cause = error;
                while (cause.getCause() != null && !(cause instanceof java.io.IOException)) {
                    cause = cause.getCause();
                }
                if (cause instanceof java.io.IOException) {
                    // Expected problems (too big, damaged, wrong format): one line, no stack trace.
                    LogUtils.getLogger().warn("[SafePlots] Could not load schematic {}: {}", file, cause.getMessage());
                } else {
                    LogUtils.getLogger().error("[SafePlots] Could not load schematic {}", file, cause);
                }
                src.sendFailure(Component.literal("Could not load " + name + ": " + cause.getMessage()));
                return;
            }
            manager.setClipboard(player.getUUID(), schematic);
            src.sendSuccess(() -> Component.literal("✓ Loaded " + name + " (" + schematic.size() + ")." + warnings(schematic)
                    + " Stand where it should go and run /schem paste.").withStyle(ChatFormatting.GREEN), false);
        }));
        return 1;
    }

    private static String warnings(Schematic s) {
        String w = "";
        if (s.unknownBlocks() > 0) {
            w += " " + s.unknownBlocks() + " unknown block type(s) (from mods that aren't installed) will be air.";
        }
        if (s.skippedEntities() > 0) {
            w += " " + s.skippedEntities() + " entities (item frames, armor stands, ...) are not pasted.";
        }
        return w;
    }

    private static int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Schematics.Clipboard clip = schematics().clipboard(player.getUUID());
        if (clip == null) {
            return fail(c, "Nothing loaded. Use /schem load <name>.");
        }
        Schematic s = clip.schematic();
        BlockPos[] box = Schematics.bounds(clip, player.blockPosition());
        return ok(c, "Loaded: " + s.name() + " (" + s.size() + ", " + s.blockEntities().size() + " block entities), rotated "
                + degrees(clip.rotation()) + "°.\nPasting here would fill " + box[0].toShortString() + " to " + box[1].toShortString()
                + ". Undo history: " + schematics().undoCount(player.getUUID()) + "/" + Schematics.MAX_UNDO + ".");
    }

    private static int rotate(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        int degrees = IntegerArgumentType.getInteger(c, "degrees");
        Rotation rotation = switch (Math.floorMod(degrees, 360)) {
            case 0 -> Rotation.NONE;
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> null;
        };
        if (rotation == null) {
            return fail(c, "Rotate by 90, 180, 270 or -90 degrees.");
        }
        Schematics.Clipboard clip = schematics().rotate(player.getUUID(), rotation);
        if (clip == null) {
            return fail(c, "Nothing loaded. Use /schem load <name>.");
        }
        return ok(c, clip.schematic().name() + " is now rotated " + degrees(clip.rotation()) + "° clockwise (seen from above).");
    }

    private static int paste(CommandContext<CommandSourceStack> c, boolean skipAir) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Schematics manager = schematics();
        Schematics.Clipboard clip = manager.clipboard(player.getUUID());
        if (clip == null) {
            return fail(c, "Nothing loaded. Use /schem load <name>.");
        }
        if (manager.busy(player.getUUID())) {
            return fail(c, "Your last paste or undo is still running. Wait for it to finish.");
        }
        BlockPos at = player.blockPosition();
        BlockPos[] box = Schematics.bounds(clip, at);
        CommandSourceStack src = c.getSource();
        String name = clip.schematic().name();
        src.sendSuccess(() -> Component.literal("Pasting " + name + " from " + box[0].toShortString() + " to " + box[1].toShortString()
                + (clip.schematic().volume() > 200_000 ? ". Big builds appear over a few seconds." : "...")).withStyle(ChatFormatting.YELLOW), false);
        manager.paste(player, clip, at, skipAir, changed -> src.sendSuccess(() -> Component.literal(
                "✓ Pasted " + name + ": " + changed + " blocks changed. /schem undo puts them back.").withStyle(ChatFormatting.GREEN), false));
        return 1;
    }

    private static int undo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Schematics manager = schematics();
        if (manager.busy(player.getUUID())) {
            return fail(c, "Your last paste or undo is still running. Wait for it to finish.");
        }
        CommandSourceStack src = c.getSource();
        String what = manager.undo(player.getUUID(), restored -> src.sendSuccess(() -> Component.literal(
                "✓ Undone: " + restored + " blocks restored.").withStyle(ChatFormatting.GREEN), false));
        if (what == null) {
            return fail(c, "Nothing to undo. (Undo history is kept for your last " + Schematics.MAX_UNDO + " pastes until a restart.)");
        }
        return ok(c, "Undoing the paste of " + what + "...");
    }

    private static int degrees(Rotation rotation) {
        return switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 90;
            case CLOCKWISE_180 -> 180;
            case COUNTERCLOCKWISE_90 -> 270;
        };
    }

    private static String quoteIfNeeded(String name) {
        return name.matches("[A-Za-z0-9_.+-]+") ? name : "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Schematics schematics() {
        Schematics manager = Schematics.get();
        if (manager == null) {
            throw new IllegalStateException("SafePlots is not running");
        }
        return manager;
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
