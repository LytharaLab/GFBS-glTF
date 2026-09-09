package org.lytharalab.gfbs.gltf.api.client.plugin;

import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.lytharalab.gfbs.gltf.api.client.GltfInstance;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderContext;

/** Live, render-thread-only access to one model render invocation. */
public interface GltfRenderFrame {
    GltfInstance instance();
    MultiBufferSource buffers();
    int packedLight();
    int packedOverlay();
    GltfRenderContext context();
    boolean shadowPass();
    boolean shaderPackActive();
    GltfRenderStage stage();

    /** Returns a defensive copy of the caller's model transform. */
    Matrix4f baseTransform();

    /** Draws one additional scene-wide pass through the normal skin/morph/culling machinery. */
    void draw(GltfCustomRenderPass pass);

    /**
     * Flushes buffered geometry when the supplied buffer source supports it. Use this before a
     * framebuffer composite which must observe a custom streamed pass immediately.
     */
    boolean flush();

    /** Suppression is accepted only during {@link GltfRenderStage#BEFORE_BUILTIN_PASSES}. */
    void suppress(GltfBuiltInRenderPass pass);

    boolean isSuppressed(GltfBuiltInRenderPass pass);
}
