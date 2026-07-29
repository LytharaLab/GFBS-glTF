package org.lytharalab.gfbs.gltf.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lytharalab.gfbs.gltf.collision.GltfCollisionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(Entity.class)
public abstract class EntityCollisionMixin {
    @Redirect(
        method = "collide",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntityCollisions("
                + "Lnet/minecraft/world/entity/Entity;"
                + "Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"
        )
    )
    private List<VoxelShape> gfbsGltf$appendModelCollision(
        Level level, Entity entity, AABB area
    ) {
        return GltfCollisionManager.appendShapes(
            level, entity, area, level.getEntityCollisions(entity, area)
        );
    }
}
