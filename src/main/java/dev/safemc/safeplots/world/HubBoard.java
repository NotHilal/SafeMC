package dev.safemc.safeplots.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * A sidebar for players in the worlds picked with {@code /mv topwinners <world> on} (the games hub) with the player with the most wins in each minigame, read from the win files
 * the minigame mods keep ({@code <world>/safebedwars-wins.json} ...). Games whose mod isn't installed aren't listed;
 * with none of them there is no sidebar. Sent as packets only, like the minigames' own sidebars, so the server's
 * scoreboard isn't touched.
 */
public final class HubBoard {
    private static final String OBJECTIVE = "safeplots_hub";
    private static final Component TITLE = Component.literal("Top Winners").withStyle(ChatFormatting.GOLD);

    private record Game(String title, ChatFormatting color, String file) {}

    private static final List<Game> GAMES = List.of(
            new Game("BedWars", ChatFormatting.RED, "safebedwars-wins.json"),
            new Game("TNT Run", ChatFormatting.GOLD, "safetntrun-wins.json"),
            new Game("Guess the Build", ChatFormatting.AQUA, "safeguessbuild-wins.json"));

    private record Top(String name, int wins) {}

    /** Per game file: when it was last read and its #1 (null: nobody won yet). Missing: the file doesn't exist. */
    private static final Map<String, Long> READ_AT = new HashMap<>();
    private static final Map<String, @Nullable Top> TOP = new HashMap<>();

    /** What each player in those worlds was last sent, to only send changes and to remove the board when they leave. */
    private static final Map<UUID, List<Component>> SHOWN = new HashMap<>();

    private HubBoard() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 40 == 0) {
            update(event.getServer());
        }
    }

    /** Re-reads the win files that changed and sends the board to who's in those worlds (and removes it from who left). */
    public static void update(MinecraftServer server) {
        refresh(server);
        List<Component> lines = lines();
        Worlds worlds = Worlds.get();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (lines.isEmpty() || worlds == null || !worlds.topWinners(player.level())) {
                hide(player);
            } else {
                show(player, lines);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SHOWN.remove(event.getEntity().getUUID()); // the client forgets it too
    }

    /** Reads the win files that changed since last time. */
    private static void refresh(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        for (Game game : GAMES) {
            Path file = root.resolve(game.file());
            try {
                if (!Files.exists(file)) {
                    READ_AT.remove(game.file());
                    TOP.remove(game.file());
                    continue;
                }
                long modified = Files.getLastModifiedTime(file).toMillis();
                if (READ_AT.containsKey(game.file()) && READ_AT.get(game.file()) == modified) {
                    continue;
                }
                READ_AT.put(game.file(), modified);
                TOP.put(game.file(), top(file));
            } catch (Exception e) {
                // half-written or broken: keep what was shown and try again next time
                READ_AT.remove(game.file());
            }
        }
    }

    private static @Nullable Top top(Path file) throws Exception {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        Top best = null;
        if (root.has("players")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                int wins = o.get("wins").getAsInt();
                String name = o.get("name").getAsString();
                // same order as the games' own leaderboards: most wins, then by name
                if (wins > 0 && (best == null || wins > best.wins() || wins == best.wins() && name.compareToIgnoreCase(best.name()) < 0)) {
                    best = new Top(name, wins);
                }
            }
        }
        return best;
    }

    public static List<Component> lines() {
        List<Component> lines = new ArrayList<>();
        for (Game game : GAMES) {
            if (!TOP.containsKey(game.file())) {
                continue; // that minigame isn't installed
            }
            // one short line per game: "BedWars: Steve (42)"
            Top top = TOP.get(game.file());
            lines.add(Component.literal(game.title() + ": ").withStyle(game.color())
                    .append(top == null
                            ? Component.literal("-").withStyle(ChatFormatting.GRAY)
                            : Component.literal(top.name()).withStyle(ChatFormatting.WHITE)
                                    .append(Component.literal(" (" + top.wins() + ")").withStyle(ChatFormatting.GRAY))));
        }
        return lines;
    }

    private static Objective objective(ServerPlayer player) {
        return new Objective(player.level().getServer().getScoreboard(), OBJECTIVE, ObjectiveCriteria.DUMMY, TITLE,
                ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
    }

    private static void show(ServerPlayer player, List<Component> lines) {
        List<Component> shown = SHOWN.get(player.getUUID());
        if (lines.equals(shown)) {
            return;
        }
        if (shown == null) {
            Objective objective = objective(player);
            player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
            player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
            shown = List.of();
        }
        // Higher scores are shown first; the hidden number keeps the lines in order.
        for (int i = 0; i < lines.size(); i++) {
            player.connection.send(new ClientboundSetScorePacket("line" + i, OBJECTIVE, lines.size() - i,
                    Optional.of(lines.get(i)), Optional.of(BlankFormat.INSTANCE)));
        }
        for (int i = lines.size(); i < shown.size(); i++) {
            player.connection.send(new ClientboundResetScorePacket("line" + i, OBJECTIVE));
        }
        SHOWN.put(player.getUUID(), List.copyOf(lines));
    }

    private static void hide(ServerPlayer player) {
        if (SHOWN.remove(player.getUUID()) != null) {
            player.connection.send(new ClientboundSetObjectivePacket(objective(player), ClientboundSetObjectivePacket.METHOD_REMOVE));
        }
    }

    /** For the self-test: whether this player has the sidebar right now. */
    public static boolean showing(ServerPlayer player) {
        return SHOWN.containsKey(player.getUUID());
    }
}
