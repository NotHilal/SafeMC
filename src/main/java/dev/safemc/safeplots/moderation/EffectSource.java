package dev.safemc.safeplots.moderation;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * /effectsource [player]: for the next minute, says what gives a player each effect they get (a command block, a
 * datapack, another mod, SafePlots giving back a world's saved effects...). For effects that keep coming back. The full
 * call stack of each one goes to the server log.
 */
public final class EffectSource {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int WATCH_TICKS = 60 * 20;

    private record Watch(UUID viewer, int until, Set<String> seen) {}

    /** Watched player -> who asked. */
    private static final Map<UUID, Watch> WATCHES = new HashMap<>();
    /** The last source said, for the self-test. */
    public static @Nullable String last;

    private EffectSource() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("effectsource").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(c -> start(c, c.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player()).executes(c -> start(c, EntityArgument.getPlayer(c, "player")))));
    }

    private static int start(CommandContext<CommandSourceStack> c, ServerPlayer target) throws CommandSyntaxException {
        watch(c.getSource().getPlayerOrException(), target);
        return 1;
    }

    /** {@code viewer} is told, for the next minute, what gives {@code target} each effect. */
    public static void watch(ServerPlayer viewer, ServerPlayer target) {
        MinecraftServer server = viewer.level().getServer();
        WATCHES.put(target.getUUID(), new Watch(viewer.getUUID(), server.getTickCount() + WATCH_TICKS, new HashSet<>()));
        String name = target == viewer ? "you" : target.getGameProfile().name();
        viewer.sendSystemMessage(Component.literal("Watching the effects " + name + " get" + (target == viewer ? "" : "s")
                + " for 1 minute. Now clear them (/effect clear " + target.getGameProfile().name() + ") and see what gives them back.")
                .withStyle(ChatFormatting.YELLOW));
        if (target.getActiveEffects().isEmpty()) {
            viewer.sendSystemMessage(Component.literal("No effects right now.").withStyle(ChatFormatting.GRAY));
        }
        for (MobEffectInstance effect : target.getActiveEffects()) {
            viewer.sendSystemMessage(Component.literal("Has now: " + describe(effect)).withStyle(ChatFormatting.GRAY));
        }
    }

    /**
     * Fired every time something tries to give an effect, also when it's already there (an effect given again every
     * tick, as command blocks and datapacks do, never shows up as "added").
     */
    public static void onEffect(MobEffectEvent.Applicable event) {
        if (WATCHES.isEmpty() || !(event.getEntity() instanceof ServerPlayer target)) {
            return;
        }
        Watch watch = WATCHES.get(target.getUUID());
        if (watch == null) {
            return;
        }
        MinecraftServer server = target.level().getServer();
        if (server.getTickCount() > watch.until()) {
            WATCHES.remove(target.getUUID());
            ServerPlayer viewer = server.getPlayerList().getPlayer(watch.viewer());
            if (viewer != null) {
                viewer.sendSystemMessage(Component.literal("Stopped watching " + target.getGameProfile().name() + "'s effects.").withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        StackTraceElement[] stack = new Throwable().getStackTrace();
        String source = source(stack);
        last = source;
        MobEffectInstance effect = event.getEffectInstance();
        if (!watch.seen().add(effect.getEffect().getRegisteredName() + "|" + source)) {
            return; // already said
        }
        ServerPlayer viewer = server.getPlayerList().getPlayer(watch.viewer());
        if (viewer != null) {
            viewer.sendSystemMessage(Component.literal(describe(effect)).withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(" given to " + target.getGameProfile().name() + " by: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(source).withStyle(ChatFormatting.WHITE)));
        }
        StringBuilder log = new StringBuilder();
        for (StackTraceElement frame : stack) {
            log.append("\n    at ").append(frame);
        }
        LOGGER.info("[SafePlots] /effectsource: {} given to {} by {}{}", describe(effect), target.getGameProfile().name(), source, log);
    }

    private static String describe(MobEffectInstance effect) {
        String name = effect.getEffect().value().getDisplayName().getString() + " " + (effect.getAmplifier() + 1);
        return name + (effect.isInfiniteDuration() ? " (infinite)" : " (" + effect.getDuration() / 20 + "s)");
    }

    /** What the call stack says gave the effect, in words. */
    static String source(StackTraceElement[] stack) {
        boolean command = false;
        for (StackTraceElement frame : stack) {
            String c = frame.getClassName();
            if (c.contains("BaseCommandBlock") || c.contains("CommandBlockEntity") || c.contains("MinecartCommandBlock")) {
                return "a command block";
            }
            if (c.startsWith("net.minecraft.commands.functions") || c.contains("ServerFunctionManager") || c.contains("FunctionCommand")) {
                return "a datapack function (/datapack list shows the packs)";
            }
            if (c.contains("EffectCommands")) {
                command = true;
            }
        }
        if (command) {
            for (StackTraceElement frame : stack) {
                if (frame.getClassName().contains("ServerGamePacketListenerImpl")) {
                    return "the /effect command, typed by a player";
                }
            }
            return "the /effect command (from the console, RCON or another mod)";
        }
        String found = firstOutside(stack);
        if (found == null) {
            return "the game itself (a beacon, potion, food or mob)";
        }
        if (found.startsWith("dev.safemc.safeplots.inventory.WorldInventories")) {
            return "SafePlots, giving back the effects you had the last time you were in this world (clear them again here and they stay gone)";
        }
        if (found.contains(".SavedState")) {
            return "a SafeMC minigame, giving back the effects you had when you joined it (" + found + ")";
        }
        if (found.startsWith("net.minecraft.")) {
            return "the game itself (" + found + ")";
        }
        return "another mod: " + found;
    }

    /** The first frame that isn't this class, Java, the event bus or the effect code itself. */
    private static @Nullable String firstOutside(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String c = frame.getClassName();
            if (c.equals(EffectSource.class.getName()) || c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.")
                    || c.startsWith("net.neoforged.bus.") || c.startsWith("net.neoforged.neoforge.event.") || c.startsWith("net.neoforged.neoforge.common.")
                    || c.equals("net.minecraft.world.entity.LivingEntity") || c.equals("net.minecraft.world.entity.player.Player")
                    || c.equals("net.minecraft.server.level.ServerPlayer") || c.startsWith("net.minecraft.world.effect.")
                    || c.contains("$$Lambda") || c.contains("EventListener")) {
                continue;
            }
            return c + "." + frame.getMethodName();
        }
        return null;
    }
}
