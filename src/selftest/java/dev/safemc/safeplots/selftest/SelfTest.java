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

    public SelfTest() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(Commands.literal("selftest")
                .then(Commands.literal("setup").executes(c -> report(c, SelfTest::setup)))
                .then(Commands.literal("check").executes(c -> report(c, SelfTest::check)))
                .then(Commands.literal("persist").executes(c -> report(c, SelfTest::persist)))
                .then(Commands.literal("worlds").executes(c -> report(c, SelfTest::worlds)))
                .then(Commands.literal("schem").executes(c -> report(c, SelfTest::schem)))
                .then(Commands.literal("schembig").executes(c -> report(c, SelfTest::schemBig)))));
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
        neighbor.teleportTo(spawn.getX() + 120.5, Y, spawn.getZ() + 0.5); // square reaches to 95 blocks from spawn
        run(neighbor, "plot claimhere");
        run(neighbor, "plot confirm");
        expect(pm.plotsOwnedBy(neighbor.getUUID()).isEmpty(), "no self-made plots within 100 blocks of spawn");
        String settledName = settled.getFirst().name();
        settler.teleportTo(3000.5, Y, 3000.5);
        run(settler, "plot abandon");
        expect(pm.plot(settledName) == null && pm.plotsOwnedBy(settler.getUUID()).isEmpty(),
                "abandoning a self-made plot deletes it");

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
        portals.add(new dev.safemc.safeplots.world.Portals.Entry("st_gate", "minecraft:overworld", 2000, Y, 2000, 2002, Y + 2, 2000, "st_normal", null));
        console(server, "mvtp world Walker");
        walker.teleportTo(server.overworld(), 2001.5, Y, 2000.5, java.util.Set.of(), 0, 0, true);
        dev.safemc.safeplots.world.Portals.onPlayerTick(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(walker));
        expect(walker.level() == normal, "walking into a portal area sends the player to its world");
        expect(walker.getInventory().countItem(Items.DIRT) == 7, "arriving by portal switches the inventory too");
        portals.add(new dev.safemc.safeplots.world.Portals.Entry("st_np", "minecraft:overworld", 2010, Y, 2010, 2010, Y, 2010, "st_normal", null));
        var transition = ((net.minecraft.world.level.block.Portal) Blocks.NETHER_PORTAL)
                .getPortalDestination(server.overworld(), walker, new BlockPos(2010, Y, 2010));
        expect(transition != null && transition.newLevel() == normal, "a nether portal inside a portal area leads to its world");
        portals.remove("st_gate");
        portals.remove("st_np");

        console(server, "mvtp st_void Walker");
        console(server, "mv delete st_void confirm");
        expect(walker.level() == server.overworld(), "deleting a world sends players in it to spawn");
        expect(worlds.world("st_void") == null && server.getLevel(voidLevel.dimension()) == null, "deleted world is unloaded");
        console(server, "mv delete st_normal confirm");
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

        CompoundTag legacy = new CompoundTag();
        legacy.putShort("Width", (short) 1);
        legacy.putShort("Height", (short) 1);
        legacy.putShort("Length", (short) 1);
        legacy.putString("Materials", "Alpha");
        net.minecraft.nbt.NbtIo.writeCompressed(legacy, dir.resolve("st_legacy.schematic"));

        expect(manager.available().containsAll(List.of("st_house", "old/st_path", "st_legacy")), "/schem list finds files, also in subfolders");
        expect(manager.resolve("../safeplots.json") == null && manager.resolve("st_house") != null, "names can't leave the schematics folder");
        boolean refused = false;
        try {
            dev.safemc.safeplots.schematic.SchematicReader.read(manager.resolve("st_legacy"), "st_legacy", server.registryAccess(), 1000);
        } catch (java.io.IOException e) {
            refused = e.getMessage().contains("MCEdit");
        }
        expect(refused, "old MCEdit .schematic files are refused with an explanation");

        var house = dev.safemc.safeplots.schematic.SchematicReader.read(manager.resolve("st_house"), "st_house", server.registryAccess(), 1000);
        expect(house.width() == 3 && house.height() == 2 && house.length() == 2 && house.blockEntities().size() == 1, "v3 schematic read");
        var path = dev.safemc.safeplots.schematic.SchematicReader.read(manager.resolve("old/st_path"), "st_path", server.registryAccess(), 1000);
        expect(path.palette()[0].is(Blocks.DIRT_PATH), "1.16 grass_path upgraded to dirt_path");

        ServerPlayer builder = fakePlayer(server, "Builder");
        BlockPos at = new BlockPos(3000, Y, 3000);
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

    private static void run(ServerPlayer player, String command) {
        // Execute synchronously; performPrefixedCommand would queue it until /selftest itself finishes.
        try {
            player.level().getServer().getCommands().getDispatcher().execute(command, player.createCommandSourceStack());
        } catch (Exception e) {
            results.add("FAIL command /" + command + ": " + e.getMessage());
        }
    }

    private static boolean chatBlocked(ServerPlayer player, String text) {
        return NeoForge.EVENT_BUS.post(new ServerChatEvent(player, text, Component.literal(text))).isCanceled();
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
