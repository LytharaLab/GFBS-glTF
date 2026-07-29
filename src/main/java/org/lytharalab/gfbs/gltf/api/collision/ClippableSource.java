package org.lytharalab.gfbs.gltf.api.collision;

import net.minecraft.world.phys.Vec3;

public interface ClippableSource extends CollisionSource {
    Vec3 clip(Vec3 from, Vec3 to);
}
