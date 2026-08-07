package org.lytharalab.gfbs.gltf.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Internal allocation-free bridge to Frustum's scalar cube test. */
@Mixin(Frustum.class)
public interface FrustumInvoker {
    @Invoker("cubeInFrustum")
    boolean gfbsGltf$cubeInFrustum(double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ);
}
