package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;
import org.lytharalab.gfbs.gltf.GFBSglTF;
import org.lytharalab.gfbs.gltf.api.client.GltfInstance;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderDiagnostics;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderOptions.SkinningMode;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderer;
import org.lytharalab.gfbs.gltf.api.client.node.GltfPrimitiveKey;
import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfMesh;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfScene;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;
import org.lytharalab.gfbs.gltf.api.model.GltfTexture;
import org.lytharalab.gfbs.gltf.api.model.GltfTextureInfo;
import org.lytharalab.gfbs.gltf.api.model.MorphTarget;
import org.lytharalab.gfbs.gltf.api.model.PrimitiveMode;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Runs against Minecraft's own render context via runGpuSkinningTest. */
@Mod.EventBusSubscriber(modid = GFBSglTF.MODID, value = Dist.CLIENT)
public final class GltfSkinningClientCheck {
    private static final int SIZE = 96;
    private static final GltfPrimitiveKey PRIMITIVE = new GltfPrimitiveKey(0, 0, 0);
    private static CompletableFuture<Void> reload;
    private static boolean done;

    private GltfSkinningClientCheck() {}

    @SubscribeEvent
    public static void onRender(TickEvent.RenderTickEvent event) {
        if (!Boolean.getBoolean("gfbs.gltf.gpuSkinningTest") || done || event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getInstance();
        if (client.screen == null || client.getOverlay() != null) return;
        if (reload == null) {
            done = true;
            describeDriver();
            verifyDensePalette();
            compareRenderedFrames(client, true);
            reload = client.reloadResourcePacks();
            done = false;
        } else if (reload.isDone()) {
            done = true;
            reload.join();
            var stats = GltfRenderer.diagnostics();
            require(stats.residentGpuGeometryBytes() == 0 && stats.residentGpuDeformationBytes() == 0
                && stats.cachedCpuDeformationBytes() == 0, "Resource reload must release old deformation and geometry");
            compareRenderedFrames(client, false);
            GFBSglTF.LOGGER.info("GFBS glTF GPU skinning acceptance passed, including client resource reload");
            client.stop();
        }
    }

    private static void describeDriver() {
        require(GltfRenderer.maxGpuSkinningJoints() > 0, "GPU skinning shaders did not load");
        GFBSglTF.LOGGER.info("GPU skinning test device {} ({}), palette limit {}",
            GL11C.glGetString(GL11C.GL_RENDERER), GL11C.glGetString(GL11C.GL_VERSION),
            GltfRenderer.maxGpuSkinningJoints());
    }

    private static void verifyDensePalette() {
        int limit = GltfRenderer.maxGpuSkinningJoints();
        int vertices = (limit + 2) / 3 * 3;
        float[] positions = new float[vertices * 3];
        float[] normals = new float[positions.length];
        int[] joints = new int[vertices * 4];
        float[] weights = new float[joints.length];
        int[] indices = new int[vertices];
        for (int i = 0; i < vertices; i++) {
            positions[i * 3] = (i % 7) * 0.03f;
            positions[i * 3 + 1] = (i % 5) * 0.02f;
            normals[i * 3 + 2] = 1.0f;
            joints[i * 4] = Math.min(i, limit - 1);
            weights[i * 4] = 0.7f;
            indices[i] = i;
        }
        GltfPrimitive primitive = new GltfPrimitive(PrimitiveMode.TRIANGLES, 0, vertices, positions, normals, null,
            null, null, null, joints, weights, indices, List.of());
        GltfAsset asset = asset("dense", limit, primitive, material(false), null);
        try (GltfInstance instance = new GltfInstance(asset)) {
            instance.nodes().node(limit).translation(0.17f, -0.2f, 0.13f).rotation(0.2f, 0.3f, 0.4f, 0.8f);
            var verification = GltfRenderer.verifySkinning(instance, PRIMITIVE, 4_096).orElseThrow(
                () -> new AssertionError("No transform feedback results for dense palette"));
            require(verification.matches(), "Dense palette mismatch " + verification);
            require(verification.sampledVertices() == Math.min(4_096, vertices), "Unexpected sample count");
            GFBSglTF.LOGGER.info("Dense {}-joint GPU skinning verification {}", limit, verification);
        }
    }

    private static void compareRenderedFrames(Minecraft client, boolean benchmark) {
        GltfAsset weighted = asset("weighted", GltfSkin.MAX_JOINTS, grid(48, false), material(true), null);
        GltfAsset morphed = asset("morphed", 4, grid(3, true), material(true), new float[]{0.35f});
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        TextureTarget target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        target.setClearColor(0, 0, 0, 0);
        RenderSystem.backupProjectionMatrix();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.setShaderFogStart(100f);
        RenderSystem.setShaderFogEnd(200f);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        GltfRenderer.setDiagnosticsEnabled(true);
        try (GltfInstance first = new GltfInstance(weighted);
             GltfInstance second = new GltfInstance(weighted);
             GltfInstance morph = new GltfInstance(morphed)) {
            PoseStack pose = new PoseStack();
            pose.scale(-0.88f, 0.73f, 1.1f);
            first.nodes().node(GltfSkin.MAX_JOINTS).translation(0.11f, 0.05f, 0.0f);
            second.nodes().node(GltfSkin.MAX_JOINTS).translation(-0.26f, 0.13f, 0.0f);

            GltfRenderer.resetDiagnostics();
            byte[] gpu = render(target, pose, first, SkinningMode.AUTO);
            GltfRenderDiagnostics graphics = GltfRenderer.diagnostics();
            require(graphics.gpuSkinnedDraws() == 2 && graphics.gpuGeometryUploads() == 1,
                "Base and emission must use the same resident GPU geometry " + graphics);
            require(graphics.gpuDeformationBuilds() == 1 && graphics.gpuDeformationReuses() == 1,
                "Base and emission must share GPU skin results " + graphics);
            require(graphics.paletteMatrixUpdates() == 4, "Sparse palette must include just four joints " + graphics);
            byte[] secondGpu = render(target, pose, second, SkinningMode.AUTO);
            require(GltfRenderer.diagnostics().gpuGeometryUploads() == 1, "A second instance should share the GPU upload");
            require(!Arrays.equals(gpu, secondGpu), "Different instance poses must change the output");

            GltfRenderer.resetDiagnostics();
            compare(gpu, render(target, pose, first, SkinningMode.CPU), "mirrored sparse skin with emissive");
            compare(secondGpu, render(target, pose, second, SkinningMode.CPU), "second instance");
            var cpu = GltfRenderer.diagnostics();
            require(cpu.cpuSkinnedDraws() == 4 && cpu.cpuDeformationBuilds() == 2
                && cpu.cpuDeformationCacheHits() == 2, "CPU base and emission must reuse deformation " + cpu);

            first.nodes().node(GltfSkin.MAX_JOINTS).translation(0.27f, 0.05f, 0.0f);
            GltfRenderer.resetDiagnostics();
            byte[] updatedGpu = render(target, pose, first, SkinningMode.AUTO);
            var updated = GltfRenderer.diagnostics();
            require(updated.paletteMatrixUpdates() == 1, "One joint must update only one compact matrix " + updated);
            require(!Arrays.equals(gpu, updatedGpu), "Changed joint moved no pixels");
            GltfRenderer.resetDiagnostics();
            compare(updatedGpu, render(target, pose, first, SkinningMode.CPU), "incrementally updated pose");
            require(GltfRenderer.diagnostics().paletteMatrixUpdates() == 1, "CPU palette should update one matrix");

            GltfRenderer.resetDiagnostics();
            byte[] initialMorph = render(target, pose, morph, SkinningMode.AUTO);
            require(GltfRenderer.verifySkinning(morph, PRIMITIVE, 8).isEmpty(), "Active morph should use the CPU");
            var morphStats = GltfRenderer.diagnostics();
            require(morphStats.cpuSkinnedDraws() == 2 && morphStats.morphBuilds() == 1
                && morphStats.morphCacheHits() == 1, "Morph results must be shared between CPU passes " + morphStats);
            morph.nodes().node(0).morphWeights(0.8f);
            byte[] updatedMorph = render(target, pose, morph, SkinningMode.AUTO);
            require(!Arrays.equals(initialMorph, updatedMorph), "Changed morph weights must invalidate cached vertices");
            require(GltfRenderer.diagnostics().morphBuilds() == 2, "The new weights must rebuild once");

            var sparse = GltfRenderer.verifySkinning(first, PRIMITIVE, 3_000).orElseThrow();
            require(sparse.matches(), "Sparse GPU skin mismatch " + sparse);
            compareLitAndCutout(client, target);
            if (benchmark) benchmarkSubmission(target);
            require(GL11C.glGetError() == GL11C.GL_NO_ERROR, "OpenGL error during GPU skinning checks");
            GFBSglTF.LOGGER.info("GPU skinning and CPU cache checks passed, sparse verification {}", sparse);
        } finally {
            GltfRenderer.setDiagnosticsEnabled(false);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            target.destroyBuffers();
            client.getMainRenderTarget().bindWrite(true);
        }
    }

    private static void compareLitAndCutout(Minecraft client, TextureTarget target) {
        // At the title screen, the world has not populated Minecraft's light texture yet.
        var light = client.gameRenderer.lightTexture();
        light.turnOnLightLayer();
        try (NativeImage white = new NativeImage(16, 16, false)) {
            white.fillRect(0, 0, 16, 16, 0xffffffff);
            white.upload(0, 0, 0, false);
        } finally {
            light.turnOffLightLayer();
        }
        RenderSystem.setShaderLights(new Vector3f(0.0f, 0.0f, 1.0f), new Vector3f(0.6f, 0.0f, 0.8f));
        GltfTexture cutoutImage;
        try (NativeImage image = new NativeImage(2, 2, false)) {
            image.fillRect(0, 0, 2, 2, 0xffffffff);
            image.setPixelRGBA(0, 0, 0x00ffffff);
            cutoutImage = new GltfTexture("cutout", "image/png", ByteBuffer.wrap(image.asByteArray()),
                9728, 9728, 33071, 33071);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        GltfMaterial cutout = new GltfMaterial("cutout", null,
            new GltfTextureInfo(0, 1, 0.09f, 0.05f, 0.92f, 0.91f, 0.15f), 0, 1,
            null, null, 1, null, 1, null, null, 1, AlphaMode.MASK, 0.5f, false, false);
        float[] positions = {-0.77f, -0.59f, 0, 0.73f, -0.59f, 0, -0.77f, 0.79f, 0, 0.73f, 0.79f, 0};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        float[] uv = {0, 0, 1, 0, 0, 1, 1, 1};
        float[] color = {0.4f, 0.2f, 0.6f, 1, 0.9f, 0.3f, 0.2f, 1, 0.5f, 0.8f, 0.4f, 1, 0.4f, 0.3f, 0.8f, 1};
        int[] joints = {3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2};
        float[] weights = {0, 0, 0, 0, .3f, .2f, .15f, .25f, .3f, 0, 0, 0, .3f, -.1f, 0, 0};
        GltfPrimitive strip = new GltfPrimitive(PrimitiveMode.TRIANGLE_STRIP, 0, 4, positions, normals, null,
            null, uv, color, joints, weights, new int[]{0, 1, 2, 3}, List.of());
        GltfAsset asset = asset("cutout", 4, strip,
            List.of(GltfMaterial.defaultMaterial(), cutout), List.of(cutoutImage), null);
        try (GltfInstance instance = new GltfInstance(asset)) {
            PoseStack pose = new PoseStack();
            pose.scale(0.85f, 0.96f, 1.4f);
            instance.nodes().node(4).rotation(0.16f, -0.07f, 0.23f, 0.96f);
            instance.nodes().node(0).colorMultiplier(0.78f, 0.92f, 0.86f);
            GltfRenderer.resetDiagnostics();
            byte[] solidGpu = render(target, pose, instance, SkinningMode.AUTO);
            compare(solidGpu, render(target, pose, instance, SkinningMode.CPU), "lit indexed triangle strip");
            instance.nodes().primitive(PRIMITIVE).material(1);
            byte[] cutoutGpu = render(target, pose, instance, SkinningMode.AUTO);
            compare(cutoutGpu, render(target, pose, instance, SkinningMode.CPU), "cutout with transformed UV1");
            var stats = GltfRenderer.diagnostics();
            require(stats.gpuSkinnedDraws() == 2 && stats.gpuGeometryUploads() == 1
                && stats.gpuDeformationBuilds() == 0, "Material switching should reuse direct GPU geometry " + stats);
            require(countColoredPixels(cutoutGpu) < countColoredPixels(solidGpu), "Expected cutout texture coverage");
            require(GltfRenderer.verifySkinning(instance, PRIMITIVE, 4).orElseThrow().matches(),
                "Zero or non-normalized vertex weights differ between backends");

            instance.renderOptions().alpha(0.7f);
            GltfRenderer.resetDiagnostics();
            render(target, pose, instance, SkinningMode.AUTO);
            require(GltfRenderer.diagnostics().cpuSkinnedDraws() == 1, "Translucent triangles should stream");
        }
    }

    private static void benchmarkSubmission(TextureTarget target) {
        int size = 128;
        GltfAsset asset = asset("timing", GltfSkin.MAX_JOINTS, grid(size, false), material(false), null);
        GltfRenderer.setDiagnosticsEnabled(false);
        try (GltfInstance instance = new GltfInstance(asset)) {
            double cpu = medianSubmission(target, instance, SkinningMode.CPU);
            double gpu = medianSubmission(target, instance, SkinningMode.AUTO);
            GFBSglTF.LOGGER.info("Skinned submission benchmark {} indexed triangles, CPU {} ms, GPU {} ms",
                (size - 1) * (size - 1) * 2, cpu, gpu);
        } finally {
            GltfRenderer.setDiagnosticsEnabled(true);
        }
    }

    private static double medianSubmission(TextureTarget target, GltfInstance instance, SkinningMode mode) {
        int warmup = 16;
        long[] samples = new long[40];
        PoseStack pose = new PoseStack();
        instance.renderOptions().skinning(mode).frustumCulling(false);
        for (int i = 0; i < warmup + samples.length; i++) {
            instance.nodes().node(GltfSkin.MAX_JOINTS).translation((i % 19) * 0.013f, 0, 0);
            clear(target);
            GL11C.glFinish();
            long start = System.nanoTime();
            GltfRenderer.render(instance, pose, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            long elapsed = System.nanoTime() - start;
            if (i >= warmup) samples[i - warmup] = elapsed;
        }
        GL11C.glFinish();
        Arrays.sort(samples);
        return (samples[19] + samples[20]) / 2_000_000.0;
    }

    private static byte[] render(TextureTarget target, PoseStack pose, GltfInstance instance, SkinningMode mode) {
        clear(target);
        instance.renderOptions().skinning(mode).frustumCulling(false);
        GltfRenderer.render(instance, pose, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        try {
            GL11C.glReadPixels(0, 0, SIZE, SIZE, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
            byte[] result = new byte[pixels.remaining()];
            pixels.get(result);
            return result;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static void clear(TextureTarget target) {
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(true, true, true, true);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
    }

    private static int countColoredPixels(byte[] pixels) {
        int count = 0;
        for (int i = 0; i < pixels.length; i += 4) {
            if (pixels[i] != 0 || pixels[i + 1] != 0 || pixels[i + 2] != 0) count++;
        }
        return count;
    }

    private static void compare(byte[] gpu, byte[] cpu, String description) {
        int changed = 0, worst = 0;
        for (int i = 0; i < gpu.length; i += 4) {
            int difference = 0;
            for (int channel = 0; channel < 3; channel++) {
                difference = Math.max(difference, Math.abs(Byte.toUnsignedInt(cpu[i + channel]) - Byte.toUnsignedInt(gpu[i + channel])));
            }
            if (difference > 2) changed++;
            worst = Math.max(worst, difference);
        }
        require(countColoredPixels(gpu) > 1_000, "Expected visible triangles for " + description);
        require(changed <= 4, "Framebuffer mismatch for " + description + ": " + changed + " pixels, max " + worst);
    }

    private static GltfPrimitive grid(int size, boolean morph) {
        int vertices = size * size;
        float[] positions = new float[vertices * 3];
        float[] normals = morph ? null : new float[positions.length];
        int[] joints = new int[vertices * 4];
        float[] weights = new float[joints.length];
        int highest = morph ? 3 : GltfSkin.MAX_JOINTS - 1;
        float[] delta = morph ? new float[positions.length] : null;
        for (int vertex = 0; vertex < vertices; vertex++) {
            positions[vertex * 3] = -0.74f + vertex % size * (1.33f / (size - 1));
            positions[vertex * 3 + 1] = -0.61f + vertex / size * (1.51f / (size - 1));
            if (normals != null) normals[vertex * 3 + 2] = 1.0f;
            if (delta != null) delta[vertex * 3] = 0.16f;
            joints[vertex * 4] = highest;
            joints[vertex * 4 + 1] = 0;
            joints[vertex * 4 + 2] = 1;
            joints[vertex * 4 + 3] = 2;
            weights[vertex * 4] = 0.3f;
            weights[vertex * 4 + 1] = 0.25f;
            weights[vertex * 4 + 2] = 0.35f;
            weights[vertex * 4 + 3] = vertex % 2 == 0 ? 0.15f : 0.0f;
        }
        int[] indices = new int[(size - 1) * (size - 1) * 6];
        for (int y = 0, i = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                int corner = y * size + x;
                indices[i++] = corner;
                indices[i++] = corner + 1;
                indices[i++] = corner + size + 1;
                indices[i++] = corner;
                indices[i++] = corner + size + 1;
                indices[i++] = corner + size;
            }
        }
        return new GltfPrimitive(PrimitiveMode.TRIANGLES, 0, vertices, positions, normals, null, null, null,
            null, joints, weights, indices, delta == null ? List.of() : List.of(new MorphTarget(delta, null, null)));
    }

    private static GltfMaterial material(boolean emission) {
        return new GltfMaterial("test", new float[]{0.33f, 0.51f, 0.71f, 1f}, null, 0, 1,
            null, null, 1, null, 1, emission ? new float[]{0.21f, 0.12f, 0.15f} : null,
            null, 1, AlphaMode.OPAQUE, 0.5f, false, true);
    }

    private static GltfAsset asset(String name, int jointCount, GltfPrimitive primitive,
                                   GltfMaterial material, float[] defaultMorph) {
        return asset(name, jointCount, primitive, List.of(material), List.of(), defaultMorph);
    }

    private static GltfAsset asset(String name, int jointCount, GltfPrimitive primitive,
                                   List<GltfMaterial> materials, List<GltfTexture> textures, float[] defaultMorph) {
        int[] joints = new int[jointCount];
        float[] bind = new float[jointCount * 16];
        List<GltfNode> nodes = new ArrayList<>(jointCount + 1);
        nodes.add(new GltfNode("mesh", -1, new int[0], new int[]{0}, 0, null, null, null, null, null));
        GltfNode sharedJoint = new GltfNode("joint", -1, new int[0], new int[0], -1, null, null, null, null, null);
        for (int i = 0; i < jointCount; i++) {
            joints[i] = i + 1;
            for (int j = 0; j < 16; j += 5) bind[i * 16 + j] = 1.0f;
            nodes.add(sharedJoint);
        }
        return new GltfAsset(ResourceLocation.fromNamespaceAndPath("gfbs_gltf", "test/" + name),
            List.of(new GltfScene("scene", new int[]{0})), nodes, List.of(new GltfMesh("mesh", List.of(primitive), defaultMorph)),
            materials, textures, List.of(new GltfSkin("skin", -1, joints, bind)), List.of(), List.of(), List.of());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
