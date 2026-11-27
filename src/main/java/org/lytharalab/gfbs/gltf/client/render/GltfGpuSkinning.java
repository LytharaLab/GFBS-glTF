package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lytharalab.gfbs.gltf.GFBSglTF;
import org.lytharalab.gfbs.gltf.api.client.node.GltfNodeManager.SkinPalette;
import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;
import org.lytharalab.gfbs.gltf.api.model.GltfTextureInfo;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/** Single-pass vertex skinning for built-in triangle draws on the vanilla render pipeline. */
public final class GltfGpuSkinning {
    private static final int SOLID = 0;
    private static final int CUTOUT = 1;
    private static final int EYES = 2;
    private static final DrawShader[] SHADERS = new DrawShader[3];
    private static int paletteLimit;
    private static int maxCachedVertices;
    private static GltfSkinPaletteBuffer palette;

    private GltfGpuSkinning() {}

    public static void registerShaders(RegisterShadersEvent event) {
        Arrays.fill(SHADERS, null);
        paletteLimit = 0;
        maxCachedVertices = 0;
        try {
            if (!GL.getCapabilities().OpenGL32
                || GL11C.glGetInteger(GL20C.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS) < 3
                || GL11C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS) <= GltfSkinPaletteBuffer.TEXTURE_UNIT) return;
            int texels = GL11C.glGetInteger(GL31C.GL_MAX_TEXTURE_BUFFER_SIZE);
            int limit = texels / GltfSkinPaletteBuffer.TEXELS_PER_JOINT;
            if (limit <= 0) return;
            register(event, SOLID, "skinning_solid");
            register(event, CUTOUT, "skinning_cutout");
            register(event, EYES, "skinning_eyes");
            paletteLimit = Math.min(GltfSkin.MAX_JOINTS, limit);
            maxCachedVertices = texels / (GltfSkinFeedback.FLOATS_PER_VERTEX / 4);
        } catch (IOException | RuntimeException | LinkageError failure) {
            GFBSglTF.LOGGER.warn("GPU skinning shaders are unavailable. Using CPU skinning.", failure);
        }
    }

    private static void register(RegisterShadersEvent event, int slot, String name) throws IOException {
        ShaderInstance shader = new ShaderInstance(event.getResourceProvider(),
            ResourceLocation.fromNamespaceAndPath(GFBSglTF.MODID, name), GltfSkinnedGeometry.FORMAT);
        // Minecraft owns the program and closes it during its normal shader reload.
        event.registerShader(shader, loaded -> {
            try {
                if (GL20C.glGetProgrami(loaded.getId(), GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
                    throw new IllegalStateException(GL20C.glGetProgramInfoLog(loaded.getId()));
                }
                SHADERS[slot] = new DrawShader(loaded, slot != EYES);
            } catch (RuntimeException | LinkageError failure) {
                paletteLimit = 0;
                GFBSglTF.LOGGER.warn("GPU skinning shader {} failed to initialize. Using CPU skinning.", name, failure);
            }
        });
    }

    public static int maxPaletteJoints() {
        return SHADERS[SOLID] == null || SHADERS[CUTOUT] == null || SHADERS[EYES] == null ? 0 : paletteLimit;
    }

    static int maxCachedVertices() { return maxCachedVertices; }

    static ShaderInstance verificationShader() {
        return SHADERS[SOLID].shader;
    }

    static GltfSkinPaletteBuffer upload(SkinPalette matrices) {
        int limit = maxPaletteJoints();
        if (limit == 0 || matrices.matrices().length / 16 > limit) return null;
        try {
            if (palette == null) palette = new GltfSkinPaletteBuffer();
            palette.upload(matrices);
            return palette;
        } catch (RuntimeException | LinkageError failure) {
            paletteLimit = 0;
            clear();
            GFBSglTF.LOGGER.warn("GPU skin palette upload failed. Using CPU skinning.", failure);
            return null;
        }
    }

    static void clear() {
        GltfSkinPaletteBuffer old = palette;
        palette = null;
        if (old != null) old.close();
    }

    static void releaseCold(long frame) {
        if (palette != null && frame - palette.lastFrame > 180) clear();
    }

    static void draw(GltfSkinnedGeometry geometry, int skinTexture, boolean cachedDeformation, RenderType type,
                     GltfMaterial material, GltfGeometryPipeline.MaterialPass pass, Matrix4f model,
                     Matrix3f normal, int light, int overlay, float alpha, boolean mirrored) {
        boolean emissive = pass.kind() == GltfGeometryPipeline.PassKind.EMISSIVE;
        DrawShader drawShader = SHADERS[emissive || material.unlit() ? EYES
            : material.alphaMode() == AlphaMode.MASK ? CUTOUT : SOLID];
        ShaderInstance shader = drawShader.shader;
        int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int previousFace = mirrored ? GL11C.glGetInteger(GL11C.GL_FRONT_FACE) : GL11C.GL_CCW;
        type.setupRenderState();
        try {
            drawShader.configure(material, pass, emissive, cachedDeformation, model, normal, light, overlay, alpha);
            int previousPalette = GltfSkinPaletteBuffer.bindTexture(skinTexture);
            try {
                shader.apply();
                GL30C.glBindVertexArray(geometry.vao);
                if (mirrored) GL11C.glFrontFace(previousFace == GL11C.GL_CCW ? GL11C.GL_CW : GL11C.GL_CCW);
                geometry.draw();
                GltfRenderMetrics metrics = GltfRenderMetrics.active();
                if (metrics != null) metrics.gpuSkinnedDraws++;
            } finally {
                if (mirrored) GL11C.glFrontFace(previousFace);
                GL30C.glBindVertexArray(previousVao);
                GltfSkinPaletteBuffer.restore(previousPalette);
                shader.clear();
            }
        } finally {
            type.clearRenderState();
        }
    }

    private static final class DrawShader {
        final ShaderInstance shader;
        final Uniform model, normal, light, overlay, pass, materialColor, tint, texCoord, uvScaleOffset, uvRotation;
        final Uniform cached;

        DrawShader(ShaderInstance shader, boolean lit) {
            this.shader = shader;
            model = uniform("GltfModel");
            normal = uniform("GltfNormal");
            light = lit ? uniform("GltfLight") : null;
            overlay = lit ? uniform("GltfOverlay") : null;
            pass = uniform("GltfPass");
            materialColor = uniform("GltfMaterialColor");
            tint = uniform("GltfTint");
            texCoord = uniform("GltfTexCoord");
            uvScaleOffset = uniform("GltfUvScaleOffset");
            uvRotation = uniform("GltfUvRotation");
            cached = uniform("GltfCachedDeformation");
            uniform("GltfBones").set(GltfSkinPaletteBuffer.TEXTURE_UNIT);
        }

        private Uniform uniform(String name) {
            return Objects.requireNonNull(shader.getUniform(name), "Missing skinning uniform " + name);
        }

        void configure(GltfMaterial material, GltfGeometryPipeline.MaterialPass materialPass, boolean emissive,
                       boolean cachedDeformation,
                       Matrix4f modelMatrix, Matrix3f normalMatrix, int packedLight, int packedOverlay, float alpha) {
            shader.setSampler("Sampler0", RenderSystem.getShaderTexture(0));
            shader.MODEL_VIEW_MATRIX.set(RenderSystem.getModelViewMatrix());
            shader.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
            if (shader.INVERSE_VIEW_ROTATION_MATRIX != null) {
                shader.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
            }
            if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
            if (shader.FOG_START != null) shader.FOG_START.set(RenderSystem.getShaderFogStart());
            if (shader.FOG_END != null) shader.FOG_END.set(RenderSystem.getShaderFogEnd());
            if (shader.FOG_SHAPE != null) shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
            if (shader.FOG_COLOR != null) shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
            RenderSystem.setupShaderLights(shader);

            model.set(modelMatrix);
            normal.set(normalMatrix);
            if (light != null) {
                shader.setSampler("Sampler1", RenderSystem.getShaderTexture(1));
                shader.setSampler("Sampler2", RenderSystem.getShaderTexture(2));
                light.set(packedLight & 0xffff, packedLight >>> 16);
                overlay.set(packedOverlay & 0xffff, packedOverlay >>> 16);
            }
            cached.set(cachedDeformation ? 1 : 0);
            pass.set(emissive ? 2 : material.unlit() ? 1 : 0);
            if (emissive) {
                float strength = materialPass.colorScale() * alpha;
                materialColor.set(material.emissiveRed() * strength, material.emissiveGreen() * strength,
                    material.emissiveBlue() * strength, 0.0f);
            } else {
                materialColor.set(material.baseColorRed(), material.baseColorGreen(), material.baseColorBlue(),
                    material.alphaMode() == AlphaMode.OPAQUE ? 0.0f : 1.0f);
            }
            tint.set(materialPass.redMultiplier(), materialPass.greenMultiplier(), materialPass.blueMultiplier(), alpha);
            GltfTextureInfo uv = materialPass.textureInfo();
            texCoord.set(uv.texCoord());
            uvScaleOffset.set(uv.scaleU(), uv.scaleV(), uv.offsetU(), uv.offsetV());
            float rotation = uv.rotation();
            uvRotation.set(rotation == 0.0f ? 1.0f : (float) Math.cos(rotation),
                rotation == 0.0f ? 0.0f : (float) Math.sin(rotation));
        }
    }
}
