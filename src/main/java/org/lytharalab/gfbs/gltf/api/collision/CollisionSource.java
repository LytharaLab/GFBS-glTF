package org.lytharalab.gfbs.gltf.api.collision;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

public interface CollisionSource {
    void collect(AABB area, List<VoxelShape> output);
    Level level();
    boolean isActive();
}
