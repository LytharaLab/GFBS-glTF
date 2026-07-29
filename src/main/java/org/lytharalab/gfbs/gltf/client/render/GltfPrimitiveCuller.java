package org.lytharalab.gfbs.gltf.client.render;

import org.joml.Matrix4f;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderContext;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderOptions;
import org.lytharalab.gfbs.gltf.api.model.GltfBounds;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;

final class GltfPrimitiveCuller {
    private GltfPrimitiveCuller() {
    }

    static boolean isVisible(GltfPrimitive primitive, Matrix4f modelView,
                             GltfRenderContext context, GltfRenderOptions options) {
        if (context == null || primitive.hasDynamicGeometry()) return true;
        GltfBounds bounds = primitive.bounds().transform(modelView);
        if (!bounds.valid()) return true;
        if (options.maxRenderDistance() > 0.0) {
            double x = bounds.centerX();
            double y = bounds.centerY();
            double z = bounds.centerZ();
            double maximum = options.maxRenderDistance() + bounds.radius();
            if (x * x + y * y + z * z > maximum * maximum) return false;
        }
        if (options.frustumCulling() && context.frustum() != null) {
            return context.frustum().isVisible(bounds.minecraft(
                context.cameraX(), context.cameraY(), context.cameraZ()
            ));
        }
        return true;
    }
}
