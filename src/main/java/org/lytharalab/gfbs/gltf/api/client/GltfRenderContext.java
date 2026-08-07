package org.lytharalab.gfbs.gltf.api.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Optional frame data enabling distance, primitive-frustum and occlusion culling.
 */
public record GltfRenderContext(Matrix4f projection, Frustum frustum,
                                double cameraX, double cameraY, double cameraZ) {
    public GltfRenderContext {
        projection = projection == null ? null : new Matrix4f(projection);
    }

    public GltfRenderContext(Matrix4f projection, Frustum frustum, Camera camera) {
        this(projection, frustum, position(camera));
    }

    private GltfRenderContext(Matrix4f projection, Frustum frustum, Vec3 camera) {
        this(projection, frustum, camera.x, camera.y, camera.z);
    }

    private static Vec3 position(Camera camera) {
        if (camera == null) return Vec3.ZERO;
        return camera.getPosition();
    }

    @Override
    public Matrix4f projection() {
        return projection == null ? null : new Matrix4f(projection);
    }

    /**
     * Read-only projection view for renderer hot paths. Unlike {@link #projection()}, this does not
     * allocate a defensive matrix copy. Integrations must never mutate the returned matrix.
     */
    public Matrix4f projectionView() { return projection; }
}
