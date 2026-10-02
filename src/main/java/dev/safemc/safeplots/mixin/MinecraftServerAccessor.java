package dev.safemc.safeplots.mixin;

import java.util.Map;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What {@link dev.safemc.safeplots.world.Worlds} needs to add and remove levels while the server runs. */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> safeplots$levels();

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess safeplots$storageSource();

    @Accessor("executor")
    Executor safeplots$executor();
}
