package org.lytharalab.gfbs.gltf.api.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;

/**
 * Optional frame data enabling distance, primitive-frustum and occlusion culling.
 */
public record GltfRenderContext(Matrix4f projection, Frustum frustum,
                                double cameraX, double cameraY, double cameraZ,
                                Matrix3f viewToWorld) {
    public GltfRenderContext {
        projection = projection == null ? null : new Matrix4f(projection);
        viewToWorld = viewToWorld == null ? null : new Matrix3f(viewToWorld);
    }

    /** Camera-relative position with axes aligned to world space. */
    public GltfRenderContext(Matrix4f projection, Frustum frustum,
                             double cameraX, double cameraY, double cameraZ) {
        this(projection, frustum, cameraX, cameraY, cameraZ, null);
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

    /** Frame context for Forge world/entity pose stacks with vanilla pitch and yaw applied. */
    public static GltfRenderContext viewSpace(Matrix4f projection, Frustum frustum, Camera camera) {
        Vec3 origin = position(camera);
        Matrix3f rotation = camera == null ? null : new Matrix3f()
            .rotateY((float) Math.toRadians(-camera.getYRot() - 180.0f))
            .rotateX((float) Math.toRadians(-camera.getXRot()));
        return new GltfRenderContext(projection, frustum, origin.x, origin.y, origin.z, rotation);
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

    @Override public Matrix3f viewToWorld() {
        return viewToWorld == null ? null : new Matrix3f(viewToWorld);
    }

    /** Read-only orientation for renderer culling. */
    public Matrix3fc viewToWorldView() { return viewToWorld; }
}
