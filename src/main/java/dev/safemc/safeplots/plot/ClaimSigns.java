package dev.safemc.safeplots.plot;

import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.jspecify.annotations.Nullable;

/**
 * Claim signs are only a way to claim and a display. Protection never depends on them:
 * if a sign is destroyed the plot keeps its owner, and a new sign at the same spot shows the right text again.
 */
public final class ClaimSigns {
    private ClaimSigns() {}

    /** Admin right-clicked a sign while a {@code /plot sign} link was pending. */
    public static void link(PlotManager manager, ServerPlayer admin, Level level, BlockPos pos, String plotName) {
        Plot plot = manager.plot(plotName);
        if (plot == null) {
            admin.sendSystemMessage(Component.literal("Plot " + plotName + " no longer exists.").withStyle(ChatFormatting.RED));
            return;
        }
        if (!plot.dimension().equals(PlotManager.dimensionId(level))) {
            admin.sendSystemMessage(Component.literal("The sign must be in the same dimension as " + plotName + ".").withStyle(ChatFormatting.RED));
            return;
        }
        manager.linkSign(plot, pos);
        admin.sendSystemMessage(Component.literal("✓ Sign linked to " + plotName + ".").withStyle(ChatFormatting.GREEN));
    }

    /** A player right-clicked a linked claim sign. */
    public static void click(PlotManager manager, ServerPlayer player, Plot plot) {
        // Always redraw, so a re-placed or stale sign fixes itself.
        refresh(manager, plot);

        UUID id = player.getUUID();
        UUID owner = plot.owner();
        if (owner != null) {
            if (owner.equals(id)) {
                player.sendSystemMessage(Component.literal("You already own " + plot.name() + ".").withStyle(ChatFormatting.YELLOW));
            } else {
                player.sendSystemMessage(Component.literal("This plot is already claimed by " + manager.nameOf(owner) + ".").withStyle(ChatFormatting.RED));
            }
            return;
        }

        int owned = manager.plotsOwnedBy(id).size();
        int max = manager.maxClaims(id);
        if (owned >= max) {
            player.sendSystemMessage(Component.literal("You already own " + owned + "/" + max
                    + " plots. Ask an admin for another claim slot.").withStyle(ChatFormatting.RED));
            return;
        }

        manager.rememberName(player.nameAndId());
        manager.setOwner(plot, id);
        player.sendSystemMessage(Component.literal("✓ Plot claimed: " + plot.name()).withStyle(ChatFormatting.GREEN));
    }

    /** Redraws the plot's sign if its chunk is loaded. Unloaded signs are redrawn on their next click. */
    public static void refresh(PlotManager manager, Plot plot) {
        SignBlockEntity sign = loadedSign(manager, plot.dimension(), plot.sign());
        if (sign == null) {
            return;
        }
        // Hanging signs are narrower (60px vs 90px); bold "AVAILABLE" wouldn't fit on them.
        boolean bold = !(sign instanceof HangingSignBlockEntity);
        SignText text = plot.owner() == null
                ? text(Component.literal("AVAILABLE").withStyle(s -> s.withColor(ChatFormatting.DARK_GREEN).withBold(bold)),
                        Component.literal("Right Click"),
                        Component.literal("to Claim"),
                        Component.literal(plot.name()).withStyle(ChatFormatting.DARK_GRAY))
                : text(Component.literal("CLAIMED").withStyle(s -> s.withColor(ChatFormatting.DARK_RED).withBold(bold)),
                        Component.literal(manager.nameOf(plot.owner())),
                        Component.empty(),
                        Component.literal(plot.name()).withStyle(ChatFormatting.DARK_GRAY));
        sign.setText(text, SignTextSlot.FRONT);
        sign.setText(text, SignTextSlot.BACK);
        sign.setWaxed(true);
        sign.setAllowedPlayerEditor(null); // a freshly placed sign may still have its edit screen open
    }

    /** Blanks a sign that is no longer linked to a plot. */
    static void clear(PlotManager manager, String dimension, @Nullable BlockPos pos) {
        SignBlockEntity sign = loadedSign(manager, dimension, pos);
        if (sign == null) {
            return;
        }
        sign.setText(SignText.EMPTY, SignTextSlot.FRONT);
        sign.setText(SignText.EMPTY, SignTextSlot.BACK);
        sign.setWaxed(false);
    }

    private static @Nullable SignBlockEntity loadedSign(PlotManager manager, String dimension, @Nullable BlockPos pos) {
        if (pos == null) {
            return null;
        }
        ServerLevel level = manager.level(dimension);
        if (level == null || !level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof SignBlockEntity sign ? sign : null;
    }

    private static SignText text(Component l1, Component l2, Component l3, Component l4) {
        List<Component> lines = List.of(l1, l2, l3, l4);
        return new SignText(lines, lines, DyeColor.BLACK, false);
    }
}
