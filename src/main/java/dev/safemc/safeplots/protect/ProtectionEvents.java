package dev.safemc.safeplots.protect;

import dev.safemc.safeplots.plot.ClaimSigns;
import dev.safemc.safeplots.plot.Plot;
import dev.safemc.safeplots.plot.PlotManager;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/**
 * All protection that NeoForge exposes as events. The rule everywhere: inside a plot, only the owner,
 * trusted players and admins with bypass may change anything. Things NeoForge has no event for
 * (liquid flow, hoppers, fire burning, dispensers) are handled by the mixins in {@code dev.safemc.safeplots.mixin}.
 */
public final class ProtectionEvents {
    private ProtectionEvents() {}

    // ---------------------------------------------------------------- players and blocks

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        PlotManager manager = PlotManager.get();
        if (level.isClientSide() || manager == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        BlockPos pos = event.getPos();

        // Claim signs come first: linking (admin with a pending /plot sign) or claiming.
        if (level.getBlockEntity(pos) instanceof SignBlockEntity) {
            String pending = manager.takePendingSignLink(player.getUUID());
            if (pending != null) {
                event.setCanceled(true);
                ClaimSigns.link(manager, player, level, pos, pending);
                return;
            }
            Plot signPlot = manager.plotForSign(level, pos);
            if (signPlot != null) {
                event.setCanceled(true);
                ClaimSigns.click(manager, player, signPlot, event.getItemStack());
                return;
            }
        }

        // An admin in sign-linking mode may place a sign anywhere; onPlace links it. Owners still follow the normal rules.
        if (manager.hasPendingSignLink(player.getUUID()) && PlotManager.isAdmin(player) && isSignItem(event.getItemStack())) {
            return;
        }

        // Using the block itself (chests, doors, buttons, flint and steel on it, bonemeal, ...).
        if (deny(manager, player, level, pos)) {
            event.setCanceled(true);
            return;
        }
        // Anything held could be placed or poured on the clicked face (fire, water, lava, boats, ...).
        Direction face = event.getFace();
        if (!event.getItemStack().isEmpty() && face != null && deny(manager, player, level, pos.relative(face))) {
            event.setCanceled(true);
        }
    }

