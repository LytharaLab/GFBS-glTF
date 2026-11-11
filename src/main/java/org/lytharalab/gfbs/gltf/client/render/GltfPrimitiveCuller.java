package org.lytharalab.gfbs.gltf.client.render;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
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
        return isVisible(primitive.bounds(), modelView, context, options);
    }

    static boolean isVisible(GltfBounds source, Matrix4f modelView,
                             GltfRenderContext context, GltfRenderOptions options) {
        return isVisible(source, modelView, context, options, false);
    }

    static boolean isVisible(GltfBounds source, Matrix4f modelView, GltfRenderContext context,
                             GltfRenderOptions options, boolean shadowPass) {
        if (!enabled(context, options, shadowPass)) return true;
        if (!source.valid()) return true;

        BoundsScratch bounds = SCRATCH.get();
        Frustum frustum = context.frustum();
        Matrix4f axes = modelView;
        if (options.frustumCulling() && frustum != null && context.viewToWorldView() != null) {
            axes = bounds.worldAxes.set(context.viewToWorldView()).mul(modelView);
        }
        transform(source, axes, bounds);
        if (!shadowPass && options.maxRenderDistance() > 0.0) {
            double x = Math.max(0, Math.max(bounds.minX, -bounds.maxX));
            double y = Math.max(0, Math.max(bounds.minY, -bounds.maxY));
            double z = Math.max(0, Math.max(bounds.minZ, -bounds.maxZ));
            double maximum = options.maxRenderDistance();
            if (x * x + y * y + z * z > maximum * maximum) return false;
        }
        if (options.frustumCulling() && frustum != null) {
            if (frustum.getClass() != Frustum.class) {
                return frustum.isVisible(new AABB(
                    bounds.minX + context.cameraX(), bounds.minY + context.cameraY(), bounds.minZ + context.cameraZ(),
                    bounds.maxX + context.cameraX(), bounds.maxY + context.cameraY(), bounds.maxZ + context.cameraZ()));
            }
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

    static boolean enabled(GltfRenderContext context, GltfRenderOptions options) {
        return enabled(context, options, false);
    }

    static boolean enabled(GltfRenderContext context, GltfRenderOptions options, boolean shadowPass) {
        return context != null && (!shadowPass && options.maxRenderDistance() > 0.0
            || options.frustumCulling() && context.frustum() != null);
    }

    private static void transform(GltfBounds bounds, Matrix4f matrix, BoundsScratch output) {
        double x = ((double) bounds.minX() + bounds.maxX()) * 0.5;
        double y = ((double) bounds.minY() + bounds.maxY()) * 0.5;
        double z = ((double) bounds.minZ() + bounds.maxZ()) * 0.5;
        double hx = ((double) bounds.maxX() - bounds.minX()) * 0.5;
        double hy = ((double) bounds.maxY() - bounds.minY()) * 0.5;
        double hz = ((double) bounds.maxZ() - bounds.minZ()) * 0.5;
        double cx = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
        double cy = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
        double cz = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
        double ex = Math.abs(matrix.m00()) * hx + Math.abs(matrix.m10()) * hy + Math.abs(matrix.m20()) * hz;
        double ey = Math.abs(matrix.m01()) * hx + Math.abs(matrix.m11()) * hy + Math.abs(matrix.m21()) * hz;
        double ez = Math.abs(matrix.m02()) * hx + Math.abs(matrix.m12()) * hy + Math.abs(matrix.m22()) * hz;
        output.minX = cx - ex; output.maxX = cx + ex;
        output.minY = cy - ey; output.maxY = cy + ey;
        output.minZ = cz - ez; output.maxZ = cz + ez;
    }

    private static final class BoundsScratch {
        final Matrix4f worldAxes = new Matrix4f();
        double minX, minY, minZ, maxX, maxY, maxZ;
    }
}
