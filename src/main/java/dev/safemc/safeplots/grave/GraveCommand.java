package dev.safemc.safeplots.grave;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

/**
 * {@code /grave list} for players; {@code /grave list <player>} and {@code /grave restore <id>} for admins
 * (restore gives the items to the owner directly, for graves that can't be reached).
 */
public final class GraveCommand {
    private static final Predicate<CommandSourceStack> ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private GraveCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("grave")
                .then(Commands.literal("list")
                        .executes(GraveCommand::listOwn)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile()).requires(ADMIN)
                                .executes(GraveCommand::listOther)))
                .then(Commands.literal("restore").requires(ADMIN)
                        .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(GraveCommand::restore))));
    }

    private static int listOwn(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        return list(c, player.getUUID(), "You have");
    }

    private static int listOther(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(c, "player");
        if (profiles.size() != 1) {
            c.getSource().sendFailure(Component.literal("Please name exactly one player."));
            return 0;
        }
        NameAndId target = profiles.iterator().next();
        return list(c, target.id(), target.name() + " has");
    }

    private static int list(CommandContext<CommandSourceStack> c, UUID owner, String who) {
        List<Grave> graves = manager().ownedBy(owner);
        if (graves.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal(who + " no graves."), false);
            return 1;
        }
        List<String> lines = new ArrayList<>();
        lines.add(who + " " + graves.size() + " grave" + (graves.size() == 1 ? "" : "s") + ":");
        long now = System.currentTimeMillis();
        for (Grave grave : graves) {
            long minutes = Math.max(0, (now - grave.createdAt()) / 60000);
            String age = minutes < 60 ? minutes + " min ago" : minutes < 2880 ? (minutes / 60) + " h ago" : (minutes / 1440) + " days ago";
            lines.add(" #" + grave.id() + " at " + grave.describePos() + ", " + grave.items().size() + " stacks, " + age);
        }
        c.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)).withStyle(ChatFormatting.GOLD), false);
        return graves.size();
    }

    private static int restore(CommandContext<CommandSourceStack> c) {
        GraveManager manager = manager();
        int id = IntegerArgumentType.getInteger(c, "id");
        Grave grave = manager.byId(id);
        if (grave == null) {
            c.getSource().sendFailure(Component.literal("No grave #" + id + "."));
            return 0;
        }
        ServerPlayer owner = c.getSource().getServer().getPlayerList().getPlayer(grave.owner());
        if (owner == null) {
            c.getSource().sendFailure(Component.literal(grave.ownerName() + " must be online to get their items back."));
            return 0;
        }
        manager.giveBack(grave, owner);
        owner.sendSystemMessage(Component.literal("✓ An admin returned the items from your grave #" + id + ".").withStyle(ChatFormatting.GREEN));
        c.getSource().sendSuccess(() -> Component.literal("Returned grave #" + id + " to " + grave.ownerName() + ".")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static GraveManager manager() {
        GraveManager manager = GraveManager.get();
        if (manager == null) {
            throw new IllegalStateException("SafePlots is not running");
        }
        return manager;
    }
}
