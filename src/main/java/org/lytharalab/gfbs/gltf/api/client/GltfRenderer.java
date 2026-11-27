package org.lytharalab.gfbs.gltf.api.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lytharalab.gfbs.gltf.api.client.node.GltfPrimitiveKey;
import org.lytharalab.gfbs.gltf.client.render.EntityGltfRenderer;
import org.lytharalab.gfbs.gltf.client.render.GltfGpuCache;
import org.lytharalab.gfbs.gltf.client.render.GltfGpuSkinning;
import org.lytharalab.gfbs.gltf.client.render.GltfRenderMetrics;
import org.lytharalab.gfbs.gltf.client.render.GltfSkinningVerifier;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;

import java.util.Optional;

@OnlyIn(Dist.CLIENT)
public final class GltfRenderer {
    private GltfRenderer() {
    }

    /** Prepares CPU-side draw plans and spatial indexes. Safe to call from a loading worker. */
    public static void prepareScene(GltfAsset asset, int scene) {
        GltfGpuCache.prepareScene(asset, scene);
    }

    /** Maximum number of distinct joints in a GPU primitive palette. Zero means the backend is unavailable. */
    public static int maxGpuSkinningJoints() {
        RenderSystem.assertOnRenderThread();
        return GltfGpuSkinning.maxPaletteJoints();
    }

    /**
     * Compares the current pose on both backends using a synchronous GPU readback.
     * Call on the render thread for diagnostics. An empty result indicates an unsupported
     * primitive, a compatibility pass, or unavailable GPU resources.
     */
    public static Optional<GltfSkinningVerification> verifySkinning(GltfInstance instance, GltfPrimitiveKey key,
                                                                    int sampleLimit) {
        return GltfSkinningVerifier.verify(instance, key, sampleLimit);
    }

    /** Enables or pauses collection on the render thread. Collection starts disabled. */
    public static void setDiagnosticsEnabled(boolean enabled) {
        RenderSystem.assertOnRenderThread();
        GltfRenderMetrics.setEnabled(enabled);
    }

    /** Returns work observed on the render thread since the last reset. */
    public static GltfRenderDiagnostics diagnostics() {
        RenderSystem.assertOnRenderThread();
        return GltfRenderMetrics.snapshot();
    }

    /** Clears accumulated counters on the render thread. */
    public static void resetDiagnostics() {
        RenderSystem.assertOnRenderThread();
        GltfRenderMetrics.reset();
    }

    public static void render(GltfInstance instance, PoseStack poseStack, int packedLight, int packedOverlay) {
        EntityGltfRenderer.render(instance, poseStack, packedLight, packedOverlay, null);
    }

    public static void render(GltfInstance instance, PoseStack poseStack, int packedLight,
                              int packedOverlay, GltfRenderContext context) {
        EntityGltfRenderer.render(instance, poseStack, packedLight, packedOverlay, context);
    }

    public static void render(GltfInstance instance, PoseStack poseStack, MultiBufferSource buffers,
                              int packedLight, int packedOverlay) {
        EntityGltfRenderer.render(instance, poseStack, buffers, packedLight, packedOverlay, null);
    }

    public static void render(GltfInstance instance, PoseStack poseStack, MultiBufferSource buffers,
                              int packedLight, int packedOverlay, GltfRenderContext context) {
        EntityGltfRenderer.render(instance, poseStack, buffers, packedLight, packedOverlay, context);
    }
}
