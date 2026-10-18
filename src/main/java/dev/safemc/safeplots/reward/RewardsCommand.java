package dev.safemc.safeplots.reward;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /rewards: today's chests and the prizes. Admins: /rewards give, /rewards reload. /rewards win is what the minigame
 * mods run (console only) when someone wins.
 */
public final class RewardsCommand {
    private RewardsCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rewards")
                .executes(RewardsCommand::show)
                .then(Commands.literal("give").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(c -> give(c, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(c -> give(c, IntegerArgumentType.getInteger(c, "count"))))))
                .then(Commands.literal("reload").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(RewardsCommand::reload))
                .then(Commands.literal("win").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                        .then(Commands.argument("players", EntityArgument.players()).executes(RewardsCommand::win))));
    }

    private static int show(CommandContext<CommandSourceStack> c) {
        Rewards rewards = Rewards.get();
        if (rewards == null) {
            return 0;
        }
        CommandSourceStack source = c.getSource();
        source.sendSystemMessage(Component.literal("Winner Chests").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        source.sendSystemMessage(Component.literal("Win a minigame to get one (max " + rewards.daily() + " a day). Right-click it in "
                + rewards.world() + " to spin the wheel.").withStyle(ChatFormatting.YELLOW));
        if (source.getEntity() instanceof ServerPlayer p) {
            source.sendSystemMessage(Component.literal("Today: " + rewards.today(p.getUUID()) + "/" + rewards.daily()
                    + (rewards.pending(p.getUUID()) > 0 ? " (" + rewards.pending(p.getUUID()) + " waiting for room in your inventory)" : ""))
                    .withStyle(ChatFormatting.GRAY));
        }
        int total = rewards.totalWeight();
        for (Rewards.Prize prize : rewards.prizes()) {
            String chance = String.format(java.util.Locale.ROOT, "%.0f%%", 100.0 * prize.weight() / total);
            source.sendSystemMessage(Component.literal(" " + chance + " ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(prize.describe()).withStyle(ChatFormatting.WHITE)));
        }
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> c, int count) throws CommandSyntaxException {
        Rewards rewards = Rewards.get();
        Collection<ServerPlayer> players = EntityArgument.getPlayers(c, "players");
        if (rewards == null) {
            return 0;
        }
        players.forEach(p -> rewards.give(p, count));
        c.getSource().sendSuccess(() -> Component.literal("✓ Gave " + count + " Winner Chest" + (count == 1 ? "" : "s") + " to "
                + players.size() + " player" + (players.size() == 1 ? "" : "s") + ".").withStyle(ChatFormatting.GREEN), true);
        return players.size();
    }

    private static int win(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Rewards rewards = Rewards.get();
        Collection<ServerPlayer> players = EntityArgument.getPlayers(c, "players");
        if (rewards == null) {
            return 0;
        }
        players.forEach(rewards::win);
        return players.size();
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        Rewards rewards = Rewards.get();
        if (rewards == null) {
            return 0;
        }
        String problem = rewards.reload();
        if (problem != null) {
            c.getSource().sendFailure(Component.literal("config/safeplots-rewards.txt " + problem + ". The old settings stay."));
            return 0;
        }
        String warning = rewards.level() == null ? " Warning: there is no world called " + rewards.world()
                + ", so chests wait until it exists (set world: in the file)." : "";
        c.getSource().sendSuccess(() -> Component.literal("✓ Rewards reloaded: " + rewards.prizes().size() + " prizes, max "
                + rewards.daily() + " chests a day, opened in " + rewards.world() + "." + warning)
                .withStyle(warning.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return 1;
    }
}
