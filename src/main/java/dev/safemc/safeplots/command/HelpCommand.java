package dev.safemc.safeplots.command;

import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * {@code /ppt help}: every SafePlots command in one list. Players only see what they can use; admins also
 * see the admin sections. Clicking a line puts the command in the chat box.
 */
public final class HelpCommand {
    private record Entry(String usage, String description) {}

    private record Section(String title, boolean adminOnly, List<Entry> entries) {}

    private static final List<Section> SECTIONS = List.of(
            new Section("Your plots", false, List.of(
                    new Entry("/plot trust <player> [plot]", "Let a player build in your plot"),
                    new Entry("/plot untrust <player> [plot]", "Remove that access"),
                    new Entry("/plot showlimits [plot]", "Show your plot's borders (run again to hide)"),
                    new Entry("/plot abandon [plot]", "Give up your plot (buildings stay)"),
                    new Entry("/ppt help", "This list"))),
            new Section("Homes & teleport", false, List.of(
                    new Entry("/sethome", "Save this spot as your home (replaces the old one)"),
                    new Entry("/home", "Teleport to your home"),
                    new Entry("/delhome", "Delete your home"),
                    new Entry("/tpa <player>", "Ask to teleport to a player"),
                    new Entry("/tpahere <player>", "Ask a player to teleport to you"),
                    new Entry("/tpaccept [player]", "Accept a teleport request"),
                    new Entry("/tpdeny [player]", "Deny a teleport request"))),
            new Section("Graves", false, List.of(
                    new Entry("/grave list", "Where your graves are"))),
            new Section("Plot admin", true, List.of(
                    new Entry("/plot wand", "Selection wand: left-click pos1, right-click pos2"),
                    new Entry("/plot pos1", "Set corner 1 (block you look at)"),
                    new Entry("/plot pos2", "Set corner 2"),
                    new Entry("/plot create <name> [full | height <min> [max]]", "Create a plot from the selection"),
                    new Entry("/plot redefine <name> [full | height <min> [max]]", "Move a plot to the selection"),
                    new Entry("/plot setheight <name> <min> [max]", "Change only a plot's height"),
                    new Entry("/plot sign <name>", "Link the next sign you place or click"),
                    new Entry("/plot cancel", "Stop linking a sign"),
                    new Entry("/plot setowner <name> <player>", "Give a plot to a player"),
                    new Entry("/plot removeowner <name>", "Make a plot available again"),
                    new Entry("/plot delete <name>", "Delete a plot (blocks stay)"),
                    new Entry("/plot setlimit <player> <number>", "Set how many plots a player may own"),
                    new Entry("/plot addlimit <player> <amount>", "Raise (or lower) that limit"),
                    new Entry("/plot list [player]", "List plots"),
                    new Entry("/plot info [name]", "Details of a plot"),
                    new Entry("/plot showlimits <name>", "Show any plot's borders"),
                    new Entry("/plot bypass", "Toggle protection bypass for yourself"))),
            new Section("Grave admin", true, List.of(
                    new Entry("/grave list <player>", "Someone else's graves"),
                    new Entry("/grave restore <id>", "Give a grave's items to its (online) owner"))),
            new Section("Moderation", true, List.of(
                    new Entry("/vanish", "Hide from non-admins (until you log out)"),
                    new Entry("/freeze <player>", "Stop a player moving, building and using commands"),
                    new Entry("/unfreeze <player>", "Unfreeze"),
                    new Entry("/mute <player> [duration|perm] [reason]", "Block chat and /msg"),
                    new Entry("/unmute <player>", "Lift a mute"),
                    new Entry("/tempban <player> <duration> [reason]", "Ban that ends by itself (/pardon to lift)"))));

    private HelpCommand() {}

    static int run(CommandContext<CommandSourceStack> c) {
        boolean admin = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(c.getSource());
        MutableComponent out = Component.literal("SafePlots commands").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        for (Section section : SECTIONS) {
            if (section.adminOnly() && !admin) {
                continue;
            }
            out.append(Component.literal("\n" + section.title()).withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE));
            for (Entry entry : section.entries()) {
                String base = entry.usage().split(" [<\\[]", 2)[0]; // "/plot trust <player>" -> "/plot trust"
                out.append(Component.literal("\n " + entry.usage()).withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withClickEvent(new ClickEvent.SuggestCommand(base + (base.equals(entry.usage()) ? "" : " ")))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to type it")))))
                        .append(Component.literal(" - " + entry.description()).withStyle(ChatFormatting.GRAY));
            }
        }
        if (!admin) {
            out.append(Component.literal("\nClaim a plot by right-clicking its AVAILABLE sign.").withStyle(ChatFormatting.GREEN));
        }
        c.getSource().sendSuccess(() -> out, false);
        return 1;
    }
}
