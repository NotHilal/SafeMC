package dev.safemc.safeplots.world;

import dev.safemc.safeplots.holo.Holograms;
import dev.safemc.safeplots.plot.PlotManager;
import dev.safemc.safeplots.teleport.Teleports;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Signs that take players to a world's spawn: {@code /mv sign <world> [text]}, then right-click a sign. They show the
 * world's name (or the text) and how many are in it. Clicking one teleports right away, without the usual wait. Only
 * admins can break one; that unlinks it.
 */
public final class WorldSigns {
    /** Admin -> what the next right-clicked sign links to. */
    private static final Map<UUID, Worlds.WorldSign> PENDING = new HashMap<>();

    private WorldSigns() {}

    /** The next sign {@code admin} right-clicks goes to {@code world}, with {@code label} as its title (empty: the world's name). */
    public static void startLink(ServerPlayer admin, String world, String label) {
        PENDING.put(admin.getUUID(), new Worlds.WorldSign("", BlockPos.ZERO, world, label));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Worlds worlds = Worlds.get();
        Level level = event.getLevel();
        if (worlds == null || level.isClientSide() || !(event.getEntity() instanceof ServerPlayer player)
                || !(level.getBlockEntity(event.getPos()) instanceof SignBlockEntity)) {
            return;
        }
        Worlds.WorldSign pending = PENDING.remove(player.getUUID());
        if (pending != null) {
            event.setCanceled(true);
            Worlds.WorldSign sign = new Worlds.WorldSign(PlotManager.dimensionId(level), event.getPos().immutable(), pending.world(), pending.label());
            worlds.addWorldSign(sign);
            refresh(worlds, level.getServer(), sign);
            player.sendSystemMessage(Component.literal("✓ This sign now takes players to " + sign.world() + ".").withStyle(ChatFormatting.GREEN));
            return;
        }
        Worlds.WorldSign sign = worlds.worldSignAt(level, event.getPos());
        if (sign == null) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        refresh(worlds, level.getServer(), sign);
        if (worlds.levelByName(sign.world()) == null) {
            player.sendSystemMessage(Component.literal(sign.world() + " isn't loaded right now. Ask an admin.").withStyle(ChatFormatting.RED));
            return;
        }
        Teleports.now(player, sign.world(), () -> worlds.spawnDestination(sign.world()));
    }

    /** Only admins may break one, which unlinks it. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BreakBlockEvent event) {
        Worlds worlds = Worlds.get();
        if (worlds == null || !(event.getLevel() instanceof Level level) || level.isClientSide()) {
            return;
        }
        Worlds.WorldSign sign = worlds.worldSignAt(level, event.getPos());
        if (sign == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!PlotManager.isAdmin(player)) {
            event.setCanceled(true);
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("Only admins can remove this sign.").withStyle(ChatFormatting.RED), true);
            }
            return;
        }
        worlds.removeWorldSign(sign);
        if (player instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal("Sign to " + sign.world() + " removed.").withStyle(ChatFormatting.YELLOW));
        }
    }

    /** The player counts, every second. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Worlds worlds = Worlds.get();
        if (worlds != null && event.getServer().getTickCount() % 20 == 0) {
            for (Worlds.WorldSign sign : worlds.worldSigns()) {
                refresh(worlds, event.getServer(), sign);
            }
        }
    }

    static void refresh(Worlds worlds, MinecraftServer server, Worlds.WorldSign sign) {
        SignBlockEntity entity = loaded(server, sign);
        if (entity == null) {
            return;
        }
        ServerLevel target = worlds.levelByName(sign.world());
        int players = target == null ? 0 : target.players().size();
        Component title = sign.label().isEmpty()
                ? Component.literal(displayName(sign.world())).withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD)
                : Holograms.colored(sign.label());
        List<Component> lines = List.of(title, Component.literal("Click to go").withStyle(ChatFormatting.BLACK),
                Component.literal(players + (players == 1 ? " player" : " players")).withStyle(ChatFormatting.DARK_GRAY), Component.empty());
        SignText text = new SignText(lines, lines, DyeColor.BLACK, false);
        if (text.equals(entity.getText(SignTextSlot.FRONT))) {
            return; // only changed text is sent to players
        }
        entity.setText(text, SignTextSlot.FRONT);
        entity.setText(text, SignTextSlot.BACK);
        entity.setWaxed(true);
        entity.setAllowedPlayerEditor(null);
    }

    private static @Nullable SignBlockEntity loaded(MinecraftServer server, Worlds.WorldSign sign) {
        for (ServerLevel level : server.getAllLevels()) {
            if (PlotManager.dimensionId(level).equals(sign.dimension())) {
                return level.isLoaded(sign.pos()) && level.getBlockEntity(sign.pos()) instanceof SignBlockEntity entity ? entity : null;
            }
        }
        return null;
    }

    /** "prison" -> "Prison", "kit_pvp" -> "Kit Pvp". */
    static String displayName(String world) {
        StringBuilder out = new StringBuilder();
        for (String word : world.split("[_-]")) {
            if (!word.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
            }
        }
        return out.toString();
    }
}
