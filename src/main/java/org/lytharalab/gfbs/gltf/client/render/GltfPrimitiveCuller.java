package org.lytharalab.gfbs.gltf.client.render;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderContext;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderOptions;
import org.lytharalab.gfbs.gltf.api.model.GltfBounds;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.mixin.FrustumInvoker;

/** Primitive culling with no per-test bounds/vector/AABB allocation. */
final class GltfPrimitiveCuller {
    private static final ThreadLocal<BoundsScratch> SCRATCH =
        ThreadLocal.withInitial(BoundsScratch::new);

    private GltfPrimitiveCuller() {}

    static boolean isVisible(GltfPrimitive primitive, Matrix4f modelView,
                             GltfRenderContext context, GltfRenderOptions options) {
        return isVisible(primitive, modelView, context, options, primitive.hasDynamicGeometry());
    }

    static boolean isVisible(GltfPrimitive primitive, Matrix4f modelView,
                             GltfRenderContext context, GltfRenderOptions options,
                             boolean dynamicGeometry) {
        if (context == null || dynamicGeometry) return true;
        GltfBounds source = primitive.bounds();
        if (!source.valid()) return true;

        BoundsScratch bounds = SCRATCH.get();
        transform(source, modelView, bounds);
        if (options.maxRenderDistance() > 0.0) {
            double x = (bounds.minX + bounds.maxX) * 0.5;
            double y = (bounds.minY + bounds.maxY) * 0.5;
            double z = (bounds.minZ + bounds.maxZ) * 0.5;
            double hx = (bounds.maxX - bounds.minX) * 0.5;
            double hy = (bounds.maxY - bounds.minY) * 0.5;
            double hz = (bounds.maxZ - bounds.minZ) * 0.5;
            double maximum = options.maxRenderDistance() + Math.sqrt(hx * hx + hy * hy + hz * hz);
            if (x * x + y * y + z * z > maximum * maximum) return false;
        }
        Frustum frustum = context.frustum();
        if (options.frustumCulling() && frustum != null) {
            // modelView is camera-relative, while Frustum.cubeInFrustum accepts world-space
            // coordinates and subtracts its own camera origin internally. Preserve the old
            // GltfBounds.minecraft(cameraX, cameraY, cameraZ) semantics without allocating AABB.
            return ((FrustumInvoker) frustum).gfbsGltf$cubeInFrustum(
                bounds.minX + context.cameraX(),
                bounds.minY + context.cameraY(),
                bounds.minZ + context.cameraZ(),
                bounds.maxX + context.cameraX(),
                bounds.maxY + context.cameraY(),
                bounds.maxZ + context.cameraZ()
            );
        }
        return true;
    }

    private static void transform(GltfBounds bounds, Matrix4f matrix, BoundsScratch output) {
        float minX = bounds.minX(), minY = bounds.minY(), minZ = bounds.minZ();
        float maxX = bounds.maxX(), maxY = bounds.maxY(), maxZ = bounds.maxZ();
        double outMinX = Double.POSITIVE_INFINITY;
        double outMinY = Double.POSITIVE_INFINITY;
        double outMinZ = Double.POSITIVE_INFINITY;
        double outMaxX = Double.NEGATIVE_INFINITY;
        double outMaxY = Double.NEGATIVE_INFINITY;
        double outMaxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? minX : maxX;
            double y = (corner & 2) == 0 ? minY : maxY;
            double z = (corner & 4) == 0 ? minZ : maxZ;
            double tx = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
            double ty = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
            double tz = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
            outMinX = Math.min(outMinX, tx); outMaxX = Math.max(outMaxX, tx);
            outMinY = Math.min(outMinY, ty); outMaxY = Math.max(outMaxY, ty);
            outMinZ = Math.min(outMinZ, tz); outMaxZ = Math.max(outMaxZ, tz);
        }
        output.minX = outMinX; output.minY = outMinY; output.minZ = outMinZ;
        output.maxX = outMaxX; output.maxY = outMaxY; output.maxZ = outMaxZ;
    }

    private static final class BoundsScratch {
        double minX, minY, minZ, maxX, maxY, maxZ;
    }
}