    /** Buckets don't use the clicked block; they ray-trace themselves, including fluids. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Level level = event.getLevel();
        PlotManager manager = PlotManager.get();
        if (level.isClientSide() || manager == null || !(event.getItemStack().getItem() instanceof BucketItem)) {
            return;
        }
        Player player = event.getEntity();
        if (player.pick(player.blockInteractionRange(), 1.0F, true) instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            if (deny(manager, player, level, pos) || deny(manager, player, level, pos.relative(hit.getDirection()))) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Level level = event.getLevel();
        PlotManager manager = PlotManager.get();
        if (level.isClientSide() || manager == null) {
            return;
        }
        if (deny(manager, event.getEntity(), level, event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BreakBlockEvent event) {
        PlotManager manager = PlotManager.get();
        if (manager == null || !(event.getLevel() instanceof Level level) || level.isClientSide()) {
            return;
        }
        Player player = event.getPlayer();
        if (manager.plotForSign(level, event.getPos()) != null && !manager.hasBypass(player)) {
            event.setCanceled(true);
            resendBlockEntity(level, event.getPos());
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("Only admins with /plot bypass can remove claim signs.").withStyle(ChatFormatting.RED), true);
            }
            return;
        }
        if (deny(manager, player, level, event.getPos())) {
            event.setCanceled(true);
            resendBlockEntity(level, event.getPos());
        }
    }

    /** Covers every block placement by an entity, including both halves of beds and doors. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        PlotManager manager = PlotManager.get();
        if (manager == null || !(event.getLevel() instanceof Level level) || level.isClientSide()) {
            return;
        }
        List<BlockSnapshot> snapshots = event instanceof BlockEvent.EntityMultiPlaceEvent multi
                ? multi.getReplacedBlockSnapshots()
                : List.of(event.getBlockSnapshot());
        Entity entity = event.getEntity();

        // An admin in sign-linking mode who places a sign links it, wherever it is.
        // Owners (/plot movesign) only get here where they're allowed to build; ClaimSigns#link checks the rest.
        if (entity instanceof ServerPlayer player && manager.hasPendingSignLink(player.getUUID())
                && level.getBlockEntity(event.getPos()) instanceof SignBlockEntity
                && (PlotManager.isAdmin(player) || manager.canModify(player, level, event.getPos()))) {
            ClaimSigns.link(manager, player, level, event.getPos(), manager.takePendingSignLink(player.getUUID()));
            return;
        }

        for (BlockSnapshot snapshot : snapshots) {
            BlockPos pos = snapshot.getPos();
            boolean blocked = entity instanceof Player player
                    ? deny(manager, player, level, pos)
                    : entity instanceof Enemy && manager.plotAt(level, pos) != null; // endermen placing blocks
            if (blocked) {
                event.setCanceled(true);
                return;
            }
        }

        // A new sign where a claim sign used to be becomes that claim sign again right away.
        Plot signPlot = manager.plotForSign(level, event.getPos());
        if (signPlot != null) {
            ClaimSigns.refresh(manager, signPlot);
        }
    }

    @SubscribeEvent
    public static void onTrample(BlockEvent.FarmlandTrampleEvent event) {
        PlotManager manager = PlotManager.get();
        if (manager == null || !(event.getLevel() instanceof Level level) || level.isClientSide()) {
            return;
        }
        boolean blocked = event.getEntity() instanceof Player player
                ? !manager.canModify(player, level, event.getPos())
                : manager.plotAt(level, event.getPos()) != null;
        if (blocked) {
            event.setCanceled(true);
        }
    }

    // ---------------------------------------------------------------- entities (frames, armor stands, animals, ...)

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttackEntity(AttackEntityEvent event) {
        PlotManager manager = PlotManager.get();
        Entity target = event.getTarget();
        if (manager == null || target.level().isClientSide() || !isPlotProtectedEntity(target)) {
            return;
        }
        if (deny(manager, event.getEntity(), target.level(), target.blockPosition())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
        PlotManager manager = PlotManager.get();
        Entity target = event.getTarget();
        if (manager == null || event.getLevel().isClientSide() || !isPlotProtectedEntity(target)) {
            return;
        }
        if (deny(manager, event.getEntity(), target.level(), target.blockPosition())) {
            event.setCanceled(true);
        }
    }

    /** Catches indirect player damage too (arrows, tridents, thrown potions): the source entity is the shooter. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLivingDamage(LivingIncomingDamageEvent event) {
        PlotManager manager = PlotManager.get();
        Entity target = event.getEntity();
        if (manager == null || target.level().isClientSide() || !isPlotProtectedEntity(target)) {
            return;
        }
        if (event.getSource().getEntity() instanceof Player attacker
                && deny(manager, attacker, target.level(), target.blockPosition())) {
            event.setCanceled(true);
        }
    }

    /** Projectiles shot by outsiders can't hit item frames/paintings etc. in plots, and don't carry fire in. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        PlotManager manager = PlotManager.get();
        Projectile projectile = event.getProjectile();
        Level level = projectile.level();
        if (manager == null || level.isClientSide() || !(projectile.getOwner() instanceof Player shooter)) {
            return;
        }
        HitResult hit = event.getRayTraceResult();
        if (hit instanceof EntityHitResult entityHit) {
            Entity target = entityHit.getEntity();
            if (isPlotProtectedEntity(target) && deny(manager, shooter, level, target.blockPosition())) {
                event.setCanceled(true);
            }
        } else if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            // Burning arrows would ignite TNT or campfires; put the fire out instead of stopping the arrow.
            if (projectile.isOnFire() && !manager.canModify(shooter, level, blockHit.getBlockPos())) {
                projectile.clearFire();
            }
        }
    }

    /** Anything that isn't a player or a hostile mob: animals, villagers, item frames, armor stands, paintings, minecarts... */
    private static boolean isProtectedEntity(Entity entity) {
        return !(entity instanceof Player) && !(entity instanceof Enemy);
    }

    /** Same, minus tamed pets: players interacting with pets are handled by PetProtection, even inside plots. */
    private static boolean isPlotProtectedEntity(Entity entity) {
        return isProtectedEntity(entity) && PetProtection.ownerOf(entity) == null;
    }

