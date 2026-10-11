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
                    new Entry("/plot claimhere", "Make your own 50x50 plot around where you stand"),
                    new Entry("/plot confirm", "Confirm the plot /plot claimhere showed you"),
                    new Entry("/plot trust <player> [plot]", "Let a player build in your plot"),
                    new Entry("/plot untrust <player> [plot]", "Remove that access"),
                    new Entry("/plot showlimits [plot]", "Show your plot's borders (run again to hide)"),
                    new Entry("/plot abandon [plot]", "Give up your plot (buildings stay)"),
                    new Entry("/plot movesign [plot]", "Move your plot's sign to another sign inside it"),
                    new Entry("/plot cancel", "Stop moving or linking a sign"),
                    new Entry("/ppt help", "This list"))),
            new Section("Homes & teleport", false, List.of(
                    new Entry("/sethome [name]", "Save this spot as a home (up to 5; same name moves it)"),
                    new Entry("/home [name]", "Teleport to a home"),
                    new Entry("/homes", "List your homes (click one to go there)"),
                    new Entry("/delhome [name]", "Delete a home"),
                    new Entry("/tpa <player>", "Ask to teleport to a player"),
                    new Entry("/tpahere <player>", "Ask a player to teleport to you"),
                    new Entry("/tpaccept [player]", "Accept a teleport request"),
                    new Entry("/tpdeny [player]", "Deny a teleport request"),
                    new Entry("/mvtp <world>", "Teleport to another world (world, nether, end, or one an admin made)"))),
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
                    new Entry("/nomobspawn create <name>", "No hostile mob spawns in the selected area"),
                    new Entry("/nomobspawn delete <name>", "Remove a no-mob-spawn zone"),
                    new Entry("/nomobspawn list", "List no-mob-spawn zones (/nomobspawn here: the one you're in)"),
                    new Entry("/plot setowner <name> <player>", "Give a plot to a player"),
                    new Entry("/plot removeowner <name>", "Make a plot available again"),
                    new Entry("/plot delete <name>", "Delete a plot (blocks stay)"),
                    new Entry("/plot setlimit <player> <number>", "Set how many plots a player may own"),
                    new Entry("/plot addlimit <player> <amount>", "Raise (or lower) that limit"),
                    new Entry("/plot list [player]", "List plots"),
                    new Entry("/plot info [name]", "Details of a plot"),
                    new Entry("/plot showlimits <name>", "Show any plot's borders"),
                    new Entry("/plot bypass", "Toggle protection bypass for yourself"))),
            new Section("Worlds", true, List.of(
                    new Entry("/mv create <name> <type> [seed]", "New world: normal, amplified, large_biomes, flat, void, nether, end"),
                    new Entry("/mv import <name> <folder> [type] [seed]", "Turn a world folder in imports/ into a world"),
                    new Entry("/mv list", "List worlds"),
                    new Entry("/mv setspawn", "Set the spawn of the world you're in"),
                    new Entry("/mv group [world] [group]", "Which worlds share an inventory (main = world, nether, end)"),
                    new Entry("/mv portal create <name> <world>", "Selected area becomes a portal to a world"),
                    new Entry("/mv portal setdest <name>", "The portal lands players where you stand"),
                    new Entry("/mv portal delete <name>", "Remove a portal (blocks stay)"),
                    new Entry("/mv portal list", "List portals"),
                    new Entry("/mv delete <name>", "Delete a world and everything in it"),
                    new Entry("/mvtp <world> <player>", "Send a player to a world (no warm-up)"))),
            new Section("Schematics", true, List.of(
                    new Entry("/schem list", "Schematics in the server's schematics/ folder"),
                    new Entry("/schem load <name>", "Load a .schem file"),
                    new Entry("/schem rotate <degrees>", "Turn it 90, 180 or 270 degrees clockwise"),
                    new Entry("/schem paste [noair]", "Paste where you stand (noair: keep blocks where it has air)"),
                    new Entry("/schem info", "What's loaded and where it would go"),
                    new Entry("/schem undo", "Undo your last paste (up to 5 are kept)"))),
            new Section("Grave admin", true, List.of(
                    new Entry("/grave list <player>", "Someone else's graves"),
                    new Entry("/grave restore <id>", "Give a grave's items to its (online) owner"))),
            new Section("Moderation", true, List.of(
                    new Entry("/vanish", "Hide from non-admins (until you log out)"),
                    new Entry("/freeze <player>", "Stop a player moving, building and using commands"),
                    new Entry("/unfreeze <player>", "Unfreeze"),
                    new Entry("/mute <player> [duration|perm] [reason]", "Block chat and /msg"),
                    new Entry("/unmute <player>", "Lift a mute"),
                    new Entry("/tempban <player> <duration> [reason]", "Ban that ends by itself (/pardon to lift)"),
                    new Entry("/setjail", "Set the jail spawn where you stand"),
                    new Entry("/jail <player> <duration> [reason]", "Send a player to jail for a while"),
                    new Entry("/unjail <player>", "Release a player from jail early"))));

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
