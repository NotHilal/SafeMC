package dev.safemc.safeplots.protect;

import dev.safemc.safeplots.plot.PlotManager;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jspecify.annotations.Nullable;

/**
 * Tamed pets (wolves, cats, parrots, horses, donkeys, llamas, camels, ...) are protected everywhere, not just
 * in plots: only their owner, or an admin with bypass, may hurt, ride, leash, feed or open them.
 * Pets are handled here entirely, so an owner can always get their pet back, even from someone else's plot.
 */
public final class PetProtection {
    private PetProtection() {}

    /** The owner's UUID if this is a tamed pet, else null. Works while the owner is offline. */
    public static @Nullable UUID ownerOf(Entity entity) {
        if (entity instanceof OwnableEntity ownable && !(entity instanceof Enemy)) {
            EntityReference<LivingEntity> owner = ownable.getOwnerReference();
            return owner == null ? null : owner.getUUID();
        }
        return null;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (denied(event.getEntity(), event.getTarget(), true)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (denied(event.getEntity(), event.getTarget(), true)) {
            event.setCanceled(true);
        }
    }

    /** Arrows, tridents, potions, TNT someone lit, or another player's pet attacking. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDamage(LivingIncomingDamageEvent event) {
        Entity cause = event.getSource().getEntity();
        if (cause != null && denied(cause, event.getEntity(), cause instanceof Player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onProjectile(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (event.getRayTraceResult() instanceof EntityHitResult hit && projectile.getOwner() != null
                && denied(projectile.getOwner(), hit.getEntity(), false)) {
            event.setCanceled(true);
        }
    }

    /**
     * True if {@code actor} (a player, or a pet acting for its owner) may not touch {@code target}.
     * Mobs and the environment are never blocked here, so pets still take normal damage from them.
     */
    private static boolean denied(Entity actor, Entity target, boolean warn) {
        PlotManager manager = PlotManager.get();
        if (manager == null || target.level().isClientSide()) {
            return false;
        }
        UUID petOwner = ownerOf(target);
        if (petOwner == null) {
            return false;
        }
        UUID responsible = actor instanceof Player ? actor.getUUID() : ownerOf(actor);
        if (responsible == null || responsible.equals(petOwner)) {
            return false;
        }
        if (actor instanceof Player player && manager.hasBypass(player)) {
            return false;
        }
        if (warn && actor instanceof Player player) {
            Warnings.warn(player, "This pet belongs to " + manager.nameOf(petOwner) + ".");
        }
        return true;
    }
}