    // ---------------------------------------------------------------- explosions, pistons, mobs, lava

    /** Explosions still happen, but never remove blocks or hurt protected entities inside any plot. */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        PlotManager manager = PlotManager.get();
        Level level = event.getLevel();
        if (manager == null || level.isClientSide()) {
            return;
        }
        event.getAffectedBlocks().removeIf(pos -> manager.plotAt(level, pos) != null || manager.plotForSign(level, pos) != null);
        event.getAffectedEntities().removeIf(e -> isProtectedEntity(e) && manager.plotAt(level, e.blockPosition()) != null);
    }

    /** A piston may not push, pull or break blocks in a plot unless it is in that same plot (or same owner's plot). */
    @SubscribeEvent
    public static void onPiston(PistonEvent.Pre event) {
        PlotManager manager = PlotManager.get();
        if (manager == null || !(event.getLevel() instanceof Level level) || level.isClientSide()) {
            return;
        }
        PistonStructureResolver structure = event.getStructureHelper();
        if (structure == null || !structure.resolve()) {
            return; // the piston won't move anything
        }
        BlockPos piston = event.getPos();
        Direction push = structure.getPushDirection();
        if (!pistonMayAffect(manager, level, piston, event.getFaceOffsetPos())) {
            event.setCanceled(true);
            return;
        }
        for (BlockPos pos : structure.getToPush()) {
            if (!pistonMayAffect(manager, level, piston, pos) || !pistonMayAffect(manager, level, piston, pos.relative(push))) {
                event.setCanceled(true);
                return;
            }
        }
        for (BlockPos pos : structure.getToDestroy()) {
            if (!pistonMayAffect(manager, level, piston, pos)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    private static boolean pistonMayAffect(PlotManager manager, Level level, BlockPos piston, BlockPos pos) {
        return manager.mayAffect(level, piston, pos) && manager.plotForSign(level, pos) == null;
    }

    /** Hostile mobs near a plot can't grief (endermen taking blocks, ravagers, withers, zombies breaking doors). */
    @SubscribeEvent
    public static void onMobGriefing(EntityMobGriefingEvent event) {
        PlotManager manager = PlotManager.get();
        Entity entity = event.getEntity();
        if (manager == null || !(entity instanceof Enemy) || !event.canGrief()) {
            return;
        }
        if (manager.intersectsAnyPlot(entity.level(), entity.getBoundingBox().inflate(4.0))) {
            event.setCanGrief(false);
        }
    }

    /** Withers, the ender dragon and door-breaking zombies. */
    @SubscribeEvent
    public static void onLivingDestroyBlock(LivingDestroyBlockEvent event) {
        PlotManager manager = PlotManager.get();
        if (manager != null && manager.plotAt(event.getEntity().level(), event.getPos()) != null) {
            event.setCanceled(true);
        }
    }

    /** Lava turning blocks into stone or setting them on fire across a plot border. */
    @SubscribeEvent
    public static void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent event) {
        PlotManager manager = PlotManager.get();
        LevelAccessor accessor = event.getLevel();
        if (manager != null && accessor instanceof Level level && !manager.mayAffect(level, event.getLiquidPos(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    // ---------------------------------------------------------------- misc

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        PlotManager manager = PlotManager.get();
        if (manager != null) {
            // Keeps names right after a username change; plots themselves are stored by UUID.
            manager.rememberName(event.getEntity().nameAndId());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Warnings.forget(event.getEntity().getUUID());
    }

    private static boolean isSignItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof SignBlock;
    }

    /**
     * When a break is cancelled the client gets the block back, but not its block entity data,
     * so a sign would show up blank. Queue a full update (sent at the end of the tick).
     */
    private static void resendBlockEntity(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) != null) {
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Returns true (and warns the player) if they may not modify this position. */
    private static boolean deny(PlotManager manager, Player player, Level level, BlockPos pos) {
        if (manager.canModify(player, level, pos)) {
            return false;
        }
        Plot plot = manager.plotAt(level, pos);
        if (plot != null) {
            Warnings.denied(manager, player, plot);
        }
        return true;
    }
}
