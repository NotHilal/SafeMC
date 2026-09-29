package dev.safemc.safeplots.plot;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** A rectangular, admin-defined region. Bounds are inclusive block coordinates. */
public final class Plot {
    private final String name;
    private final String dimension;
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private @Nullable UUID owner;
    private final Set<UUID> trusted = new LinkedHashSet<>();
    private @Nullable BlockPos sign;

    public Plot(String name, String dimension, BlockPos a, BlockPos b) {
        this.name = name;
        this.dimension = dimension;
        setBounds(a, b);
    }

    /** Changes the area. Callers must check for overlaps first (see PlotManager#resize). */
    void setBounds(BlockPos a, BlockPos b) {
        this.minX = Math.min(a.getX(), b.getX());
        this.minY = Math.min(a.getY(), b.getY());
        this.minZ = Math.min(a.getZ(), b.getZ());
        this.maxX = Math.max(a.getX(), b.getX());
        this.maxY = Math.max(a.getY(), b.getY());
        this.maxZ = Math.max(a.getZ(), b.getZ());
    }

    public String name() {
        return name;
    }

    public String dimension() {
        return dimension;
    }

    public BlockPos min() {
        return new BlockPos(minX, minY, minZ);
    }

    public BlockPos max() {
        return new BlockPos(maxX, maxY, maxZ);
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    public boolean overlaps(Plot other) {
        return dimension.equals(other.dimension)
                && minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public boolean intersects(AABB box) {
        return box.intersects(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    public int minChunkX() {
        return minX >> 4;
    }

    public int maxChunkX() {
        return maxX >> 4;
    }

    public int minChunkZ() {
        return minZ >> 4;
    }

    public int maxChunkZ() {
        return maxZ >> 4;
    }

    public @Nullable UUID owner() {
        return owner;
    }

    public boolean isClaimed() {
        return owner != null;
    }

    /** Sets the owner and clears trusted players, since trust belongs to the previous owner. */
    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        this.trusted.clear();
    }

    public Set<UUID> trusted() {
        return trusted;
    }

    /** Owner or trusted player. Unclaimed plots have no members. */
    public boolean isMember(UUID player) {
        return owner != null && (owner.equals(player) || trusted.contains(player));
    }

    public @Nullable BlockPos sign() {
        return sign;
    }

    public void setSign(@Nullable BlockPos sign) {
        this.sign = sign;
    }

    public String describeBounds() {
        return "(" + minX + ", " + minY + ", " + minZ + ") to (" + maxX + ", " + maxY + ", " + maxZ + ")";
    }
}
