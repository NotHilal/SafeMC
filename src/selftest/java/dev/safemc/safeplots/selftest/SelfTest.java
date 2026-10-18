package dev.safemc.safeplots.selftest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import dev.safemc.safeplots.command.PlotWand;
import dev.safemc.safeplots.grave.Grave;
import dev.safemc.safeplots.grave.GraveManager;
import dev.safemc.safeplots.moderation.Durations;
import dev.safemc.safeplots.teleport.HomeManager;
import dev.safemc.safeplots.teleport.Teleports;
import dev.safemc.safeplots.moderation.ModerationManager;
import dev.safemc.safeplots.plot.Plot;
import dev.safemc.safeplots.plot.PlotBorders;
import dev.safemc.safeplots.plot.PlotManager;
import dev.safemc.safeplots.world.Worlds;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

/** Dev-only end-to-end checks. /selftest setup, wait ~15s, /selftest check, restart, /selftest persist. */
@Mod("safeplots_selftest")
public final class SelfTest {
    private static final int Y = -60; // first air layer in a default superflat world
    private static final List<String> results = new ArrayList<>();
    /** Set when a chat message got past mutes (WorldChat, at LOWEST, then always cancels it to send its own line). */
    private static boolean chatPassed;

    public SelfTest() {
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOW, (ServerChatEvent e) -> chatPassed = true);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(Commands.literal("selftest")
                .then(Commands.literal("setup").executes(c -> report(c, SelfTest::setup)))
                .then(Commands.literal("check").executes(c -> report(c, SelfTest::check)))
                .then(Commands.literal("persist").executes(c -> report(c, SelfTest::persist)))
                .then(Commands.literal("worlds").executes(c -> report(c, SelfTest::worlds)))
                .then(Commands.literal("schem").executes(c -> report(c, SelfTest::schem)))
                .then(Commands.literal("schembig").executes(c -> report(c, SelfTest::schemBig)))
                .then(Commands.literal("chat").executes(c -> report(c, SelfTest::chat)))
                .then(Commands.literal("crystals").executes(c -> report(c, SelfTest::crystals)))
                .then(Commands.literal("holo").executes(c -> report(c, SelfTest::holo)))
                .then(Commands.literal("guide").executes(c -> report(c, SelfTest::guide)))
                .then(Commands.literal("parkour").executes(c -> report(c, SelfTest::parkour)))
                .then(Commands.literal("elevator").executes(c -> report(c, SelfTest::elevator)))
                .then(Commands.literal("hunger").executes(c -> report(c, SelfTest::hunger)))
                .then(Commands.literal("hubboard").executes(c -> report(c, SelfTest::hubBoard)))
                .then(Commands.literal("endportal").executes(c -> report(c, SelfTest::endPortal)))
                .then(Commands.literal("damage").executes(c -> report(c, SelfTest::damage)))
                .then(Commands.literal("afk").executes(c -> report(c, SelfTest::afk)))
                .then(Commands.literal("invsee").executes(c -> report(c, SelfTest::invsee)))
                .then(Commands.literal("rewards").executes(c -> report(c, SelfTest::rewards)))));
    }

    private interface Body {
        void run(MinecraftServer server, ServerLevel level, PlotManager pm) throws Exception;
    }

    private static int report(CommandContext<CommandSourceStack> c, Body body) {
        results.clear();
        MinecraftServer server = c.getSource().getServer();
        try {
            body.run(server, server.overworld(), PlotManager.get());
        } catch (Throwable t) {
            results.add("FAIL exception: " + t);
            t.printStackTrace();
        }
        long failed = results.stream().filter(r -> r.startsWith("FAIL")).count();
        results.add((failed == 0 ? "ALL PASSED" : failed + " FAILED") + " (" + results.size() + " checks)");
        c.getSource().sendSuccess(() -> Component.literal(String.join("\n", results)), false);
        return 1;
    }

    private static void expect(boolean ok, String what) {
        results.add((ok ? "ok   " : "FAIL ") + what);
    }

    private static BlockPos p(int x, int z) {
        return new BlockPos(x, Y, z);
    }

    // ------------------------------------------------------------------ immediate checks + tick setups

    private static void setup(MinecraftServer server, ServerLevel level, PlotManager pm) throws Exception {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamerule fire_spread_radius_around_player -1");
        for (int cx = (980 >> 4); cx <= (1050 >> 4); cx++) {
            for (int cz = (980 >> 4); cz <= (1030 >> 4); cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        for (Plot plot : new ArrayList<>(pm.plots())) {
            if (plot.name().startsWith("t_")) {
                pm.delete(plot);
            }
        }
        for (int x = 980; x <= 1050; x++) {
            for (int z = 980; z <= 1030; z++) {
                for (int y = Y; y <= Y + 6; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }

        String dim = PlotManager.dimensionId(level);
        Plot home = new Plot("t_home", dim, new BlockPos(1000, level.getMinY(), 1000), new BlockPos(1009, level.getMaxY(), 1009));
        Plot second = new Plot("t_second", dim, new BlockPos(1020, level.getMinY(), 1000), new BlockPos(1025, level.getMaxY(), 1005));
        Plot exact = new Plot("t_exact", dim, new BlockPos(1030, Y + 2, 1000), new BlockPos(1035, Y + 8, 1005));
        expect(pm.create(home) == null && pm.create(second) == null && pm.create(exact) == null, "create 3 plots");
        expect("t_home".equals(pm.create(new Plot("t_overlap", dim, new BlockPos(1005, 0, 1005), new BlockPos(1015, 0, 1015)))),
                "overlapping plot rejected (overlaps t_home)");

        ServerPlayer owner = fakePlayer(server, "Owner");
        ServerPlayer griefer = fakePlayer(server, "Griefer");
        ServerPlayer friend = fakePlayer(server, "Friend");
        ServerPlayer admin = fakePlayer(server, "Admin");
        server.getPlayerList().op(admin.nameAndId());
        pm.setMaxClaims(owner.getUUID(), 1);
        for (ServerPlayer sp : List.of(owner, griefer, friend, admin)) {
            sp.teleportTo(1004.5, Y, 1015.5);
        }

        // ---- claim via sign
        // ---- wand
        run(admin, "plot wand");
        expect(PlotWand.isWand(admin.getMainHandItem()) || admin.getInventory().contains(s -> PlotWand.isWand(s)), "/plot wand gives the wand");
        ItemStack wand = PlotWand.create();
        BlockPos wandA = new BlockPos(1040, Y - 1, 1020), wandB = new BlockPos(1045, Y - 1, 1025);
        admin.setItemInHand(InteractionHand.MAIN_HAND, wand);
        admin.gameMode.handleBlockBreakAction(wandA, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP, level.getMaxY(), 0);
        click(admin, wandB, wand);
        expect(wandA.equals(pm.selection(admin.getUUID()).pos1()) && wandB.equals(pm.selection(admin.getUUID()).pos2()),
                "wand: left-click sets pos1, right-click sets pos2");
        expect(!level.getBlockState(wandA).isAir() && level.getBlockState(wandB.above()).isAir(), "wand doesn't break blocks or place candles");

        // ---- heights: create with a floor, change height, redefine corners
        run(admin, "plot create t_height height -58");
        Plot tall = pm.plot("t_height");
        expect(tall != null && tall.min().getY() == -58 && tall.max().getY() == level.getMaxY(), "/plot create ... height <minY> goes up to build limit");
        BlockPos under = new BlockPos(1042, Y - 3, 1022);
        griefer.gameMode.destroyBlock(under);
        expect(level.getBlockState(under).isAir(), "anyone can mine under a plot with a raised floor");
        run(admin, "plot setheight t_height -62 100");
        expect(tall.min().getY() == -62 && tall.max().getY() == 100 && tall.min().getX() == 1040, "/plot setheight changes only Y");
        run(admin, "plot setheight t_height 50 10");
        expect(tall.min().getY() == -62, "invalid height range is rejected");
        pm.setOwner(tall, owner.getUUID());
        pm.setCorner(admin.getUUID(), 1, dim, new BlockPos(1008, 0, 1008));
        pm.setCorner(admin.getUUID(), 2, dim, new BlockPos(1012, 0, 1012));
        run(admin, "plot redefine t_height");
        expect(tall.min().getX() == 1040, "redefine into another plot is rejected (overlap)");
        pm.setCorner(admin.getUUID(), 1, dim, new BlockPos(1041, -60, 1021));
        pm.setCorner(admin.getUUID(), 2, dim, new BlockPos(1048, -50, 1028));
        run(admin, "plot redefine t_height");
        expect(tall.min().equals(new BlockPos(1041, -60, 1021)) && tall.max().equals(new BlockPos(1048, -50, 1028))
                && owner.getUUID().equals(tall.owner()), "redefine moves corners and keeps the owner");
        run(admin, "plot redefine t_height full");
        expect(tall.min().getY() == level.getMinY() && tall.max().getY() == level.getMaxY(), "redefine ... full covers the whole height");
        pm.delete(tall);
        run(admin, "plot create t_default");
        Plot byDefault = pm.plot("t_default");
        expect(byDefault != null && byDefault.min().equals(new BlockPos(1041, -60, 1021)) && byDefault.max().equals(new BlockPos(1048, -50, 1028)),
                "/plot create uses the exact selected corners by default");
        pm.delete(byDefault);

        // ---- sign linking: right-click an existing sign, or place a new one (even inside a protected plot)
        BlockPos sign1 = p(999, 998), sign2 = p(1022, 1002);
        level.setBlockAndUpdate(sign1, Blocks.OAK_SIGN.defaultBlockState());
        run(admin, "plot sign t_home");
        run(admin, "plot cancel");
        click(admin, sign1, ItemStack.EMPTY);
        expect(home.sign() == null, "/plot cancel stops linking");
        run(admin, "plot sign t_home");
        click(admin, sign1, ItemStack.EMPTY);
        run(admin, "plot sign t_second");
        click(admin, sign2.below(), new ItemStack(Items.OAK_SIGN), Direction.UP);
        expect(sign2.equals(second.sign()) && signLine(level, sign2).contains("AVAILABLE"), "placing a sign inside an unclaimed plot links it");
        expect(sign1.equals(home.sign()), "admin linked sign by right-click");
        BlockPos support = new BlockPos(1036, Y + 2, 1001);
        level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        run(admin, "plot sign t_exact");
        click(admin, support, new ItemStack(Items.OAK_HANGING_SIGN), Direction.DOWN);
        expect(support.below().equals(exact.sign()) && signLine(level, support.below()).contains("AVAILABLE"), "hanging sign placed and linked");
        click(friend, support.below(), ItemStack.EMPTY);
        expect(friend.getUUID().equals(exact.owner()) && signLine(level, support.below()).contains("'s property"), "claim via hanging sign");
        expect(signLine(level, sign1).contains("AVAILABLE"), "unclaimed sign shows AVAILABLE");
        expect(!home.isClaimed(), "linking click did not claim");

        click(owner, sign1, ItemStack.EMPTY);
        expect(owner.getUUID().equals(home.owner()), "owner claimed t_home by clicking sign");
        expect(signLine(level, sign1).contains("'s property"), "sign updated to <owner>'s property");
        click(griefer, sign1, ItemStack.EMPTY);
        expect(owner.getUUID().equals(home.owner()), "second player can't take a claimed plot");
        click(owner, sign2, ItemStack.EMPTY);
        expect(!second.isClaimed(), "claim limit 1 blocks a second claim");
        console(server, "plot addlimit Owner 1");
        click(owner, sign2, ItemStack.EMPTY);
        expect(owner.getUUID().equals(second.owner()), "after /plot addlimit the second claim works");

        // ---- breaking
        BlockPos stone = p(1001, 1001);
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        griefer.gameMode.destroyBlock(stone);
        expect(level.getBlockState(stone).is(Blocks.STONE), "outsider can't break");
        friend.gameMode.destroyBlock(stone);
        expect(level.getBlockState(stone).is(Blocks.STONE), "untrusted friend can't break");
        run(owner, "plot trust Friend t_home");
        expect(home.trusted().contains(friend.getUUID()), "/plot trust works");
        friend.gameMode.destroyBlock(stone);
        expect(level.getBlockState(stone).isAir(), "trusted friend can break");
        run(owner, "plot untrust Friend t_home");
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        friend.gameMode.destroyBlock(stone);
        expect(level.getBlockState(stone).is(Blocks.STONE), "untrusted again -> can't break");
        owner.gameMode.destroyBlock(stone);
        expect(level.getBlockState(stone).isAir(), "owner can break");

        // ---- placing and pouring
        BlockPos ground = new BlockPos(1002, Y - 1, 1002);
        click(griefer, ground, new ItemStack(Items.STONE), Direction.UP);
        expect(level.getBlockState(ground.above()).isAir(), "outsider can't place blocks");
        click(griefer, ground, new ItemStack(Items.FLINT_AND_STEEL), Direction.UP);
        expect(level.getBlockState(ground.above()).isAir(), "outsider can't use flint and steel");
        griefer.teleportTo(1002.5, Y, 1004.5);
        griefer.setXRot(60F);
        griefer.setYRot(180F); // look north toward z=1002
        griefer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LAVA_BUCKET));
        griefer.gameMode.useItem(griefer, level, griefer.getMainHandItem(), InteractionHand.MAIN_HAND);
        expect(countFluid(level, 1000, 1009, 1000, 1009) == 0, "outsider can't pour lava bucket");
        griefer.teleportTo(1004.5, Y, 1015.5);
        click(owner, ground, new ItemStack(Items.STONE), Direction.UP);
        expect(level.getBlockState(ground.above()).is(Blocks.STONE), "owner can place blocks");

        // ---- containers
        BlockPos chest = p(1003, 1003);
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        click(griefer, chest, ItemStack.EMPTY);
        expect(griefer.containerMenu == griefer.inventoryMenu, "outsider can't open chest");
        click(owner, chest, ItemStack.EMPTY);
        expect(owner.containerMenu != owner.inventoryMenu, "owner can open chest");
        owner.closeContainer();

        // ---- entities
        ArmorStand stand = new ArmorStand(level, 1004.5, Y, 1006.5);
        level.addFreshEntity(stand);
        griefer.attack(stand);
        expect(stand.isAlive(), "outsider can't break armor stand");
        Pig pig = EntityTypes.PIG.spawn(level, p(1006, 1006), EntitySpawnReason.COMMAND);
        float health = pig.getHealth();
        griefer.attack(pig);
        expect(pig.getHealth() == health, "outsider can't hurt animals");

        // ---- pets (outside any plot)
        Wolf wolf = EntityTypes.WOLF.spawn(level, p(990, 1020), EntitySpawnReason.COMMAND);
        wolf.tame(owner);
        float wolfHealth = wolf.getHealth();
        griefer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        griefer.attack(wolf);
        expect(wolf.getHealth() == wolfHealth, "outsider can't hurt someone's wolf outside plots");
        griefer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LEAD));
        griefer.interactOn(wolf, InteractionHand.MAIN_HAND, wolf.position());
        expect(!wolf.isLeashed(), "outsider can't leash someone's wolf");
        Horse horse = EntityTypes.HORSE.spawn(level, p(992, 1020), EntitySpawnReason.COMMAND);
        horse.tameWithName(owner);
        horse.setItemSlot(net.minecraft.world.entity.EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
        griefer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        griefer.interactOn(horse, InteractionHand.MAIN_HAND, horse.position());
        expect(griefer.getVehicle() == null, "outsider can't ride someone's horse");
        admin.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        admin.attack(wolf);
        expect(wolf.getHealth() == wolfHealth, "admin without bypass can't hurt pets");
        owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        owner.attack(wolf);
        expect(wolf.getHealth() < wolfHealth, "owner can still hurt their own pet");
        Wolf friendsWolf = EntityTypes.WOLF.spawn(level, p(1005, 1007), EntitySpawnReason.COMMAND); // inside t_home
        friendsWolf.tame(friend);
        friend.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LEAD));
        friend.interactOn(friendsWolf, InteractionHand.MAIN_HAND, friendsWolf.position());
        expect(friendsWolf.isLeashed(), "pet owner can leash their pet inside someone else's plot");
        wolf.discard();
        horse.discard();
        friendsWolf.discard();

        // ---- graves
        GraveManager graves = GraveManager.get();
        ServerPlayer victim = fakePlayer(server, "Victim");
        victim.connection.markClientLoaded(); // simulated players never send "client loaded", so they'd be invulnerable
        victim.teleportTo(985.5, Y, 1005.5);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));
        victim.getInventory().add(sword);
        victim.getInventory().add(new ItemStack(Items.DIRT, 64));
        victim.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        victim.setExperienceLevels(10);
        victim.kill(level);
        List<Grave> victimGraves = graves.ownedBy(victim.getUUID());
        expect(victimGraves.size() == 1 && victimGraves.get(0).items().size() == 3 && victimGraves.get(0).xp() > 0,
                "death stores items and XP in a grave");
        Grave grave = victimGraves.isEmpty() ? null : victimGraves.get(0);
        BlockPos gravePos = grave == null ? p(985, 1005) : grave.pos();
        expect(level.getBlockState(gravePos).is(Blocks.PLAYER_HEAD), "grave head placed at death spot");
        expect(level.getEntitiesOfClass(ItemEntity.class, new AABB(gravePos).inflate(4)).isEmpty(), "no items dropped on the ground");
        griefer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        click(griefer, gravePos, ItemStack.EMPTY);
        griefer.gameMode.destroyBlock(gravePos);
        expect(graves.at(level, gravePos) != null && level.getBlockState(gravePos).is(Blocks.PLAYER_HEAD)
                && !griefer.getInventory().contains(st -> st.is(Items.DIAMOND_SWORD)), "others can't open or break the grave");
        level.explode(null, gravePos.getX() + 0.5, gravePos.getY(), gravePos.getZ() + 1.5, 4.0F, Level.ExplosionInteraction.TNT);
        expect(level.getBlockState(gravePos).is(Blocks.PLAYER_HEAD), "explosions don't destroy graves");
        Creeper creeper = EntityTypes.CREEPER.spawn(level, gravePos.east(), EntitySpawnReason.COMMAND);
        level.explode(creeper, gravePos.getX() + 1.5, gravePos.getY(), gravePos.getZ() + 0.5, 3.0F, Level.ExplosionInteraction.MOB);
        creeper.discard();
        expect(level.getBlockState(gravePos).is(Blocks.PLAYER_HEAD), "creeper explosion doesn't destroy graves");
        WitherBoss wither = EntityTypes.WITHER.create(level, EntitySpawnReason.COMMAND);
        expect(NeoForge.EVENT_BUS.post(new LivingDestroyBlockEvent(wither, gravePos, level.getBlockState(gravePos))).isCanceled(),
                "the Wither can't break graves");
        victim = server.getPlayerList().respawn(victim, false, Entity.RemovalReason.KILLED);
        victim.teleportTo(985.5, Y, 1007.5);
        int xpBefore = victim.totalExperience;
        click(victim, gravePos, ItemStack.EMPTY);
        expect(victim.getInventory().contains(st -> st.is(Items.DIAMOND_SWORD) && "Excalibur".equals(st.getHoverName().getString()))
                && victim.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)
                && victim.getInventory().contains(st -> st.is(Items.DIRT) && st.getCount() == 64), "owner gets items back (armor re-equipped)");
        expect(victim.totalExperience > xpBefore, "owner gets XP back");
        expect(graves.ownedBy(victim.getUUID()).isEmpty() && !level.getBlockState(gravePos).is(Blocks.PLAYER_HEAD), "claimed grave is removed");
        // A second grave that stays for the restart test.
        ServerPlayer victim2 = fakePlayer(server, "Victim2");
        victim2.connection.markClientLoaded();
        victim2.teleportTo(986.5, Y, 1015.5);
        ItemStack sword2 = new ItemStack(Items.DIAMOND_SWORD);
        sword2.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));
        victim2.getInventory().add(sword2);
        victim2.kill(level);

        // ---- moderation
        expect(Durations.parse("1d12h") == 129_600_000L && Durations.parse("30m") == 1_800_000L && Durations.parse("abc") == -1,
                "durations parse");
        console(server, "mute Griefer 10m spamming");
        expect(chatBlocked(griefer, "hello"), "muted player can't chat");
        expect(commandBlocked(griefer, "msg Owner hi"), "muted player can't /msg");
        expect(!chatBlocked(owner, "hello"), "others can still chat");
        console(server, "unmute Griefer");
        expect(!chatBlocked(griefer, "hello"), "/unmute works");
        ModerationManager.get().setMute(griefer.getUUID(), new ModerationManager.Mute("Griefer", System.currentTimeMillis() - 1000, ""));
        expect(!chatBlocked(griefer, "hello"), "expired mute no longer applies");

        run(admin, "vanish");
        expect(!admin.broadcastToPlayer(griefer) && !admin.broadcastToPlayer(owner), "vanished admin is hidden from players");
        run(admin, "vanish");
        expect(admin.broadcastToPlayer(griefer), "unvanished admin is visible again");

        ServerPlayer banned = fakePlayer(server, "Banned");
        console(server, "tempban Banned 1h griefing");
        var ban = server.getPlayerList().getBans().get(banned.nameAndId());
        expect(ban != null && ban.getExpires() != null
                && Math.abs(ban.getExpires().getTime() - System.currentTimeMillis() - 3_600_000L) < 60_000L, "/tempban adds a vanilla ban ending in 1h");
        console(server, "pardon Banned");
        expect(!server.getPlayerList().getBans().isBanned(banned.nameAndId()), "/pardon lifts a temp ban");

        ServerPlayer suspect = fakePlayer(server, "Suspect");
        suspect.connection.markClientLoaded();
        suspect.teleportTo(1015.5, Y, 1015.5);
        console(server, "freeze Suspect");
        BlockPos near = p(1016, 1016);
        level.setBlockAndUpdate(near, Blocks.STONE.defaultBlockState());
        suspect.gameMode.destroyBlock(near);
        expect(level.getBlockState(near).is(Blocks.STONE), "frozen player can't break blocks");
        expect(commandBlocked(suspect, "help") && !commandBlocked(suspect, "msg Admin help me"), "frozen player can only use /msg");
        float suspectHealth = suspect.getHealth();
        suspect.hurtServer(level, level.damageSources().generic(), 5.0F);
        expect(suspect.getHealth() == suspectHealth, "frozen player can't be hurt");
        suspect.setPos(1025.5, Y, 1015.5); // "walks away"; the next ticks must put them back

        // ---- homes and tpa (warm-ups finish during the wait before /selftest check)
        ServerPlayer alice = fakePlayer(server, "Alice");
        ServerPlayer bob = fakePlayer(server, "Bob");
        ServerPlayer carol = fakePlayer(server, "Carol");
        carol.connection.markClientLoaded();
        alice.teleportTo(1030.5, Y, 1015.5);
        run(alice, "sethome");
        expect(HomeManager.get().home(alice.getUUID(), "home") != null, "/sethome saves a home");
        alice.teleportTo(1004.5, Y, 1004.5); // inside Owner's t_home
        run(alice, "sethome");
        expect(HomeManager.get().home(alice.getUUID(), "home").x() == 1004.5, "a home can be set anywhere, even in someone else's plot");
        alice.teleportTo(1032.5, Y, 1015.5);
        run(alice, "sethome");
        alice.teleportTo(1030.5, Y, 1015.5);
        run(alice, "sethome");
        expect(HomeManager.get().home(alice.getUUID(), "home").x() == 1030.5
                && HomeManager.get().homes(alice.getUUID()).size() == 1, "/sethome again moves the same home");
        for (String n : new String[] {"Farm", "mine", "base2", "nether_hub"}) {
            run(alice, "sethome " + n);
        }
        run(alice, "sethome sixth");
        expect(HomeManager.get().homes(alice.getUUID()).size() == HomeManager.MAX_HOMES
                && HomeManager.get().home(alice.getUUID(), "farm") != null
                && HomeManager.get().home(alice.getUUID(), "sixth") == null, "5 named homes, not a 6th (names not case sensitive)");
        run(alice, "delhome mine");
        run(alice, "sethome sixth");
        expect(HomeManager.get().home(alice.getUUID(), "mine") == null && HomeManager.get().home(alice.getUUID(), "sixth") != null,
                "/delhome <name> frees a slot");
        for (String n : new String[] {"farm", "base2", "nether_hub", "sixth"}) {
            run(alice, "delhome " + n);
        }
        alice.teleportTo(990.5, Y, 1025.5);
        run(alice, "home");
        expect(Teleports.isPending(alice.getUUID()) && alice.position().distanceTo(new Vec3(990.5, Y, 1025.5)) < 0.1,
                "/home starts a warm-up instead of teleporting at once");

        carol.teleportTo(1034.5, Y, 1015.5);
        run(carol, "sethome");
        carol.teleportTo(990.5, Y, 1022.5);
        run(carol, "home");
        carol.hurtServer(level, level.damageSources().generic(), 1.0F);
        expect(!Teleports.isPending(carol.getUUID()), "taking damage cancels the warm-up");

        bob.teleportTo(1036.5, Y, 1018.5);
        ServerPlayer dave = fakePlayer(server, "Dave");
        dave.teleportTo(1038.5, Y, 1022.5);
        run(dave, "tpa Bob");
        run(bob, "tpaccept");
        expect(Teleports.isPending(dave.getUUID()), "/tpa + /tpaccept starts the requester's warm-up");
        run(bob, "tpahere Owner");
        run(owner, "tpdeny");
        expect(!Teleports.isPending(owner.getUUID()), "/tpdeny refuses a request");

        admin.teleportTo(1044.5, Y, 1015.5);
        run(admin, "sethome");
        admin.teleportTo(990.5, Y, 1015.5);
        run(admin, "home");
        expect(admin.position().distanceTo(new Vec3(1044.5, Y, 1015.5)) < 0.1, "admins teleport instantly");

        // ---- /plot claimhere: players make their own 50x50 plot
        ServerPlayer settler = fakePlayer(server, "Settler");
        ServerPlayer neighbor = fakePlayer(server, "Neighbor");
        settler.teleportTo(3000.5, Y, 3000.5);
        run(settler, "plot claimhere");
        expect(pm.plotsOwnedBy(settler.getUUID()).isEmpty(), "/plot claimhere only shows the plot until /plot confirm");
        run(settler, "plot confirm");
        List<Plot> settled = pm.plotsOwnedBy(settler.getUUID());
        expect(settled.size() == 1 && settled.getFirst().selfClaimed()
                && settled.getFirst().min().equals(new BlockPos(2975, level.getMinY(), 2975))
                && settled.getFirst().max().equals(new BlockPos(3024, level.getMaxY(), 3024)),
                "/plot confirm makes a 50x50 full-height plot around the player");
        expect(dev.safemc.safeplots.plot.PlotBorders.showing(settler.getUUID()) == null, "the glowing border goes away after /plot confirm");
        expect(!pm.canModify(neighbor, level, new BlockPos(3010, Y, 3010)), "others can't build in a self-made plot");
        settler.teleportTo(3500.5, Y, 3500.5);
        pm.setMaxClaims(settler.getUUID(), 3);
        run(settler, "plot claimhere");
        run(settler, "plot confirm");
        expect(pm.plotsOwnedBy(settler.getUUID()).size() == 1, "only one self-made plot per player, even with a higher limit");
        pm.setMaxClaims(settler.getUUID(), 1);
        neighbor.teleportTo(3059.5, Y, 3000.5); // its square would start at 3034: only 9 free blocks
        run(neighbor, "plot claimhere");
        run(neighbor, "plot confirm");
        expect(pm.plotsOwnedBy(neighbor.getUUID()).isEmpty(), "a self-made plot needs a 10-block gap to other plots");
        neighbor.teleportTo(3060.5, Y, 3000.5); // starts at 3035: exactly 10 free blocks
        run(neighbor, "plot claimhere");
        run(neighbor, "plot confirm");
        expect(pm.plotsOwnedBy(neighbor.getUUID()).size() == 1, "exactly 10 free blocks is enough");
        run(neighbor, "plot abandon");
        neighbor.teleportTo(5000.5, Y, 5000.5);
        pm.setMaxClaims(neighbor.getUUID(), 0);
        run(neighbor, "plot claimhere");
        run(neighbor, "plot confirm");
        pm.setMaxClaims(neighbor.getUUID(), 1);
        expect(pm.plotsOwnedBy(neighbor.getUUID()).isEmpty(), "self-made plots count toward the plot limit");
        BlockPos spawn = server.getRespawnData().pos();
        neighbor.teleportTo(spawn.getX() + 34.5, Y, spawn.getZ() + 0.5); // square reaches to 9 blocks from spawn
        run(neighbor, "plot claimhere");
        run(neighbor, "plot confirm");
        expect(pm.plotsOwnedBy(neighbor.getUUID()).isEmpty(), "no self-made plots within 10 blocks of spawn");
        String settledName = settled.getFirst().name();
        settler.teleportTo(3000.5, Y, 3000.5);
        run(settler, "plot abandon");
        expect(pm.plot(settledName) == null && pm.plotsOwnedBy(settler.getUUID()).isEmpty(),
                "abandoning a self-made plot deletes it");

        // /mv claims: 3 plots per player in this world, then 0 (no claiming), then back to the default 1.
        console(server, "mv claims world 3");
        for (int i = 0; i < 4; i++) {
            settler.teleportTo(3000.5 + 200 * i, Y, 3000.5);
            run(settler, "plot claimhere");
            run(settler, "plot confirm");
        }
        List<Plot> three = pm.plotsOwnedBy(settler.getUUID());
        expect(three.size() == 3 && three.stream().allMatch(Plot::selfClaimed), "/mv claims world 3: three self-made plots, not a fourth: " + three.size());
        String listed = output(settler, "plot list");
        expect(listed.contains("(3/3)") && three.stream().allMatch(pl -> listed.contains(pl.name() + ": center")),
                "/plot list shows your plots here with coordinates");
        console(server, "mv claims world 0");
        neighbor.teleportTo(3000.5, Y, 4000.5);
        run(neighbor, "plot claimhere");
        run(neighbor, "plot confirm");
        expect(pm.plotsOwnedBy(neighbor.getUUID()).isEmpty(), "/mv claims world 0: nobody can claim there");
        console(server, "mv claims world 1");
        for (Plot plot : three) {
            run(settler, "plot abandon " + plot.name());
        }
        expect(pm.plotsOwnedBy(settler.getUUID()).isEmpty() && pm.worldClaims(PlotManager.dimensionId(level)) == 1,
                "back to one plot per world");

        // ---- admin bypass
        BlockPos stone2 = p(1007, 1001);
        level.setBlockAndUpdate(stone2, Blocks.STONE.defaultBlockState());
        admin.gameMode.destroyBlock(stone2);
        expect(level.getBlockState(stone2).is(Blocks.STONE), "admin without bypass is blocked");
        run(admin, "plot bypass");
        admin.gameMode.destroyBlock(stone2);
        expect(level.getBlockState(stone2).isAir(), "admin with bypass can break");
        run(admin, "plot bypass");

        // ---- claim sign protection
        griefer.gameMode.destroyBlock(sign1);
        expect(level.getBlockState(sign1).is(Blocks.OAK_SIGN), "outsider can't break claim sign");

        // ---- explosion across the border: stone row x=995..1004 at z=1005
        for (int x = 995; x <= 1004; x++) {
            level.setBlockAndUpdate(p(x, 1005), Blocks.STONE.defaultBlockState());
        }
        level.explode(null, 1000.0, Y + 0.5, 1005.5, 4.0F, Level.ExplosionInteraction.TNT);
        expect(level.getBlockState(p(1000, 1005)).is(Blocks.STONE) && level.getBlockState(p(1001, 1005)).is(Blocks.STONE),
                "explosion leaves plot blocks");
        expect(level.getBlockState(p(999, 1005)).isAir(), "explosion still breaks blocks outside (control)");
        expect(stand.isAlive(), "armor stand survived explosion");

        // ---- tick-based scenarios, verified by /selftest check
        level.setBlockAndUpdate(p(999, 1008), Blocks.WATER.defaultBlockState());          // water just outside
        level.setBlockAndUpdate(p(1005, 999), Blocks.LAVA.defaultBlockState());           // lava just outside
        pistonScenario(level, 998, 1002, 997);                                               // pushes toward x=1000
        pistonScenario(level, 986, 1025, 985);                                               // control, no plot
        level.setBlockAndUpdate(p(1026, 1003), Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.WEST));
        ((BaseContainerBlockEntity) level.getBlockEntity(p(1026, 1003))).setItem(0, new ItemStack(Items.WATER_BUCKET));
        level.setBlockAndUpdate(p(1027, 1003), Blocks.REDSTONE_BLOCK.defaultBlockState());
        hopperScenario(level, 1031, 1001);                                                   // chest in t_exact, hopper below
        hopperScenario(level, 1040, 1001);                                                   // control, no plot
        for (int x = 1001; x <= 1008; x++) {                                                // wool inside t_home, fire outside
            level.setBlockAndUpdate(p(x, 1009), Blocks.WOOL.white().defaultBlockState());
            level.setBlockAndUpdate(p(x, 1010), Blocks.FIRE.defaultBlockState());
            level.setBlockAndUpdate(p(x, 1012), Blocks.WOOL.white().defaultBlockState());    // control, no plot
            level.setBlockAndUpdate(p(x, 1013), Blocks.FIRE.defaultBlockState());
        }
        results.add("info tick scenarios placed; run /selftest check in ~15s");
    }

    private static void pistonScenario(ServerLevel level, int pistonX, int z, int powerX) {
        level.setBlockAndUpdate(p(pistonX, z), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(p(pistonX + 1, z), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(p(powerX, z), Blocks.REDSTONE_BLOCK.defaultBlockState());
    }

    private static void hopperScenario(ServerLevel level, int x, int z) {
        BlockPos chest = new BlockPos(x, Y + 2, z);
        level.setBlockAndUpdate(chest.below(), Blocks.HOPPER.defaultBlockState());
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        ((BaseContainerBlockEntity) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 5));
    }

    // ------------------------------------------------------------------ tick checks

    private static void check(MinecraftServer server, ServerLevel level, PlotManager pm) {
        expect(countFluid(level, 1000, 1009, 1000, 1009) == 0, "water/lava did not flow into t_home");
        expect(countFluid(level, 994, 999, 1003, 1013) > 1, "water did flow outside the plot (control)");
        expect(level.getBlockState(p(999, 1002)).is(Blocks.STONE) && level.getBlockState(p(1000, 1002)).isAir(),
                "piston did not push block into plot");
        expect(level.getBlockState(p(988, 1025)).is(Blocks.STONE), "piston outside plots works (control)");
        expect(countFluid(level, 1020, 1025, 1000, 1005) == 0, "dispenser did not pour water into t_second");
        expect(!level.getBlockEntity(new BlockPos(1031, Y + 2, 1001), net.minecraft.world.level.block.entity.BlockEntityTypes.CHEST)
                        .map(BaseContainerBlockEntity::isEmpty).orElse(true)
                        && ((BaseContainerBlockEntity) level.getBlockEntity(new BlockPos(1031, Y + 1, 1001))).isEmpty(),
                "hopper below plot could not steal from chest");
        expect(!((BaseContainerBlockEntity) level.getBlockEntity(new BlockPos(1040, Y + 1, 1001))).isEmpty(),
                "hopper outside plots works (control)");
        int woolInPlot = 0, woolOutside = 0;
        for (int x = 1001; x <= 1008; x++) {
            woolInPlot += level.getBlockState(p(x, 1009)).is(Blocks.WOOL.white()) ? 1 : 0;
            woolOutside += level.getBlockState(p(x, 1012)).is(Blocks.WOOL.white()) ? 1 : 0;
        }
        expect(woolInPlot == 8, "fire burned no wool inside plot (" + woolInPlot + "/8 left)");
        results.add("info control: fire burned " + (8 - woolOutside) + "/8 wool outside plots");

        // abandon and admin ownership commands (after the tick checks so they don't affect them)
        ServerPlayer owner = server.getPlayerList().getPlayerByName("Owner");
        ServerPlayer griefer = server.getPlayerList().getPlayerByName("Griefer");
        ServerPlayer admin = server.getPlayerList().getPlayerByName("Admin");

        // ---- freeze held the player in place, then unfreeze
        ServerPlayer suspect = server.getPlayerList().getPlayerByName("Suspect");
        // Simulated players have no network connection, so the server never ticks them; run one player tick by hand.
        NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(suspect));
        expect(suspect.position().distanceTo(new Vec3(1015.5, Y, 1015.5)) < 0.5, "frozen player is pulled back when moving");
        console(server, "unfreeze Suspect");
        BlockPos near = p(1016, 1016);
        suspect.gameMode.destroyBlock(near);
        expect(level.getBlockState(near).isAir(), "unfrozen player can act again");

        // ---- homes and tpa after the warm-up
        ServerPlayer alice = server.getPlayerList().getPlayerByName("Alice");
        expect(alice.position().distanceTo(new Vec3(1030.5, Y, 1015.5)) < 0.1, "/home teleports after the warm-up");
        ServerPlayer dave = server.getPlayerList().getPlayerByName("Dave");
        ServerPlayer bob = server.getPlayerList().getPlayerByName("Bob");
        expect(dave.position().distanceTo(bob.position()) < 0.1, "/tpa teleports to the other player after the warm-up");
        alice.teleportTo(990.5, Y, 1025.5);
        run(alice, "home");
        expect(!Teleports.isPending(alice.getUUID()), "cooldown blocks an immediate second teleport");

        // ---- /plot showlimits
        owner.teleportTo(1004.5, Y, 1015.5);
        run(owner, "plot showlimits");
        expect(PlotBorders.showing(owner.getUUID()) == null, "showlimits with several plots asks for a name");
        run(owner, "plot showlimits t_home");
        expect("t_home".equals(PlotBorders.showing(owner.getUUID())), "owner can show own plot borders");
        run(owner, "plot showlimits t_second");
        expect("t_second".equals(PlotBorders.showing(owner.getUUID())), "showlimits with another name switches plot");
        run(owner, "plot showlimits");
        expect(PlotBorders.showing(owner.getUUID()) == null, "running showlimits again hides the borders");
        run(griefer, "plot showlimits t_home");
        expect(PlotBorders.showing(griefer.getUUID()) == null, "outsider can't show someone else's plot");
        run(admin, "plot showlimits t_home");
        expect("t_home".equals(PlotBorders.showing(admin.getUUID())), "admin can show any plot");
        owner.teleportTo(1004.5, Y, 1004.5);
        run(owner, "plot showlimits");
        expect("t_home".equals(PlotBorders.showing(owner.getUUID())), "showlimits with no name uses the plot you stand in");
        run(owner, "plot showlimits");
        run(admin, "plot showlimits");
        Plot home = pm.plot("t_home");
        owner.teleportTo(1004.5, Y, 1004.5);
        run(owner, "plot abandon");
        expect(!home.isClaimed() && signLine(level, home.sign()).contains("AVAILABLE"), "/plot abandon frees plot and resets sign");
        expect(level.getBlockState(p(1002, 1002)).is(Blocks.STONE), "abandon keeps the buildings");
        console(server, "plot setowner t_home Owner");
        expect(owner.getUUID().equals(home.owner()) && signLine(level, home.sign()).contains("'s property"), "/plot setowner works");
        level.removeBlock(home.sign(), false);
        expect(owner.getUUID().equals(home.owner()), "plot stays claimed after sign destroyed");
        click(griefer, home.sign().below(), new ItemStack(Items.OAK_SIGN), Direction.UP);
        expect(signLine(level, home.sign()).contains("'s property"), "sign re-placed at the claim spot shows the owner immediately");
        console(server, "plot delete t_exact");
        expect(pm.plot("t_exact") == null, "/plot delete works");
    }

    private static void persist(MinecraftServer server, ServerLevel level, PlotManager pm) {
        Plot home = pm.plot("t_home");
        expect(home != null && UUIDUtil.createOfflinePlayerUUID("Owner").equals(home.owner()), "t_home still owned by Owner after restart");
        expect(pm.maxClaims(UUIDUtil.createOfflinePlayerUUID("Owner")) == 2, "claim limit survived restart");
        expect(pm.plot("t_exact") == null, "deleted plot stayed deleted");
        List<Grave> kept = GraveManager.get().ownedBy(UUIDUtil.createOfflinePlayerUUID("Victim2"));
        expect(kept.size() == 1 && kept.get(0).items().size() == 1
                && "Excalibur".equals(kept.get(0).items().get(0).getHoverName().getString()), "grave and its named item survived restart");
    }

    // ------------------------------------------------------------------ /mv worlds (run twice: before and after a restart)

    private static void worlds(MinecraftServer server, ServerLevel level, PlotManager pm) throws Exception {
        Worlds worlds = Worlds.get();
        if (worlds.world("st_normal") == null) {
            console(server, "mv create st_normal normal 42");
            console(server, "mv create st_void void");
            ServerLevel created = worlds.level(worlds.world("st_normal"));
            expect(created != null, "normal world created and loaded");
            created.setBlockAndUpdate(new BlockPos(0, 300, 0), Blocks.GOLD_BLOCK.defaultBlockState());
            results.add("     restart the server and run /selftest worlds again");
            return;
        }
        ServerLevel normal = worlds.level(worlds.world("st_normal"));
        expect(normal != null && normal.getSeed() == 42, "normal world reloaded after restart with its own seed");
        expect(normal != null && normal.getBlockState(new BlockPos(0, 300, 0)).is(Blocks.GOLD_BLOCK), "block placed before restart is still there");

        ServerPlayer walker = fakePlayer(server, "Walker");
        console(server, "mvtp st_normal Walker");
        BlockPos feet = walker.blockPosition();
        expect(walker.level() == normal, "/mvtp sent the player to the world");
        expect(walker.level().getBlockState(feet.below()).isFaceSturdy(walker.level(), feet.below(), Direction.UP)
                && walker.level().getBlockState(feet).isAir(), "landed standing on solid ground at " + feet.toShortString());

        console(server, "mvtp st_void Walker");
        ServerLevel voidLevel = worlds.level(worlds.world("st_void"));
        expect(walker.level() == voidLevel && voidLevel.getBlockState(walker.blockPosition().below()).is(Blocks.STONE),
                "void world: landed on a generated stone platform");

        console(server, "mvtp nether Walker");
        expect(walker.level().dimension() == net.minecraft.world.level.Level.NETHER, "/mvtp nether works");

        // ---- one inventory per group (main = world/nether/end; st_normal has its own)
        console(server, "mvtp world Walker");
        walker.getInventory().clearContent();
        walker.getEnderChestInventory().clearContent();
        walker.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        walker.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD));
        walker.setExperienceLevels(5);
        console(server, "mvtp st_normal Walker");
        expect(walker.getInventory().countItem(Items.DIAMOND) == 0 && walker.getEnderChestInventory().isEmpty() && walker.experienceLevel == 0,
                "another group starts with an empty inventory, ender chest and XP");
        walker.getInventory().setItem(0, new ItemStack(Items.DIRT, 7));
        console(server, "mvtp nether Walker");
        expect(walker.getInventory().countItem(Items.DIAMOND) == 3 && walker.getInventory().countItem(Items.DIRT) == 0
                && walker.getEnderChestInventory().countItem(Items.EMERALD) == 1 && walker.experienceLevel == 5,
                "nether shares the main inventory, ender chest and XP");
        console(server, "mvtp st_normal Walker");
        expect(walker.getInventory().countItem(Items.DIRT) == 7 && walker.getInventory().countItem(Items.DIAMOND) == 0,
                "st_normal kept its own inventory");
        console(server, "mv group st_normal main");
        expect(walker.getInventory().countItem(Items.DIAMOND) == 3, "moving a world into group main swaps the players inside");
        console(server, "mv group st_normal st_normal");
        expect(walker.getInventory().countItem(Items.DIRT) == 7, "moving it back restores its own inventory");

        ItemEntity thrown = new ItemEntity(normal, 0.5, 100, 0.5, new ItemStack(Items.DIAMOND));
        normal.addFreshEntity(thrown);
        expect(thrown.teleport(new net.minecraft.world.level.portal.TeleportTransition(server.overworld(), new Vec3(0.5, 100, 0.5),
                Vec3.ZERO, 0, 0, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING)) == null,
                "items can't travel to a world with another inventory");
        thrown.discard();

        // ---- portals
        dev.safemc.safeplots.world.Portals portals = dev.safemc.safeplots.world.Portals.get();
        portals.remove("st_gate");
        portals.remove("st_np");
        portals.add(new dev.safemc.safeplots.world.Portals.Entry("st_gate", "minecraft:overworld", 2000, Y, 2000, 2002, Y + 2, 2000, "st_normal", null, false));
        console(server, "mvtp world Walker");
        walker.teleportTo(server.overworld(), 2001.5, Y, 2000.5, java.util.Set.of(), 0, 0, true);
        dev.safemc.safeplots.world.Portals.onPlayerTick(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(walker));
        expect(walker.level() == normal, "walking into a portal area sends the player to its world");
        expect(walker.getInventory().countItem(Items.DIRT) == 7, "arriving by portal switches the inventory too");
        portals.add(new dev.safemc.safeplots.world.Portals.Entry("st_np", "minecraft:overworld", 2010, Y, 2010, 2010, Y, 2010, "st_normal", null, false));
        var transition = ((net.minecraft.world.level.block.Portal) Blocks.NETHER_PORTAL)
                .getPortalDestination(server.overworld(), walker, new BlockPos(2010, Y, 2010));
        expect(transition != null && transition.newLevel() == normal, "a nether portal inside a portal area leads to its world");
        portals.remove("st_gate");
        portals.remove("st_np");

        // A water portal: 1x2 opening at (2020, Y..Y+1, 2020), filled with water that must not flow out.
        ServerLevel ow = server.overworld();
        for (BlockPos p : BlockPos.betweenClosed(2019, Y - 1, 2019, 2021, Y + 2, 2021)) {
            ow.setBlock(p, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        ow.setBlock(new BlockPos(2020, Y - 1, 2020), Blocks.GLOWSTONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        portals.add(new dev.safemc.safeplots.world.Portals.Entry("st_water", "minecraft:overworld", 2020, Y, 2020, 2020, Y + 1, 2020, "st_normal", null, false));
        console(server, "mv portal water st_water");
        expect(portals.portal("st_water").water(), "/mv portal water marks the portal");
        expect(ow.getFluidState(new BlockPos(2020, Y, 2020)).isSource() && ow.getFluidState(new BlockPos(2020, Y + 1, 2020)).isSource(),
                "/mv portal water fills the opening with water sources");
        expect(dev.safemc.safeplots.world.Portals.keepsFluidIn(ow, new BlockPos(2020, Y, 2020), new BlockPos(2021, Y, 2020))
                        && !dev.safemc.safeplots.world.Portals.keepsFluidIn(ow, new BlockPos(2020, Y + 1, 2020), new BlockPos(2020, Y, 2020)),
                "water can't flow out of a water portal but can move inside it");
        ow.getFluidState(new BlockPos(2020, Y, 2020)).tick(ow, new BlockPos(2020, Y, 2020), ow.getBlockState(new BlockPos(2020, Y, 2020)));
        expect(ow.getFluidState(new BlockPos(2021, Y, 2020)).isEmpty() && ow.getFluidState(new BlockPos(2019, Y, 2020)).isEmpty(),
                "a water portal's water doesn't spread sideways");
        console(server, "mv portal delete st_water");
        expect(portals.portal("st_water") == null && ow.getFluidState(new BlockPos(2020, Y, 2020)).isEmpty(),
                "deleting a water portal removes its water");

        // ---- hub
        console(server, "mv sethub st_normal");
        expect(worlds.hub().equals("st_normal"), "/mv sethub picks the hub world");
        var commands = server.getCommands().getDispatcher().getRoot();
        expect(!commands.getChild("mvtp").canUse(walker.createCommandSourceStack()) && commands.getChild("hub").canUse(walker.createCommandSourceStack()),
                "players can use /hub but not /mvtp");
        console(server, "mvtp world Walker");
        walker.resetStat(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.LEAVE_GAME));
        dev.safemc.safeplots.world.Hub.onLogin(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(walker));
        expect(walker.level() == normal, "a first-time player starts in the hub");
        console(server, "mvtp world Walker");
        walker.awardStat(net.minecraft.stats.Stats.LEAVE_GAME);
        dev.safemc.safeplots.world.Hub.onLogin(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(walker));
        expect(walker.level() == server.overworld(), "a returning player stays where they logged out");

        // The hub is protected: only staff may change it, but everyone may use levers, doors etc.
        var hubSpawn = worlds.spawnDestination("st_normal");
        BlockPos hb = BlockPos.containing(hubSpawn.pos()).above(4);
        walker.teleportTo(normal, hubSpawn.pos().x, hubSpawn.pos().y, hubSpawn.pos().z, java.util.Set.of(), 0, 0, true);
        normal.setBlockAndUpdate(hb, Blocks.STONE.defaultBlockState());
        walker.gameMode.destroyBlock(hb);
        expect(normal.getBlockState(hb).is(Blocks.STONE), "players can't break blocks in the hub");
        click(walker, hb, new ItemStack(Items.DIRT));
        expect(normal.getBlockState(hb.above()).isAir(), "players can't place blocks in the hub");
        normal.setBlockAndUpdate(hb.above(), Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
        click(walker, hb.above(), ItemStack.EMPTY);
        expect(normal.getBlockState(hb.above()).getValue(net.minecraft.world.level.block.LeverBlock.POWERED), "players can still flip levers in the hub");
        normal.setBlockAndUpdate(hb.above(), Blocks.AIR.defaultBlockState());
        normal.explode(null, hb.getX() + 0.5, hb.getY() + 1, hb.getZ() + 0.5, 3.0F, Level.ExplosionInteraction.TNT);
        expect(normal.getBlockState(hb).is(Blocks.STONE), "explosions don't break the hub");
        String hubClaim = dev.safemc.safeplots.plot.SelfClaims.problem(pm, walker, dev.safemc.safeplots.plot.SelfClaims.candidate(pm, walker));
        expect(hubClaim != null && hubClaim.contains("hub"), "players can't /plot claimhere in the hub");
        walker.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        // Real clients confirm loading and arriving in a world; until then players can't be hurt, which would hide the rule.
        walker.connection.markClientLoaded();
        walker.hasChangedDimension();
        walker.setHealth(walker.getMaxHealth());
        walker.hurtServer(normal, normal.damageSources().generic(), 5);
        walker.hurtServer(normal, normal.damageSources().playerAttack(walker), 5);
        expect(walker.getHealth() == walker.getMaxHealth(), "nobody takes damage in the hub");
        walker.teleportTo(normal, hubSpawn.pos().x, normal.getMinY() - 70, hubSpawn.pos().z, java.util.Set.of(), 0, 0, true);
        walker.hasChangedDimension();
        walker.hurtServer(normal, normal.damageSources().fellOutOfWorld(), 4);
        expect(walker.getHealth() == walker.getMaxHealth() && walker.position().distanceTo(hubSpawn.pos()) < 2,
                "falling into the hub's void takes you back to its spawn (health " + walker.getHealth() + ", at " + walker.position()
                + ", spawn " + hubSpawn.pos() + ", in " + walker.level().dimension().identifier() + ")");
        walker.getFoodData().setFoodLevel(3);
        dev.safemc.safeplots.world.Hub.feed(worlds);
        expect(walker.getFoodData().getFoodLevel() == 20, "hunger stays full in the hub");
        ServerPlayer hubAdmin = fakePlayer(server, "HubAdmin");
        server.getPlayerList().op(hubAdmin.nameAndId());
        hubAdmin.teleportTo(normal, hubSpawn.pos().x, hubSpawn.pos().y, hubSpawn.pos().z, java.util.Set.of(), 0, 0, true);
        hubAdmin.gameMode.destroyBlock(hb);
        expect(normal.getBlockState(hb).isAir(), "staff can build in the hub without /plot bypass");
        server.getPlayerList().remove(hubAdmin);
        console(server, "mvtp world Walker");

        // ---- per-world game mode and mob spawning
        net.minecraft.world.level.GameType survival = net.minecraft.world.level.GameType.SURVIVAL;
        net.minecraft.world.level.GameType creative = net.minecraft.world.level.GameType.CREATIVE;
        console(server, "mvtp world Walker");
        walker.setGameMode(survival);
        console(server, "mv gamemode st_normal creative");
        console(server, "mvtp st_normal Walker");
        expect(walker.gameMode.getGameModeForPlayer() == creative, "arriving in a creative world puts players in creative");
        console(server, "mvtp world Walker");
        expect(walker.gameMode.getGameModeForPlayer() == survival, "leaving it gives their own game mode back");
        ServerPlayer ruleAdmin = fakePlayer(server, "RuleAdmin");
        server.getPlayerList().op(ruleAdmin.nameAndId());
        console(server, "mvtp world RuleAdmin");
        ruleAdmin.setGameMode(survival);
        console(server, "mvtp st_normal RuleAdmin");
        expect(ruleAdmin.gameMode.getGameModeForPlayer() == creative, "admins are put in the world's game mode too");
        console(server, "mvtp world RuleAdmin");
        expect(ruleAdmin.gameMode.getGameModeForPlayer() == survival, "and get their own mode back when they leave");
        server.getPlayerList().remove(ruleAdmin);
        console(server, "mv group st_normal main");
        console(server, "mvtp st_normal Walker");
        expect(walker.gameMode.getGameModeForPlayer() == creative, "creative world sharing the main inventory: creative inside");
        console(server, "mvtp world Walker");
        expect(walker.gameMode.getGameModeForPlayer() == survival, "creative world sharing the main inventory: survival again outside");
        console(server, "mv group st_normal st_normal");
        console(server, "mvtp st_normal Walker");
        console(server, "mv gamemode st_normal none");
        expect(walker.gameMode.getGameModeForPlayer() == survival, "/mv gamemode none gives players in the world their own mode back");
        console(server, "mvtp world Walker");
        expect(worlds.world("st_normal").gameMode() == null, "/mv gamemode none clears the rule");

        BlockPos mobPos = BlockPos.containing(worlds.spawnDestination("st_normal").pos());
        console(server, "mv mobs st_normal off");
        expect(!worlds.world("st_normal").mobs(), "/mv mobs off is saved");
        var natural = EntityTypes.ZOMBIE.spawn(normal, mobPos, EntitySpawnReason.NATURAL);
        expect(natural == null || natural.isRemoved(), "no natural spawns in a world with mobs off");
        var cow = EntityTypes.COW.spawn(normal, mobPos, EntitySpawnReason.CHUNK_GENERATION);
        expect(cow == null || cow.isRemoved(), "no animals from world generation either");
        var egg = EntityTypes.ZOMBIE.spawn(normal, mobPos, EntitySpawnReason.SPAWN_ITEM_USE);
        expect(egg != null && !egg.isRemoved(), "spawn eggs still work with mobs off");
        if (egg != null) {
            egg.discard();
        }
        console(server, "mv mobs st_normal on");
        var again = EntityTypes.ZOMBIE.spawn(normal, mobPos, EntitySpawnReason.NATURAL);
        expect(again != null && !again.isRemoved(), "/mv mobs on allows natural spawns again");
        if (again != null) {
            again.discard();
        }

        console(server, "mvtp st_void Walker");
        console(server, "mv delete st_void confirm");
        expect(walker.level() == server.overworld(), "deleting a world sends players in it to spawn");
        expect(worlds.world("st_void") == null && server.getLevel(voidLevel.dimension()) == null, "deleted world is unloaded");
        console(server, "mv delete st_normal confirm");
        expect(worlds.hub().equals("world"), "deleting the hub world makes the main world the hub again");
        expect(worlds.worlds().isEmpty() || worlds.world("st_normal") == null, "cleanup");
        server.getPlayerList().remove(walker);
    }

    // ------------------------------------------------------------------ /schem

    private static void schem(MinecraftServer server, ServerLevel level, PlotManager pm) throws Exception {
        dev.safemc.safeplots.schematic.Schematics manager = dev.safemc.safeplots.schematic.Schematics.get();
        java.nio.file.Path dir = manager.dir();
        int current = net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();

        // v3: 3 x 2 x 2, Offset (1, 0, 0). Row z=0: stone, stairs (north), chest with 5 diamonds; (0,0,1) stone.
        CompoundTag v3 = new CompoundTag();
        v3.putInt("Version", 3);
        v3.putInt("DataVersion", current);
        v3.putShort("Width", (short) 3);
        v3.putShort("Height", (short) 2);
        v3.putShort("Length", (short) 2);
        v3.putIntArray("Offset", new int[] {1, 0, 0});
        CompoundTag blocks = new CompoundTag();
        CompoundTag palette = new CompoundTag();
        palette.putInt("minecraft:air", 0);
        palette.putInt("minecraft:stone", 1);
        palette.putInt("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]", 2);
        palette.putInt("minecraft:chest[facing=north,type=single,waterlogged=false]", 3);
        blocks.put("Palette", palette);
        blocks.putByteArray("Data", new byte[] {1, 2, 3, 1, 0, 0, 0, 0, 0, 0, 0, 0});
        CompoundTag item = new CompoundTag();
        item.putByte("Slot", (byte) 0);
        item.putString("id", "minecraft:diamond");
        item.putInt("count", 5);
        net.minecraft.nbt.ListTag items = new net.minecraft.nbt.ListTag();
        items.add(item);
        CompoundTag chestData = new CompoundTag();
        chestData.put("Items", items);
        CompoundTag chest = new CompoundTag();
        chest.putIntArray("Pos", new int[] {2, 0, 0});
        chest.putString("Id", "minecraft:chest");
        chest.put("Data", chestData);
        net.minecraft.nbt.ListTag bes = new net.minecraft.nbt.ListTag();
        bes.add(chest);
        blocks.put("BlockEntities", bes);
        v3.put("Blocks", blocks);
        CompoundTag root = new CompoundTag();
        root.put("Schematic", v3);
        net.minecraft.nbt.NbtIo.writeCompressed(root, dir.resolve("st_house.schem"));

        // v2 from 1.16.5: one grass_path (renamed dirt_path since), WorldEdit offset -1 on X.
        CompoundTag v2 = new CompoundTag();
        v2.putInt("Version", 2);
        v2.putInt("DataVersion", 2586);
        v2.putShort("Width", (short) 1);
        v2.putShort("Height", (short) 1);
        v2.putShort("Length", (short) 1);
        CompoundTag p2 = new CompoundTag();
        p2.putInt("minecraft:grass_path", 0);
        v2.put("Palette", p2);
        v2.putByteArray("BlockData", new byte[] {0});
        CompoundTag meta = new CompoundTag();
        meta.putInt("WEOffsetX", -1);
        meta.putInt("WEOffsetY", 0);
        meta.putInt("WEOffsetZ", 0);
        v2.put("Metadata", meta);
        java.nio.file.Files.createDirectories(dir.resolve("old"));
        net.minecraft.nbt.NbtIo.writeCompressed(v2, dir.resolve("old/st_path.schem"));

        // MCEdit / WorldEdit 6, 4 x 1 x 2. Row z=0: granite (1:1), oak stairs facing north (53:3), a flower pot with a
        // poppy (140 + FlowerPot block entity), a bed head (26:10). Row z=1: a chest facing north (54:2) with an
        // old-style "Chest" block entity holding 5 diamonds, red wool (35:14), a note block (25 + Music, note 5), the
        // bed's foot facing north (26:2). The bed is blue (color 11 in its Bed block entities).
        CompoundTag legacy = new CompoundTag();
        legacy.putShort("Width", (short) 4);
        legacy.putShort("Height", (short) 1);
        legacy.putShort("Length", (short) 2);
        legacy.putString("Materials", "Alpha");
        legacy.putByteArray("Blocks", new byte[] {1, 53, (byte) 140, 26, 54, 35, 25, 26});
        legacy.putByteArray("Data", new byte[] {1, 3, 0, 10, 2, 14, 0, 2});
        CompoundTag oldItem = new CompoundTag();
        oldItem.putByte("Slot", (byte) 0);
        oldItem.putString("id", "minecraft:diamond");
        oldItem.putByte("Count", (byte) 5);
        oldItem.putShort("Damage", (short) 0);
        net.minecraft.nbt.ListTag oldItems = new net.minecraft.nbt.ListTag();
        oldItems.add(oldItem);
        CompoundTag oldChest = new CompoundTag();
        oldChest.putString("id", "Chest");
        oldChest.putInt("x", 0);
        oldChest.putInt("y", 0);
        oldChest.putInt("z", 1);
        oldChest.put("Items", oldItems);
        net.minecraft.nbt.ListTag oldBes = new net.minecraft.nbt.ListTag();
        oldBes.add(oldChest);
        CompoundTag oldPot = new CompoundTag();
        oldPot.putString("id", "FlowerPot");
        oldPot.putInt("x", 2);
        oldPot.putInt("y", 0);
        oldPot.putInt("z", 0);
        oldPot.putString("Item", "minecraft:red_flower");
        oldPot.putInt("Data", 0);
        oldBes.add(oldPot);
        CompoundTag oldNote = new CompoundTag();
        oldNote.putString("id", "minecraft:noteblock");
        oldNote.putInt("x", 2);
        oldNote.putInt("y", 0);
        oldNote.putInt("z", 1);
        oldNote.putByte("note", (byte) 5);
        oldNote.putByte("powered", (byte) 0);
        oldBes.add(oldNote);
        for (int bedZ = 0; bedZ <= 1; bedZ++) {
            CompoundTag oldBed = new CompoundTag();
            oldBed.putString("id", "minecraft:bed");
            oldBed.putInt("x", 3);
            oldBed.putInt("y", 0);
            oldBed.putInt("z", bedZ);
            oldBed.putInt("color", 11);
            oldBes.add(oldBed);
        }
        legacy.put("TileEntities", oldBes);
        legacy.putInt("WEOffsetX", -1);
        net.minecraft.nbt.NbtIo.writeCompressed(legacy, dir.resolve("st_legacy.schematic"));

        CompoundTag pocket = new CompoundTag();
        pocket.putShort("Width", (short) 1);
        pocket.putShort("Height", (short) 1);
        pocket.putShort("Length", (short) 1);
        pocket.putString("Materials", "Pocket");
        net.minecraft.nbt.NbtIo.writeCompressed(pocket, dir.resolve("st_pocket.schematic"));

        expect(manager.available().containsAll(List.of("st_house", "old/st_path", "st_legacy")), "/schem list finds files, also in subfolders");
        expect(manager.resolve("../safeplots.json") == null && manager.resolve("st_house") != null, "names can't leave the schematics folder");
        var legacySchem = dev.safemc.safeplots.schematic.SchematicReader.read(manager.resolve("st_legacy"), "st_legacy", server.registryAccess(), 1000);
        java.util.function.IntFunction<net.minecraft.world.level.block.state.BlockState> cell = i -> legacySchem.palette()[legacySchem.blocks()[i]];
        expect(legacySchem.size().equals("4 x 1 x 2") && legacySchem.unknownBlocks() == 0, "old .schematic read: " + legacySchem.size()
                + ", " + legacySchem.unknownBlocks() + " unknown");
        expect(cell.apply(0).is(Blocks.GRANITE), "1:1 is granite: " + cell.apply(0));
        expect(cell.apply(1).is(Blocks.OAK_STAIRS) && cell.apply(1).getValue(net.minecraft.world.level.block.StairBlock.FACING) == Direction.NORTH,
                "53:3 is oak stairs facing north: " + cell.apply(1));
        expect(cell.apply(4).is(Blocks.CHEST) && cell.apply(4).getValue(net.minecraft.world.level.block.ChestBlock.FACING) == Direction.NORTH,
                "54:2 is a chest facing north: " + cell.apply(4));
        expect(cell.apply(5).is(Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.RED)), "35:14 is red wool: " + cell.apply(5));
        expect(cell.apply(2).is(Blocks.POTTED_POPPY) && !legacySchem.blockEntities().containsKey(2),
                "old flower pot with a poppy becomes potted_poppy: " + cell.apply(2));
        net.minecraft.world.level.block.Block blueBed = Blocks.BED.pick(net.minecraft.world.item.DyeColor.BLUE);
        expect(cell.apply(3).is(blueBed) && cell.apply(7).is(blueBed)
                && cell.apply(3).getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD
                && cell.apply(7).getValue(net.minecraft.world.level.block.BedBlock.FACING) == Direction.NORTH,
                "old bed with color 11 becomes a blue bed, head and foot: " + cell.apply(3) + " / " + cell.apply(7));
        expect(cell.apply(6).is(Blocks.NOTE_BLOCK) && cell.apply(6).getValue(net.minecraft.world.level.block.NoteBlock.NOTE) == 5
                && !legacySchem.blockEntities().containsKey(6), "old note block keeps its pitch: " + cell.apply(6));
        CompoundTag legacyChest = legacySchem.blockEntities().get(4);
        expect(legacyChest != null && legacyChest.getStringOr("id", "").equals("minecraft:chest")
                && legacyChest.toString().contains("minecraft:diamond") && legacyChest.toString().contains("count:5"),
                "old Chest block entity upgraded with its 5 diamonds: " + legacyChest);
        expect(legacySchem.offset().equals(new BlockPos(-1, 0, 0)), "WorldEdit 6 paste offset kept");
        boolean refused = false;
        try {
            dev.safemc.safeplots.schematic.SchematicReader.read(manager.resolve("st_pocket"), "st_pocket", server.registryAccess(), 1000);
        } catch (java.io.IOException e) {
            refused = e.getMessage().contains("Pocket");
        }
        expect(refused, "Bedrock (Pocket) schematics are refused with an explanation");

        var house = dev.safemc.safeplots.schematic.SchematicReader.read(manager.resolve("st_house"), "st_house", server.registryAccess(), 1000);
        expect(house.width() == 3 && house.height() == 2 && house.length() == 2 && house.blockEntities().size() == 1, "v3 schematic read");
        var path = dev.safemc.safeplots.schematic.SchematicReader.read(manager.resolve("old/st_path"), "st_path", server.registryAccess(), 1000);
        expect(path.palette()[0].is(Blocks.DIRT_PATH), "1.16 grass_path upgraded to dirt_path");

        // Old leaves (decayable/check_decay) and glass panes, which old files store unconnected.
        CompoundTag panes = new CompoundTag();
        panes.putShort("Width", (short) 5);
        panes.putShort("Height", (short) 1);
        panes.putShort("Length", (short) 1);
        panes.putString("Materials", "Alpha");
        panes.putByteArray("Blocks", new byte[] {18, (byte) 160, (byte) 160, 0, (byte) 160});
        panes.putByteArray("Data", new byte[] {8, 14, 14, 0, 1});
        net.minecraft.nbt.NbtIo.writeCompressed(panes, dir.resolve("st_panes.schematic"));
        var paneSchem = dev.safemc.safeplots.schematic.SchematicReader.read(manager.resolve("st_panes"), "st_panes", server.registryAccess(), 1000);
        BlockState oldLeaves = paneSchem.palette()[paneSchem.blocks()[0]];
        expect(paneSchem.unknownBlocks() == 0 && oldLeaves.is(Blocks.OAK_LEAVES)
                && oldLeaves.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT), "old leaves read as leaves that don't decay: " + oldLeaves);

        ServerPlayer builder = fakePlayer(server, "Builder");
        BlockPos at = new BlockPos(3000, Y, 3000);
        BlockPos paneAt = at.offset(0, 5, 20);
        manager.setClipboard(builder.getUUID(), paneSchem);
        manager.paste(builder, manager.clipboard(builder.getUUID()), paneAt, false, n -> {});
        manager.finishAll();
        BlockState pane1 = level.getBlockState(paneAt.offset(1, 0, 0));
        BlockState pane2 = level.getBlockState(paneAt.offset(2, 0, 0));
        expect(pane1.getBlock() instanceof net.minecraft.world.level.block.StainedGlassPaneBlock && pane1.getValue(net.minecraft.world.level.block.CrossCollisionBlock.EAST)
                && pane2.getValue(net.minecraft.world.level.block.CrossCollisionBlock.WEST), "old glass panes join after pasting: " + pane1);
        BlockState lonePane = level.getBlockState(paneAt.offset(4, 0, 0));
        expect(lonePane.getValue(net.minecraft.world.level.block.CrossCollisionBlock.NORTH)
                && lonePane.getValue(net.minecraft.world.level.block.CrossCollisionBlock.WEST)
                && !pane1.getValue(net.minecraft.world.level.block.CrossCollisionBlock.NORTH), "a pane touching nothing becomes a + cross like before 1.9");
        manager.undo(builder.getUUID(), n -> {});
        manager.finishAll();
        expect(level.getBlockState(paneAt.offset(1, 0, 0)).isAir(), "undo removes the joined panes");
        builder.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, java.util.Set.of(), 0, 0, true);
        for (int dx = -2; dx <= 4; dx++) {
            for (int dz = -1; dz <= 3; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    level.setBlockAndUpdate(at.offset(dx, dy, dz), Blocks.GLASS.defaultBlockState());
                }
            }
        }

        manager.setClipboard(builder.getUUID(), house);
        manager.paste(builder, manager.clipboard(builder.getUUID()), at, false, n -> {});
        manager.finishAll();
        expect(level.getBlockState(at.offset(1, 0, 0)).is(Blocks.STONE) && level.getBlockState(at.offset(1, 0, 1)).is(Blocks.STONE),
                "pasted with the schematic's offset");
        expect(level.getBlockState(at.offset(2, 0, 0)).is(Blocks.OAK_STAIRS)
                && level.getBlockState(at.offset(2, 0, 0)).getValue(net.minecraft.world.level.block.StairBlock.FACING) == Direction.NORTH,
                "block states (stairs facing) kept");
        expect(level.getBlockEntity(at.offset(3, 0, 0)) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c
                && c.getItem(0).is(Items.DIAMOND) && c.getItem(0).getCount() == 5, "chest contents pasted");
        expect(level.getBlockState(at.offset(1, 1, 0)).isAir(), "air in the schematic replaces blocks");

        manager.undo(builder.getUUID(), n -> {});
        manager.finishAll();
        expect(level.getBlockState(at.offset(1, 0, 0)).is(Blocks.GLASS) && level.getBlockState(at.offset(3, 0, 0)).is(Blocks.GLASS)
                && level.getBlockState(at.offset(1, 1, 0)).is(Blocks.GLASS), "undo restores what was there");
        expect(level.getBlockEntity(at.offset(3, 0, 0)) == null, "undo removes pasted block entities");

        manager.rotate(builder.getUUID(), net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
        manager.paste(builder, manager.clipboard(builder.getUUID()), at, true, n -> {});
        manager.finishAll();
        // Clockwise 90: (x, y, z) -> (-z, y, x)
        expect(level.getBlockState(at.offset(0, 0, 1)).is(Blocks.STONE) && level.getBlockState(at.offset(-1, 0, 1)).is(Blocks.STONE),
                "rotated paste lands turned 90° clockwise");
        expect(level.getBlockState(at.offset(0, 0, 2)).is(Blocks.OAK_STAIRS)
                && level.getBlockState(at.offset(0, 0, 2)).getValue(net.minecraft.world.level.block.StairBlock.FACING) == Direction.EAST,
                "rotated stairs face east instead of north");
        expect(level.getBlockState(at.offset(0, 1, 1)).is(Blocks.GLASS), "paste noair leaves existing blocks where the schematic has air");
        manager.undo(builder.getUUID(), n -> {});
        manager.finishAll();
        expect(level.getBlockState(at.offset(0, 0, 1)).is(Blocks.GLASS), "second undo works too");

        // /schem copy + save: a stone base with a chest and stairs, copied, written, read back and pasted elsewhere.
        BlockPos src = at.offset(0, 0, 40);
        for (BlockPos q : BlockPos.betweenClosed(src, src.offset(2, 1, 2))) {
            level.setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
        }
        for (BlockPos q : BlockPos.betweenClosed(src, src.offset(2, 0, 2))) {
            level.setBlockAndUpdate(q, Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(src.offset(1, 1, 1), Blocks.CHEST.defaultBlockState());
        ((net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(src.offset(1, 1, 1))).setItem(0, new ItemStack(Items.EMERALD, 7));
        level.setBlockAndUpdate(src.offset(2, 1, 0), Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.SOUTH));
        server.getPlayerList().op(builder.nameAndId());
        builder.teleportTo(level, src.getX() - 0.5, src.getY(), src.getZ() + 0.5, java.util.Set.of(), 0, 0, true); // one block west of the corner
        run(builder, "schem copy " + src.getX() + " " + src.getY() + " " + src.getZ() + " "
                + (src.getX() + 2) + " " + (src.getY() + 1) + " " + (src.getZ() + 2));
        manager.finishAll();
        var copied = manager.clipboard(builder.getUUID());
        expect(copied != null && copied.schematic().size().equals("3 x 2 x 3") && copied.schematic().blockEntities().size() == 1
                && copied.schematic().offset().equals(new BlockPos(1, 0, 0)), "/schem copy: 3 x 2 x 3 with the chest, offset from where you stood");
        java.nio.file.Path saved = dir.resolve("st_copy.schem");
        java.nio.file.Files.deleteIfExists(saved);
        dev.safemc.safeplots.schematic.SchematicWriter.write(copied.schematic(), saved);
        var reread = dev.safemc.safeplots.schematic.SchematicReader.read(saved, "st_copy", server.registryAccess(), 1000);
        expect(reread.size().equals("3 x 2 x 3") && reread.offset().equals(new BlockPos(1, 0, 0)) && reread.unknownBlocks() == 0,
                "saved .schem reads back the same: " + reread.size() + " offset " + reread.offset());
        manager.setClipboard(builder.getUUID(), reread);
        BlockPos dest = at.offset(20, 0, 40);
        builder.teleportTo(level, dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, java.util.Set.of(), 0, 0, true);
        manager.paste(builder, manager.clipboard(builder.getUUID()), dest, false, n -> {});
        manager.finishAll();
        BlockPos corner = dest.offset(1, 0, 0);
        expect(level.getBlockState(corner).is(Blocks.STONE) && level.getBlockState(corner.offset(2, 0, 2)).is(Blocks.STONE),
                "the copy pastes at the same spot relative to you");
        expect(level.getBlockEntity(corner.offset(1, 1, 1)) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity copiedChest
                && copiedChest.getItem(0).is(Items.EMERALD) && copiedChest.getItem(0).getCount() == 7, "the chest and its 7 emeralds came along");
        expect(level.getBlockState(corner.offset(2, 1, 0)).is(Blocks.OAK_STAIRS)
                && level.getBlockState(corner.offset(2, 1, 0)).getValue(net.minecraft.world.level.block.StairBlock.FACING) == Direction.SOUTH,
                "stairs keep their direction");
        manager.undo(builder.getUUID(), n -> {});
        manager.finishAll();
        java.nio.file.Files.deleteIfExists(saved);

        manager.setClipboard(builder.getUUID(), path);
        manager.paste(builder, manager.clipboard(builder.getUUID()), at, false, n -> {});
        manager.finishAll();
        expect(level.getBlockState(at.offset(-1, 0, 0)).is(Blocks.DIRT_PATH), "v2 WorldEdit offset honoured");
        manager.undo(builder.getUUID(), n -> {});
        manager.finishAll();

        // Speed: a 64 x 64 x 64 checkerboard of stone and planks (262k blocks), all set from scratch.
        int n = 64 * 64 * 64;
        int[] cells = new int[n];
        for (int i = 0; i < n; i++) {
            cells[i] = i % 2;
        }
        var big = new dev.safemc.safeplots.schematic.Schematic("st_big", 64, 64, 64, BlockPos.ZERO,
                new net.minecraft.world.level.block.state.BlockState[] {Blocks.STONE.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState()},
                cells, java.util.Map.of(), 0, 0);
        manager.setClipboard(builder.getUUID(), big);
        BlockPos bigAt = new BlockPos(3200, Y, 3200);
        long t0 = System.nanoTime();
        manager.paste(builder, manager.clipboard(builder.getUUID()), bigAt, false, x -> {});
        manager.finishAll();
        long pasteMs = (System.nanoTime() - t0) / 1_000_000;
        expect(level.getBlockState(bigAt.offset(63, 63, 63)).is(Blocks.STONE) || level.getBlockState(bigAt.offset(63, 63, 63)).is(Blocks.OAK_PLANKS),
                "big paste finished");
        t0 = System.nanoTime();
        manager.undo(builder.getUUID(), x -> {});
        manager.finishAll();
        long undoMs = (System.nanoTime() - t0) / 1_000_000;
        results.add("info 262k blocks: paste " + pasteMs + " ms, undo " + undoMs + " ms (spread over ticks at 25 ms per tick)");

        java.nio.file.Files.deleteIfExists(dir.resolve("st_house.schem"));
        java.nio.file.Files.deleteIfExists(dir.resolve("old/st_path.schem"));
        java.nio.file.Files.deleteIfExists(dir.resolve("old"));
        java.nio.file.Files.deleteIfExists(dir.resolve("st_legacy.schematic"));
        server.getPlayerList().remove(builder);
    }

    /** Times a 296 x 131 x 304 paste (11.8M blocks, mostly air above a solid base) and its undo. Slow; run by hand. */
    /** Chat lines get the world in front: the server log shows "[world] <Chatter> hello there". */
    private static void chat(MinecraftServer server, ServerLevel level, PlotManager pm) {
        ServerPlayer chatter = fakePlayer(server, "Chatter");
        var event = new net.neoforged.neoforge.event.ServerChatEvent(chatter, "hello there", Component.literal("hello there"));
        NeoForge.EVENT_BUS.post(event);
        expect(event.isCanceled(), "chat is sent as our own line with the world tag (see the log)");
    }

    /** End crystals: off by default (they just break), /mv crystals world on makes them explode. */
    private static int countChests(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (dev.safemc.safeplots.reward.Rewards.isChest(p.getInventory().getItem(i))) {
                n += p.getInventory().getItem(i).getCount();
            }
        }
        return n;
    }

    private static int countPrizes(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (!stack.isEmpty() && !dev.safemc.safeplots.reward.Rewards.isChest(stack)) {
                n++;
            }
        }
        return n;
    }

    private static void rewards(MinecraftServer server, ServerLevel level, PlotManager pm) {
        var worlds = dev.safemc.safeplots.world.Worlds.get();
        var inventories = dev.safemc.safeplots.inventory.WorldInventories.get();
        var rewards = dev.safemc.safeplots.reward.Rewards.get();
        if (worlds.world("st_rew") == null) {
            console(server, "mv create st_rew void");
        }
        ServerLevel rew = worlds.level(worlds.world("st_rew"));
        java.nio.file.Path cfg = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("safeplots-rewards.txt");
        rewards.reload();
        String original;
        try {
            original = java.nio.file.Files.readString(cfg);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        try {
            java.nio.file.Files.writeString(cfg, original.replaceFirst("(?m)^world:.*$", "world: st_rew").replaceFirst("(?m)^daily:.*$", "daily: 2"));
            expect(rewards.reload() == null && rewards.level() == rew, "the reward world is set in the config file");
            expect(rewards.prizes().size() == 12 && rewards.totalWeight() == 100, "the wheel: 12 prizes, chances add up to 100");
            expect(rewards.prizes().getLast().rarest() && rewards.prizes().getLast().weight() == 1, "Papatya's book: 1%");
            ItemStack rare = dev.safemc.safeplots.reward.Rewards.rarestBook("Alice");
            var content = rare.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
            expect(content != null && content.author().equals("Papatya") && content.pages().getFirst().raw().getString().contains("Congratulations Alice")
                    && content.pages().getFirst().raw().getString().contains("Come find me"), "the rarest book is signed by Papatya, with the winner's name");
            expect(rewards.prizes().stream().anyMatch(z -> z.describe().contains("Mending") && z.describe().contains("Fortune III")),
                    "the enchanted book can be Efficiency IV, Fortune III, Protection IV or Mending");
            ServerPlayer p = fakePlayer(server, "Winner");
            p.teleportTo(rew, 0.5, 80, 0.5, java.util.Set.of(), 0, 0, true);
            inventories.sync(p);
            p.getInventory().clearContent();
            p.teleportTo(level, 0.5, 100, 0.5, java.util.Set.of(), 0, 0, true);
            inventories.sync(p);
            p.getInventory().clearContent();
            rewards.forget(p.getUUID());

            console(server, "rewards win Winner");
            console(server, "rewards win Winner");
            console(server, "rewards win Winner");
            expect(rewards.today(p.getUUID()) == 2 && countChests(p) == 0, "3 wins: 2 chests (the daily limit), none in this world's inventory");
            p.getInventory().setItem(0, dev.safemc.safeplots.reward.Rewards.chest(1));
            rewards.open(p, p.getInventory().getItem(0));
            expect(!rewards.spinning(p.getUUID()) && countChests(p) == 1, "outside the reward world it doesn't open");
            p.getInventory().clearContent();

            p.teleportTo(rew, 0.5, 80, 0.5, java.util.Set.of(), 0, 0, true);
            inventories.sync(p);
            expect(countChests(p) == 2, "both chests are in the reward world inventory");
            int slot = -1;
            for (int i = 0; i < 36; i++) {
                if (dev.safemc.safeplots.reward.Rewards.isChest(p.getInventory().getItem(i))) {
                    slot = i;
                }
            }
            rewards.open(p, p.getInventory().getItem(slot));
            expect(rewards.spinning(p.getUUID()) && countChests(p) == 1 && p.containerMenu != p.inventoryMenu, "right-click: the wheel opens, one chest used");
            java.util.Set<net.minecraft.world.item.Item> shown = new java.util.HashSet<>();
            for (int i = 9; i < 18; i++) {
                shown.add(p.containerMenu.getSlot(i).getItem().getItem());
            }
            expect(shown.size() == 9, "the wheel shows each prize once, no repeats: " + shown.size() + " different of 9");
            for (int i = 0; i < 40 && rewards.spinning(p.getUUID()); i++) {
                rewards.tickSpins();
            }
            expect(rewards.spinning(p.getUUID()), "still spinning after 2 seconds");
            for (int i = 0; i < 200 && rewards.spinning(p.getUUID()); i++) {
                rewards.tickSpins();
            }
            expect(!rewards.spinning(p.getUUID()) && countPrizes(p) == 1, "the wheel stops and the prize is in the inventory");
            p.closeContainer();
            rewards.open(p, p.getInventory().getItem(slot));
            p.closeContainer();
            expect(!rewards.spinning(p.getUUID()) && countPrizes(p) >= 1 && countChests(p) == 0, "closing the wheel early still gives the prize");
            int before = countPrizes(p);

            for (int i = 0; i < 36; i++) {
                p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
            }
            rewards.give(p, 1);
            expect(rewards.pending(p.getUUID()) == 1 && countChests(p) == 0, "inventory full: the chest waits");
            p.getInventory().clearContent();
            rewards.deliverPending();
            expect(rewards.pending(p.getUUID()) == 0 && countChests(p) == 1, "with room again, it is given");
            expect(before >= 1, "prizes counted");

            java.nio.file.Files.writeString(cfg, "world: st_rew\n5 not_an_item 3\n");
            String problem = rewards.reload();
            expect(problem != null && problem.contains("not_an_item") && rewards.prizes().size() == 12, "a typo in the file is reported and the old wheel stays: " + problem);
            p.getInventory().clearContent();
            rewards.forget(p.getUUID());
            server.getPlayerList().remove(p);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                java.nio.file.Files.writeString(cfg, original);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            rewards.reload();
        }
    }

    private static void hunger(MinecraftServer server, ServerLevel level, PlotManager pm) {
        var worlds = dev.safemc.safeplots.world.Worlds.get();
        if (worlds.world("st_void") == null) {
            console(server, "mv create st_void void");
        }
        ServerLevel world = worlds.level(worlds.world("st_void"));
        ServerPlayer p = fakePlayer(server, "Hungry");
        p.teleportTo(world, 0.5, 80, 0.5, java.util.Set.of(), 0, 0, true);
        p.getFoodData().setFoodLevel(4);
        expect(worlds.hunger(world), "hunger is on by default");
        console(server, "mv hunger st_void off");
        expect(!worlds.hunger(world) && p.getFoodData().getFoodLevel() == 20, "/mv hunger off: the food bar is full right away");
        expect(worlds.hunger(level), "other worlds keep normal hunger");
        console(server, "mv hunger st_void on");
        p.getFoodData().setFoodLevel(4);
        expect(worlds.hunger(world) && p.getFoodData().getFoodLevel() == 4, "/mv hunger on: normal hunger again");
        server.getPlayerList().remove(p);
    }

    private static void invsee(MinecraftServer server, ServerLevel level, PlotManager pm) {
        ServerPlayer staff = fakePlayer(server, "Staff");
        ServerPlayer target = fakePlayer(server, "Holder");
        var head = net.minecraft.world.entity.EquipmentSlot.HEAD;
        var pickup = net.minecraft.world.inventory.ContainerInput.PICKUP;
        var shift = net.minecraft.world.inventory.ContainerInput.QUICK_MOVE;
        try {
            staff.getInventory().clearContent();
            target.getInventory().clearContent();
            target.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
            target.getInventory().setItem(20, new ItemStack(Items.APPLE, 3));
            target.setItemSlot(head, new ItemStack(Items.IRON_HELMET));
            dev.safemc.safeplots.moderation.InvSee.open(staff, target);
            var menu = staff.containerMenu;
            expect(menu instanceof dev.safemc.safeplots.moderation.InvSee.Menu, "the window opens");
            expect(menu.getSlot(27).getItem().is(Items.DIAMOND) && menu.getSlot(11).getItem().is(Items.APPLE)
                    && menu.getSlot(36).getItem().is(Items.IRON_HELMET) && menu.getSlot(44).getItem().is(Items.STAINED_GLASS_PANE.pick(net.minecraft.world.item.DyeColor.GRAY)),
                    "it shows the hotbar (row 4), the inventory (rows 1-3) and the helmet (row 5)");
            menu.clicked(27, 0, pickup, staff);
            expect(menu.getCarried().is(Items.DIAMOND) && target.getInventory().getItem(0).isEmpty(), "clicking the diamonds takes them from the player");
            menu.clicked(45 + 27, 0, pickup, staff); // first slot of the staff's hotbar
            expect(staff.getInventory().getItem(0).is(Items.DIAMOND) && staff.getInventory().getItem(0).getCount() == 5, "and puts them in the staff's inventory");
            menu.clicked(11, 0, shift, staff);
            expect(target.getInventory().getItem(20).isEmpty() && staff.getInventory().countItem(Items.APPLE) == 3, "shift-click takes the apples");
            menu.clicked(45 + 27, 0, shift, staff);
            expect(target.getInventory().countItem(Items.DIAMOND) == 5 && staff.getInventory().countItem(Items.DIAMOND) == 0, "shift-click gives the diamonds back");
            menu.clicked(44, 0, pickup, staff);
            expect(menu.getCarried().isEmpty() && staff.getInventory().countItem(Items.STAINED_GLASS_PANE.pick(net.minecraft.world.item.DyeColor.GRAY)) == 0, "the gray panes can't be taken");
            menu.clicked(36, 0, pickup, staff);
            expect(menu.getCarried().is(Items.IRON_HELMET) && target.getItemBySlot(head).isEmpty(), "the helmet can be taken off");
            menu.clicked(36, 0, pickup, staff);
            expect(target.getItemBySlot(head).is(Items.IRON_HELMET), "and put back");
            target.getInventory().setItem(8, new ItemStack(Items.EMERALD));
            expect(menu.getSlot(35).getItem().is(Items.EMERALD), "it's live: a new item in the player's hotbar shows up");
            server.getPlayerList().remove(target);
            expect(!menu.stillValid(staff), "the window closes when the player logs out");
        } finally {
            staff.closeContainer();
            server.getPlayerList().remove(staff);
            if (!target.hasDisconnected()) {
                server.getPlayerList().remove(target);
            }
        }
    }

    private static void afk(MinecraftServer server, ServerLevel level, PlotManager pm) {
        var afk = dev.safemc.safeplots.moderation.Afk.get();
        int oldTimeout = afk.timeoutMinutes();
        ServerPlayer p = fakePlayer(server, "Sleepy");
        ServerPlayer other = fakePlayer(server, "Awake");
        try {
            p.teleportTo(level, 7000.5, 120, 7000.5, java.util.Set.of(), 0, 0, true);
            afk.tick();
            output(p, "afk");
            expect(afk.isAfk(p.getUUID()), "/afk: AFK");
            Component tab = p.getTabListDisplayName();
            expect(tab != null && tab.getString().equals("[AFK] Sleepy"), "the tab list shows [AFK] Sleepy: " + (tab == null ? null : tab.getString()));
            p.teleportTo(level, 7001.5, 120, 7000.5, java.util.Set.of(), 0, 0, true);
            afk.tick();
            expect(afk.isAfk(p.getUUID()), "moving in the first 3 seconds after /afk doesn't end it");
            afk.endGrace(p);
            p.teleportTo(level, 7003.5, 120, 7000.5, java.util.Set.of(), 0, 0, true);
            afk.tick();
            Component back = p.getTabListDisplayName();
            expect(!afk.isAfk(p.getUUID()) && (back == null || !back.getString().contains("AFK")), "moving after that: no longer AFK");
            output(p, "afk");
            output(p, "afk");
            expect(!afk.isAfk(p.getUUID()), "/afk again: back");
            output(p, "afk");
            server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withSuppressedOutput(), "help"); // like a real player
            expect(!afk.isAfk(p.getUUID()), "using a command ends it right away");

            console(server, "afk timeout 5");
            afk.pretendIdle(p, 4);
            afk.tick();
            expect(!afk.isAfk(p.getUUID()), "4 minutes doing nothing: not AFK yet");
            afk.pretendIdle(p, 5);
            afk.tick();
            expect(afk.isAfk(p.getUUID()), "5 minutes doing nothing: AFK by itself");
            afk.endGrace(p);
            p.setYRot(90);
            afk.tick();
            expect(!afk.isAfk(p.getUUID()), "looking around ends it");

            other.addTag(dev.safemc.safeplots.moderation.Afk.IN_GAME);
            afk.pretendIdle(other, 30);
            afk.tick();
            expect(!afk.isAfk(other.getUUID()), "in a minigame: never AFK by itself");
            other.removeTag(dev.safemc.safeplots.moderation.Afk.IN_GAME);
            console(server, "afk timeout 0");
            afk.pretendIdle(other, 30);
            afk.tick();
            expect(!afk.isAfk(other.getUUID()) && afk.timeoutMinutes() == 0, "/afk timeout 0: only with /afk");
        } finally {
            console(server, "afk timeout " + oldTimeout);
            server.getPlayerList().remove(p);
            server.getPlayerList().remove(other);
        }
    }

    private static void damage(MinecraftServer server, ServerLevel level, PlotManager pm) {
        var worlds = dev.safemc.safeplots.world.Worlds.get();
        if (worlds.world("st_void") == null) {
            console(server, "mv create st_void void");
        }
        ServerLevel world = worlds.level(worlds.world("st_void"));
        ServerPlayer p = fakePlayer(server, "Safe");
        try {
            p.teleportTo(world, 40.5, 80, 40.5, java.util.Set.of(), 0, 0, true);
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            p.connection.markClientLoaded();
            p.hasChangedDimension(); // a real client says it arrived; until then players can't be hurt
            expect(worlds.damage(world), "damage is on by default");
            p.hurtServer(world, world.damageSources().generic(), 5);
            expect(p.getHealth() < p.getMaxHealth(), "damage on: it hurts");
            p.setHealth(p.getMaxHealth());
            console(server, "mv damage st_void off");
            p.hurtServer(world, world.damageSources().generic(), 5);
            p.hurtServer(world, world.damageSources().fall(), 8);
            expect(!worlds.damage(world) && p.getHealth() == p.getMaxHealth(), "/mv damage off: no damage");
            p.teleportTo(world, 40.5, -100, 40.5, java.util.Set.of(), 0, 0, true);
            p.hurtServer(world, world.damageSources().fellOutOfWorld(), 4);
            expect(p.getHealth() == p.getMaxHealth() && p.getY() > 0 && p.level() == world, "falling into the void: back to the spawn, unhurt (y " + (int) p.getY() + ")");
            expect(worlds.damage(level), "other worlds keep damage");
            console(server, "mv damage st_void on");
            // no ticks passed since the first hit: only a bigger one counts during the hurt cooldown
            p.hurtServer(world, world.damageSources().generic(), 8);
            expect(worlds.damage(world) && p.getHealth() < p.getMaxHealth(), "/mv damage on: it hurts again");
        } finally {
            worlds.setDamage("st_void", true);
            server.getPlayerList().remove(p);
        }
    }

    private static void endPortal(MinecraftServer server, ServerLevel level, PlotManager pm) {
        var worlds = dev.safemc.safeplots.world.Worlds.get();
        BlockPos frame = new BlockPos(5000, 120, 5000), portal = frame.east(2);
        level.setBlock(frame, Blocks.END_PORTAL_FRAME.defaultBlockState(), 3);
        level.setBlock(portal, Blocks.END_PORTAL.defaultBlockState(), 3);
        ServerPlayer p = fakePlayer(server, "Ender");
        try {
            p.teleportTo(level, frame.getX() + 0.5, frame.getY() + 1, frame.getZ() + 0.5, java.util.Set.of(), 0, 0, true);
            console(server, "mv endportal off");
            click(p, frame, new ItemStack(Items.ENDER_EYE));
            expect(!worlds.endPortal() && !level.getBlockState(frame).getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE),
                    "/mv endportal off: an eye of ender doesn't go in the frame");
            p.teleportTo(level, portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5, java.util.Set.of(), 0, 0, true);
            expect(net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent(p, net.minecraft.world.level.Level.END)).isCanceled(),
                    "a lit end portal doesn't take you to the End");
            p.teleportTo(level, frame.getX() + 0.5, frame.getY() + 1, frame.getZ() + 0.5, java.util.Set.of(), 0, 0, true);
            expect(!net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent(p, net.minecraft.world.level.Level.END)).isCanceled(),
                    "going to the End not through a portal (/mvtp end) still works");
            console(server, "mv endportal on");
            click(p, frame, new ItemStack(Items.ENDER_EYE));
            expect(worlds.endPortal() && level.getBlockState(frame).getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE),
                    "/mv endportal on: the eye goes in again");
        } finally {
            worlds.setEndPortal(true);
            server.getPlayerList().remove(p);
            level.setBlock(frame, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(portal, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static void hubBoard(MinecraftServer server, ServerLevel level, PlotManager pm) throws Exception {
        var worlds = dev.safemc.safeplots.world.Worlds.get();
        if (worlds.world("st_void") == null) {
            console(server, "mv create st_void void");
        }
        java.nio.file.Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        java.nio.file.Path bw = root.resolve("safebedwars-wins.json"), tr = root.resolve("safetntrun-wins.json");
        if (java.nio.file.Files.exists(bw) || java.nio.file.Files.exists(tr)) {
            expect(false, "needs a world without minigame win files");
            return;
        }
        ServerPlayer p = fakePlayer(server, "Lobby");
        try {
            p.teleportTo(worlds.level(worlds.world("st_void")), 0.5, 80, 0.5, java.util.Set.of(), 0, 0, true);
            console(server, "mv topwinners st_void on");
            expect(!dev.safemc.safeplots.world.HubBoard.showing(p), "no minigame installed: no sidebar");
            console(server, "mv topwinners st_void off");
            java.nio.file.Files.writeString(bw, "{\"players\":{\"" + java.util.UUID.randomUUID() + "\":{\"name\":\"Alice\",\"wins\":3},\""
                    + java.util.UUID.randomUUID() + "\":{\"name\":\"Bob\",\"wins\":5}},\"boards\":{}}");
            java.nio.file.Files.writeString(tr, "{\"players\":{},\"boards\":{}}");
            dev.safemc.safeplots.world.HubBoard.update(server);
            expect(!dev.safemc.safeplots.world.HubBoard.showing(p), "off by default");
            console(server, "mv topwinners st_void on");
            String text = dev.safemc.safeplots.world.HubBoard.lines().stream().map(Component::getString).reduce("", (a, b) -> a + "|" + b);
            expect(dev.safemc.safeplots.world.HubBoard.showing(p), "/mv topwinners on: the sidebar shows there");
            expect(text.contains("BedWars: Bob (5)"), "BedWars shows its #1 with their wins: " + text);
            expect(text.contains("TNT Run: -"), "a game with no wins yet says so");
            expect(!text.contains("Guess the Build"), "a minigame that isn't installed isn't listed");
            p.teleportTo(level, 0.5, 100, 0.5, java.util.Set.of(), 0, 0, true);
            dev.safemc.safeplots.world.HubBoard.update(server);
            expect(!dev.safemc.safeplots.world.HubBoard.showing(p), "leaving that world removes it");
            p.teleportTo(worlds.level(worlds.world("st_void")), 0.5, 80, 0.5, java.util.Set.of(), 0, 0, true);
            dev.safemc.safeplots.world.HubBoard.update(server);
            console(server, "mv topwinners st_void off");
            expect(!dev.safemc.safeplots.world.HubBoard.showing(p) && !worlds.topWinners("st_void"), "/mv topwinners off removes it");
        } finally {
            server.getPlayerList().remove(p);
            java.nio.file.Files.deleteIfExists(bw);
            java.nio.file.Files.deleteIfExists(tr);
            worlds.setTopWinners("st_void", false);
            dev.safemc.safeplots.world.HubBoard.update(server);
        }
    }

    private static void elevator(MinecraftServer server, ServerLevel level, PlotManager pm) {
        var worlds = dev.safemc.safeplots.world.Worlds.get();
        if (worlds.world("st_void") == null) {
            console(server, "mv create st_void void");
        }
        String oldHub = worlds.hub();
        worlds.setHub("st_void");
        try {
            ServerLevel hub = worlds.level(worlds.world("st_void"));
            BlockPos low = new BlockPos(300, 80, 300), high = low.above(10), blocked = low.above(20);
            for (BlockPos p : BlockPos.betweenClosed(300, 79, 300, 300, 105, 300)) {
                hub.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
            hub.setBlock(low, Blocks.COAL_BLOCK.defaultBlockState(), 2);
            hub.setBlock(high, Blocks.COAL_BLOCK.defaultBlockState(), 2);
            hub.setBlock(blocked, Blocks.COAL_BLOCK.defaultBlockState(), 2);
            hub.setBlock(blocked.above(), Blocks.STONE.defaultBlockState(), 2); // no room to stand
            ServerPlayer p = fakePlayer(server, "Rider");
            p.teleportTo(hub, 300.5, 81, 300.5, java.util.Set.of(), 0, 0, true);
            p.setOnGround(true);
            expect(!dev.safemc.safeplots.world.Elevators.ride(p, true), "off by default, even in the hub");
            console(server, "mv elevators st_void on");
            dev.safemc.safeplots.world.Elevators.resetCooldown(p);
            p.setOnGround(true);
            expect(dev.safemc.safeplots.world.Elevators.ride(p, true) && p.blockPosition().equals(high.above()), "/mv elevators on: jumping goes up to the next coal block");
            p.setOnGround(true);
            expect(!dev.safemc.safeplots.world.Elevators.ride(p, true), "no second ride right away (cooldown)");
            p.teleportTo(hub, 300.5, 91, 300.5, java.util.Set.of(), 0, 0, true);
            p.setOnGround(true);
            dev.safemc.safeplots.world.Elevators.resetCooldown(p);
            expect(!dev.safemc.safeplots.world.Elevators.ride(p, true), "a coal block without room above isn't used");
            dev.safemc.safeplots.world.Elevators.resetCooldown(p);
            p.setOnGround(true);
            expect(dev.safemc.safeplots.world.Elevators.ride(p, false) && p.blockPosition().equals(low.above()), "sneaking goes down");
            hub.setBlock(low.above(), Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState(), 2);
            hub.setBlock(high.above(), Blocks.MOSS_CARPET.defaultBlockState(), 2);
            p.teleportTo(hub, 300.5, 81.0625, 300.5, java.util.Set.of(), 0, 0, true);
            p.setOnGround(true);
            dev.safemc.safeplots.world.Elevators.resetCooldown(p);
            expect(dev.safemc.safeplots.world.Elevators.ride(p, true) && p.blockPosition().equals(high.above()) && p.getY() > high.getY() + 1.05,
                    "carpets on the coal blocks: still goes up, onto the carpet");
            p.setOnGround(true);
            dev.safemc.safeplots.world.Elevators.resetCooldown(p);
            expect(dev.safemc.safeplots.world.Elevators.ride(p, false) && p.blockPosition().equals(low.above()), "and down from a carpet");
            hub.setBlock(low.above(), Blocks.AIR.defaultBlockState(), 2);
            hub.setBlock(high.above(), Blocks.AIR.defaultBlockState(), 2);
            p.teleportTo(hub, 300.5, 81, 300.5, java.util.Set.of(), 0, 0, true);
            worlds.setHub(oldHub);
            String dim = PlotManager.dimensionId(hub);
            if (pm.plot("t_elev") != null) {
                pm.delete(pm.plot("t_elev"));
            }
            ServerPlayer admin = fakePlayer(server, "ElevAdmin");
            server.getPlayerList().op(admin.nameAndId());
            pm.setCorner(admin.getUUID(), 1, dim, high.offset(-2, 0, -2));
            pm.setCorner(admin.getUUID(), 2, dim, high.offset(2, 5, 2));
            run(admin, "plot create t_elev full");
            p.teleportTo(hub, 300.5, 81, 300.5, java.util.Set.of(), 0, 0, true);
            dev.safemc.safeplots.world.Elevators.resetCooldown(p);
            p.setOnGround(true);
            expect(pm.plot("t_elev") != null && !dev.safemc.safeplots.world.Elevators.ride(p, true)
                    && p.blockPosition().equals(low.above()), "no elevator into someone else's plot");
            pm.delete(pm.plot("t_elev"));
            console(server, "mv elevators st_void off");
            dev.safemc.safeplots.world.Elevators.resetCooldown(p);
            p.setOnGround(true);
            expect(!dev.safemc.safeplots.world.Elevators.ride(p, true), "/mv elevators off: coal is just coal");
        } finally {
            worlds.setHub(oldHub);
        }
    }

    private static void parkour(MinecraftServer server, ServerLevel level, PlotManager pm) {
        var parkours = dev.safemc.safeplots.parkour.Parkours.get();
        var worlds = dev.safemc.safeplots.world.Worlds.get();
        if (worlds.world("st_void") == null) {
            console(server, "mv create st_void void");
        }
        if (parkours.course("t_pk") != null) {
            parkours.delete(parkours.course("t_pk"));
        }
        ServerPlayer admin = fakePlayer(server, "ParkourAdmin");
        server.getPlayerList().op(admin.nameAndId());
        admin.teleportTo(level, 8000.5, Y, 8000.5, java.util.Set.of(), 0, 0, true);
        run(admin, "parkour create t_pk");
        admin.teleportTo(8005.5, Y, 8000.5);
        run(admin, "parkour addcheckpoint t_pk");
        admin.teleportTo(8010.5, Y, 8000.5);
        run(admin, "parkour setfinish t_pk");
        run(admin, "parkour reward t_pk st_void");
        var course = parkours.course("t_pk");
        expect(course != null && course.finish != null && course.checkpoints.size() == 1 && "st_void".equals(course.rewardWorld)
                && course.diamonds == 3, "course with start, 1 checkpoint, finish and a 3 diamond reward");

        ServerPlayer p = fakePlayer(server, "Runner");
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        p.getAbilities().flying = false;
        p.teleportTo(level, 8000.5, Y, 8000.5, java.util.Set.of(), 0, 0, true);
        p.getInventory().setItem(8, new ItemStack(Items.STONE));
        parkours.check(p, false);
        expect(parkours.running(p), "stepping on the start starts a run");
        expect(dev.safemc.safeplots.parkour.LeaveItem.is(p.getInventory().getItem(8)) && p.getInventory().getItem(8).is(Items.BARRIER)
                && p.getInventory().countItem(Items.STONE) == 1, "a run puts the leave barrier in the last slot (the stone moves)");
        p.getInventory().clearContent();
        p.teleportTo(8004.5, Y, 8000.5);
        parkours.check(p, false);
        p.teleportTo(8010.5, Y, 8000.5);
        parkours.check(p, false);
        expect(parkours.running(p), "the finish doesn't count without the checkpoint");
        p.teleportTo(8005.5, Y, 8000.5);
        parkours.check(p, false);
        p.teleportTo(8010.5, Y, 8000.5);
        parkours.check(p, false);
        expect(!parkours.running(p), "checkpoint then finish ends the run");
        expect(!dev.safemc.safeplots.parkour.LeaveItem.is(p.getInventory().getItem(8)), "the barrier is gone after the run");
        expect(parkours.cooldownLeft(p.getUUID(), course) > 23 * 3_600_000L, "the reward has a 24 hour cooldown");

        // The diamonds wait in the st_void inventory.
        int here = p.getInventory().countItem(Items.DIAMOND);
        ServerLevel voidLevel = worlds.level(worlds.world("st_void"));
        p.teleportTo(voidLevel, 0.5, 70, 0.5, java.util.Set.of(), 0, 0, true);
        dev.safemc.safeplots.inventory.WorldInventories.get().sync(p);
        expect(here == 0 && p.getInventory().countItem(Items.DIAMOND) == 3, "3 diamonds in the other world's inventory");
        p.getInventory().clearContent();
        p.teleportTo(level, 8000.5, Y, 8000.5, java.util.Set.of(), 0, 0, true);
        dev.safemc.safeplots.inventory.WorldInventories.get().sync(p);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        parkours.check(p, false);
        p.teleportTo(8005.5, Y, 8000.5);
        parkours.check(p, false);
        p.teleportTo(8010.5, Y, 8000.5);
        parkours.check(p, false);
        p.teleportTo(voidLevel, 0.5, 70, 0.5, java.util.Set.of(), 0, 0, true);
        dev.safemc.safeplots.inventory.WorldInventories.get().sync(p);
        expect(p.getInventory().countItem(Items.DIAMOND) == 0, "no second reward within 24 hours");
        p.teleportTo(level, 8000.5, Y, 8000.5, java.util.Set.of(), 0, 0, true);
        dev.safemc.safeplots.inventory.WorldInventories.get().sync(p);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        parkours.check(p, false);
        p.teleportTo(8003.5, Y, 8000.5);
        parkours.check(p, false);
        p.teleportTo(8040.5, Y, 8000.5);
        parkours.check(p, false);
        expect(!parkours.running(p), "teleporting stops the run");
        p.teleportTo(8000.5, Y, 8000.5);
        parkours.check(p, false);
        p.teleportTo(8001.5, Y, 8000.5);
        p.getAbilities().flying = true;
        parkours.check(p, false);
        p.getAbilities().flying = false;
        expect(!parkours.running(p), "flying stops the run");
        p.teleportTo(8000.5, Y, 8000.5);
        parkours.check(p, false);
        p.teleportTo(8001.5, Y, 8000.5);
        parkours.check(p, false);
        expect(parkours.backToCheckpoint(p) && p.blockPosition().getX() == 8000, "/parkour checkpoint goes back to the start");
        parkours.check(p, false);
        p.teleportTo(8005.5, Y, 8000.5);
        parkours.check(p, false);
        p.teleportTo(8007.5, Y - 3, 8000.5);
        parkours.check(p, false);
        expect(parkours.running(p) && p.blockPosition().getX() == 8005 && p.blockPosition().getY() == (int) Math.floor(Y),
                "falling below the course goes back to the last checkpoint");
        p.teleportTo(8000.5, Y, 8000.5);
        parkours.check(p, false);
        p.teleportTo(8001.5, Y, 8000.5);
        parkours.check(p, false);
        expect(parkours.running(p) && parkours.checkpointsReached(p) == 1,
                "back on the start mid-run: same run, the checkpoint (and the time) is kept");
        p.teleportTo(8000.5, Y, 8000.5);
        parkours.check(p, false);
        dev.safemc.safeplots.parkour.LeaveItem.leave(p);
        expect(!parkours.running(p) && p.getInventory().countItem(Items.BARRIER) == 0, "right-clicking the barrier leaves the parkour");
        parkours.check(p, false);
        expect(!parkours.running(p) && !p.blockPosition().equals(new BlockPos(8000, Y, 8000)),
                "leaving on the start moves you off it, so no new run: " + p.blockPosition());
        parkours.delete(course);
    }

    private static void guide(MinecraftServer server, ServerLevel level, PlotManager pm) {
        var worlds = dev.safemc.safeplots.world.Worlds.get();
        if (worlds.world("st_void") == null) {
            console(server, "mv create st_void void");
        }
        String oldHub = worlds.hub();
        worlds.setHub("st_void");
        try {
            ServerLevel hub = worlds.level(worlds.world("st_void"));
            var content = dev.safemc.safeplots.world.Guide.book().get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
            expect(content != null && content.pages().size() == 5 && content.title().raw().equals("Papatya's Server"),
                    "the book has 5 pages and its title");
            var bookName = dev.safemc.safeplots.world.Guide.book().get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
            expect(bookName != null && bookName.getString().equals("Guide Papatya's Server"), "the item is named Guide Papatya's Server");
            ServerPlayer p = fakePlayer(server, "GuideReader");
            p.teleportTo(hub, 0.5, 70, 0.5, java.util.Set.of(), 0, 0, true);
            var inv = p.getInventory();
            inv.clearContent();
            inv.setItem(0, new ItemStack(Items.DIAMOND, 3));
            dev.safemc.safeplots.world.Guide.keep(p);
            expect(dev.safemc.safeplots.world.Guide.isGuide(inv.getItem(0)), "the guide is in the first slot");
            expect(p.getInventory().countItem(Items.DIAMOND) == 3, "what was there moved to another slot");
            inv.setItem(5, dev.safemc.safeplots.world.Guide.book());
            dev.safemc.safeplots.world.Guide.keep(p);
            int guides = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                guides += dev.safemc.safeplots.world.Guide.isGuide(inv.getItem(i)) ? 1 : 0;
            }
            expect(guides == 1, "extra copies are removed");
            var dropped = new net.minecraft.world.entity.item.ItemEntity(hub, 0.5, 70, 0.5, dev.safemc.safeplots.world.Guide.book());
            hub.addFreshEntity(dropped);
            expect(hub.getEntity(dropped.getUUID()) == null, "a dropped guide disappears");
            inv.clearContent();
            p.teleportTo(level, 0.5, Y, 0.5, java.util.Set.of(), 0, 0, true);
            run(p, "guide");
            expect(dev.safemc.safeplots.world.Guide.isGuide(inv.getItem(0)) || p.getInventory().countItem(Items.WRITTEN_BOOK) == 1, "/guide gives one outside the hub");
            inv.clearContent();
        } finally {
            worlds.setHub(oldHub);
        }
    }

    private static void holo(MinecraftServer server, ServerLevel level, PlotManager pm) {
        var holos = dev.safemc.safeplots.holo.Holograms.get();
        if (holos.holo("t_holo") != null) {
            holos.delete(holos.holo("t_holo"));
        }
        level.setChunkForced(7000 >> 4, 7000 >> 4, true);
        level.getChunk(7000 >> 4, 7000 >> 4);
        if (!level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(7000 >> 4, 7000 >> 4))) {
            results.add("info the test spot is still loading; run /selftest holo again in a few seconds");
            return;
        }
        ServerPlayer admin = fakePlayer(server, "HoloAdmin");
        server.getPlayerList().op(admin.nameAndId());
        admin.teleportTo(7000.5, Y, 7000.5);
        run(admin, "holo create t_holo &6&lWelcome\\n&bline two");
        var holo = holos.holo("t_holo");
        expect(holo != null && holo.lines.size() == 2, "created with 2 lines");
        if (holo == null) {
            return;
        }
        expect(level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(holo.blockPos())), "entities loaded at the spot");
        var shown = level.getEntity(holo.entity);
        expect(shown instanceof net.minecraft.world.entity.Display.TextDisplay d
                && d.getText().getString().equals("Welcome\nline two"), "a text display shows both lines");
        var first = dev.safemc.safeplots.holo.Holograms.colored("&6&lWelcome").getSiblings().getFirst().getStyle();
        expect(first.isBold() && first.getColor() != null && first.getColor().getValue() == 0xFFAA00, "&6&l is gold and bold");
        var hex = dev.safemc.safeplots.holo.Holograms.colored("&#FF0000x").getSiblings().getFirst().getStyle();
        expect(hex.getColor() != null && hex.getColor().getValue() == 0xFF0000, "&#FF0000 is red");
        run(admin, "holo setline t_holo 2 &cchanged");
        shown = level.getEntity(holo.entity);
        expect(shown instanceof net.minecraft.world.entity.Display.TextDisplay d
                && d.getText().getString().equals("Welcome\nchanged"), "setline changes the display");
        run(admin, "holo background t_holo on");
        expect(shown instanceof net.minecraft.world.entity.Display.TextDisplay d && d.getBackgroundColor() != 0, "background on");
        java.util.UUID old = holo.entity;
        shown.discard();
        holos.ensureAll();
        expect(holo.entity != null && !holo.entity.equals(old) && level.getEntity(holo.entity) != null, "a killed hologram comes back");
        var copy = new net.minecraft.world.entity.Display.TextDisplay(net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY, level);
        copy.setPos(7001, Y, 7001);
        copy.addTag(dev.safemc.safeplots.holo.Holograms.TAG);
        level.addFreshEntity(copy);
        expect(level.getEntity(copy.getUUID()) == null, "a stray copy is removed");
        double y = holo.y;
        old = holo.entity;
        run(admin, "holo height t_holo 2");
        var moved = level.getEntity(holo.entity);
        expect(level.getEntity(old) == null && moved != null && Math.abs(moved.getY() - (y + 2)) < 0.01, "height moves it up 2 blocks");
        run(admin, "holo delete t_holo");
        expect(holos.holo("t_holo") == null && level.getEntity(moved.getUUID()) == null, "delete removes it");
    }

    private static void crystals(MinecraftServer server, ServerLevel level, PlotManager pm) {
        expect(!crystalBlast(level, new BlockPos(6000, Y, 6000)), "an end crystal doesn't explode by default");
        console(server, "mv crystals world on");
        expect(crystalBlast(level, new BlockPos(6100, Y, 6000)), "with /mv crystals world on it explodes");
        console(server, "mv crystals world off");

        // In a creative world, /plot create is full height unless a height is given.
        var worlds = dev.safemc.safeplots.world.Worlds.get();
        if (worlds.world("st_void") == null) {
            console(server, "mv create st_void void");
        }
        ServerLevel creative = worlds.level(worlds.world("st_void"));
        String dim = PlotManager.dimensionId(creative);
        ServerPlayer admin = fakePlayer(server, "CreativeAdmin");
        server.getPlayerList().op(admin.nameAndId());
        for (String n : List.of("t_cr_full", "t_cr_height", "t_sv_exact")) {
            if (pm.plot(n) != null) {
                pm.delete(pm.plot(n));
            }
        }
        console(server, "mv gamemode st_void creative");
        pm.setCorner(admin.getUUID(), 1, dim, new BlockPos(100, 10, 100));
        pm.setCorner(admin.getUUID(), 2, dim, new BlockPos(110, 20, 110));
        run(admin, "plot create t_cr_full");
        Plot full = pm.plot("t_cr_full");
        expect(full != null && full.min().getY() == creative.getMinY() && full.max().getY() == creative.getMaxY(),
                "creative world: /plot create covers the whole height");
        pm.setCorner(admin.getUUID(), 1, dim, new BlockPos(200, 10, 200));
        pm.setCorner(admin.getUUID(), 2, dim, new BlockPos(210, 20, 210));
        run(admin, "plot create t_cr_height height 0 50");
        Plot chosen = pm.plot("t_cr_height");
        expect(chosen != null && chosen.min().getY() == 0 && chosen.max().getY() == 50, "creative world: height ... still picks the heights");
        console(server, "mv gamemode st_void none");
        pm.setCorner(admin.getUUID(), 1, dim, new BlockPos(300, 10, 300));
        pm.setCorner(admin.getUUID(), 2, dim, new BlockPos(310, 20, 310));
        run(admin, "plot create t_sv_exact");
        Plot exact = pm.plot("t_sv_exact");
        expect(exact != null && exact.min().getY() == 10 && exact.max().getY() == 20, "other worlds: exact corners as before");
        for (Plot plot : new Plot[] {full, chosen, exact}) {
            if (plot != null) {
                pm.delete(plot);
            }
        }
    }

    /** Hits a crystal on a stone block; true if the stone was blown up. */
    private static boolean crystalBlast(ServerLevel level, BlockPos at) {
        level.getChunk(at);
        level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
        var crystal = new net.minecraft.world.entity.boss.enderdragon.EndCrystal(level, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
        crystal.setShowBottom(false);
        level.addFreshEntity(crystal);
        crystal.hurtServer(level, level.damageSources().generic(), 1);
        boolean blown = !level.getBlockState(at).is(Blocks.STONE);
        expect(crystal.isRemoved(), "the crystal breaks either way");
        return blown;
    }

    private static void schemBig(MinecraftServer server, ServerLevel level, PlotManager pm) {
        dev.safemc.safeplots.schematic.Schematics manager = dev.safemc.safeplots.schematic.Schematics.get();
        int w = 296, h = 131, l = 304;
        int[] cells = new int[w * h * l];
        for (int i = 0; i < cells.length; i++) {
            int y = i / (w * l);
            cells[i] = y < 20 ? 1 + (i % 2) : 0; // 20 solid layers, then air like a typical build's sky
        }
        var big = new dev.safemc.safeplots.schematic.Schematic("st_huge", w, h, l, BlockPos.ZERO,
                new net.minecraft.world.level.block.state.BlockState[] {Blocks.AIR.defaultBlockState(),
                        Blocks.STONE.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState()},
                cells, java.util.Map.of(), 0, 0);
        ServerPlayer builder = fakePlayer(server, "Builder");
        manager.setClipboard(builder.getUUID(), big);
        BlockPos at = new BlockPos(5000, Y - 1, 5000);
        Runtime rt = Runtime.getRuntime();
        long t0 = System.nanoTime();
        manager.paste(builder, manager.clipboard(builder.getUUID()), at, false, x -> results.add("info changed " + x + " blocks"));
        manager.finishAll();
        long pasteMs = (System.nanoTime() - t0) / 1_000_000;
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576;
        expect(level.getBlockState(at.offset(295, 0, 303)).is(Blocks.STONE) || level.getBlockState(at.offset(295, 0, 303)).is(Blocks.OAK_PLANKS),
                "11.8M-block paste finished");
        t0 = System.nanoTime();
        manager.undo(builder.getUUID(), x -> {});
        manager.finishAll();
        long undoMs = (System.nanoTime() - t0) / 1_000_000;
        expect(level.getBlockState(at.offset(295, 0, 303)).is(Blocks.GRASS_BLOCK) || !level.getBlockState(at.offset(295, 0, 303)).is(Blocks.OAK_PLANKS),
                "11.8M-block undo finished");
        results.add("info paste " + pasteMs + " ms, undo " + undoMs + " ms of work, heap in use " + usedMb + " MB of " + rt.maxMemory() / 1_048_576 + " MB");
        server.getPlayerList().remove(builder);
    }

    // ------------------------------------------------------------------ helpers

    private static ServerPlayer fakePlayer(MinecraftServer server, String name) {
        ServerPlayer existing = server.getPlayerList().getPlayerByName(name);
        if (existing != null) {
            return existing;
        }
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, server.overworld(), profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static void click(ServerPlayer player, BlockPos pos, ItemStack held) {
        click(player, pos, held, Direction.UP);
    }

    private static void click(ServerPlayer player, BlockPos pos, ItemStack held, Direction face) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
        player.gameMode.useItemOn(player, player.level(), held, InteractionHand.MAIN_HAND, hit);
    }

    /** Runs a command as the player and returns what it printed. */
    private static String output(ServerPlayer player, String command) {
        StringBuilder out = new StringBuilder();
        net.minecraft.commands.CommandSource capture = new net.minecraft.commands.CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                out.append(message.getString()).append('\n');
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        try {
            player.level().getServer().getCommands().getDispatcher().execute(command, player.createCommandSourceStack().withSource(capture));
        } catch (Exception e) {
            results.add("FAIL command /" + command + ": " + e.getMessage());
        }
        return out.toString();
    }

    private static void run(ServerPlayer player, String command) {
        // Execute synchronously; performPrefixedCommand would queue it until /selftest itself finishes.
        try {
            player.level().getServer().getCommands().getDispatcher().execute(command, player.createCommandSourceStack());
        } catch (Exception e) {
            results.add("FAIL command /" + command + ": " + e.getMessage());
        }
    }

    private static boolean chatBlocked(ServerPlayer player, String text) {
        chatPassed = false;
        NeoForge.EVENT_BUS.post(new ServerChatEvent(player, text, Component.literal(text)));
        return !chatPassed;
    }

    private static boolean commandBlocked(ServerPlayer player, String command) {
        var parse = player.level().getServer().getCommands().getDispatcher().parse(command, player.createCommandSourceStack());
        return NeoForge.EVENT_BUS.post(new CommandEvent(parse)).isCanceled();
    }

    private static void console(MinecraftServer server, String command) {
        try {
            server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
        } catch (Exception e) {
            results.add("FAIL command /" + command + ": " + e.getMessage());
        }
    }

    /** All front lines of a sign joined with spaces (empty lines skipped). */
    private static String signLine(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            return "<no sign>";
        }
        return sign.getText(SignTextSlot.FRONT).getMessages(false).stream()
                .map(Component::getString).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.joining(" "));
    }

    private static int countFluid(ServerLevel level, int x1, int x2, int z1, int z2) {
        int n = 0;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = Y; y <= Y + 3; y++) {
                    n += level.getFluidState(new BlockPos(x, y, z)).isEmpty() ? 0 : 1;
                }
            }
        }
        return n;
    }
}
