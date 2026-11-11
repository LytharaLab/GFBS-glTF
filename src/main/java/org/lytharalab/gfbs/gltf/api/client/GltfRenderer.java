package org.lytharalab.gfbs.gltf.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lytharalab.gfbs.gltf.client.render.EntityGltfRenderer;
import org.lytharalab.gfbs.gltf.client.render.GltfGpuCache;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;

@OnlyIn(Dist.CLIENT)
public final class GltfRenderer {
    private GltfRenderer() {
    }

    /** Prepares CPU-side draw plans and spatial indexes. Safe to call from a loading worker. */
    public static void prepareScene(GltfAsset asset, int scene) {
        GltfGpuCache.prepareScene(asset, scene);
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
