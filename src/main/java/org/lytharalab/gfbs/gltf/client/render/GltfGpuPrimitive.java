package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderTypes;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.mixin.RenderSystemAccessor;

/**
 * One immutable rigid primitive compiled into a resident GPU vertex/index buffer.
 *
 * <p>The geometry is authored in node-local space. Per-instance node animation is therefore only
 * a matrix change at draw time; vertices never come back through Java after this object is built.</p>
 */
final class GltfGpuPrimitive implements AutoCloseable {
    private static final Matrix4f IDENTITY4 = new Matrix4f();
    private static final Matrix3f IDENTITY3 = new Matrix3f();
    private static final int OVERLAY_ATTRIBUTE =
        DefaultVertexFormat.NEW_ENTITY.getElements().indexOf(DefaultVertexFormat.ELEMENT_UV1);
    private static final int LIGHT_ATTRIBUTE =
        DefaultVertexFormat.NEW_ENTITY.getElements().indexOf(DefaultVertexFormat.ELEMENT_UV2);
    private static final ThreadLocal<DrawScratch> DRAW_SCRATCH =
        ThreadLocal.withInitial(DrawScratch::new);

    private final VertexBuffer buffer;

    private GltfGpuPrimitive(VertexBuffer buffer) {
        this.buffer = buffer;
    }

    static GltfGpuPrimitive compile(GltfPrimitive primitive, GltfMaterial material,
                                    GltfGeometryPipeline.MaterialPass pass) {
        RenderSystem.assertOnRenderThread();
        if (!GltfGeometryPipeline.isTriangleMode(primitive.mode())) {
            throw new IllegalArgumentException("GPU rigid path currently requires triangle geometry");
        }

        long requestedBytes = Math.max(8192L, (long) primitive.indexCount() * 40L);
        // BufferBuilder grows as needed. Cap the eager allocation so a malformed/huge primitive
        // cannot force a multi-gigabyte temporary buffer before normal importer limits react.
        BufferBuilder builder = new BufferBuilder((int) Math.min(2L * 1024L * 1024L, requestedBytes));
        builder.begin(VertexFormat.Mode.TRIANGLES, GltfRenderTypes.REQUIRED_FORMAT);
        GltfGeometryPipeline.emit(
            builder, primitive, material, pass,
            IDENTITY4, IDENTITY3,
            null, 0, null,
            0, 0, 1.0f
        );

        VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try {
            vertexBuffer.bind();
            vertexBuffer.upload(builder.end());
            // upload() is allowed to touch binding state; bind explicitly before mutating the VAO.
            vertexBuffer.bind();
            // Overlay/light are per-instance values, not geometry. Keep their arrays disabled on
            // this VAO and feed them as constant integer vertex attributes at draw time. This is
            // what makes one resident mesh reusable by hundreds of differently-lit instances.
            if (OVERLAY_ATTRIBUTE >= 0) GL20C.glDisableVertexAttribArray(OVERLAY_ATTRIBUTE);
            if (LIGHT_ATTRIBUTE >= 0) GL20C.glDisableVertexAttribArray(LIGHT_ATTRIBUTE);
            VertexBuffer.unbind();
            return new GltfGpuPrimitive(vertexBuffer);
        } catch (RuntimeException | Error failure) {
            try {
                VertexBuffer.unbind();
                vertexBuffer.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    void draw(RenderType renderType, Matrix4f modelView, boolean transformDirectionalLights,
              int packedLight, int packedOverlay, float redScale, float greenScale,
              float blueScale, float alphaScale) {
        RenderSystem.assertOnRenderThread();
        DrawScratch scratch = DRAW_SCRATCH.get();
        Vector3f[] globalLights = null;
        if (transformDirectionalLights) {
            globalLights = RenderSystemAccessor.gfbsGltf$getShaderLightDirections();
            if (globalLights != null && globalLights.length >= 2
                && globalLights[0] != null && globalLights[1] != null) {
                scratch.savedLight0.set(globalLights[0]);
                scratch.savedLight1.set(globalLights[1]);
                scratch.inverseModel.set(modelView);
                float determinant = scratch.inverseModel.determinant();
                if (Float.isFinite(determinant) && Math.abs(determinant) > 1.0e-10f) {
                    scratch.inverseModel.invert();
                    scratch.localLight0.set(scratch.savedLight0);
                    scratch.localLight1.set(scratch.savedLight1);
                    scratch.inverseModel.transform(scratch.localLight0);
                    scratch.inverseModel.transform(scratch.localLight1);
                    // The streamed path writes inverse-transpose transformed normals. Moving that
                    // transform onto the shader light requires L_local = inverse(M) * L. Do not
                    // normalize here: non-uniform scale would otherwise change the dot product and
                    // make the resident path shade differently from the legacy CPU path.
                    RenderSystem.setShaderLights(scratch.localLight0, scratch.localLight1);
                    scratch.lightsChanged = true;
                }
            }
        }

        if (redScale != 1.0f || greenScale != 1.0f || blueScale != 1.0f
            || alphaScale != 1.0f) {
            float[] color = RenderSystem.getShaderColor();
            scratch.shaderRed = color[0];
            scratch.shaderGreen = color[1];
            scratch.shaderBlue = color[2];
            scratch.shaderAlpha = color[3];
            RenderSystem.setShaderColor(
                scratch.shaderRed * redScale,
                scratch.shaderGreen * greenScale,
                scratch.shaderBlue * blueScale,
                scratch.shaderAlpha * alphaScale
            );
            scratch.colorChanged = true;
        }

        boolean stateSetup = false;
        try {
            renderType.setupRenderState();
            stateSetup = true;
            ShaderInstance shader = RenderSystem.getShader();
            if (shader == null) return;
            buffer.bind();
            if (OVERLAY_ATTRIBUTE >= 0) {
                setIntegerVec2(OVERLAY_ATTRIBUTE, packedOverlay);
            }
            if (LIGHT_ATTRIBUTE >= 0) {
                setIntegerVec2(LIGHT_ATTRIBUTE, packedLight);
            }
            // Streamed Minecraft vertices are first transformed by the caller PoseStack and are
            // then multiplied by RenderSystem's global ModelViewMat when the batch is flushed.
            // Reproduce that exact composition for resident local-space geometry.
            scratch.composedModelView.set(RenderSystem.getModelViewMatrix()).mul(modelView);
            buffer.drawWithShader(
                scratch.composedModelView, RenderSystem.getProjectionMatrix(), shader
            );
        } finally {
            VertexBuffer.unbind();
            if (stateSetup) renderType.clearRenderState();
            if (scratch.lightsChanged) {
                RenderSystem.setShaderLights(scratch.savedLight0, scratch.savedLight1);
                scratch.lightsChanged = false;
            }
            if (scratch.colorChanged) {
                RenderSystem.setShaderColor(
                    scratch.shaderRed, scratch.shaderGreen, scratch.shaderBlue, scratch.shaderAlpha
                );
                scratch.colorChanged = false;
            }
        }
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        buffer.close();
    }

    /**
     * Supplies the two integer components used by Minecraft's UV1/UV2 attributes.
     *
     * <p>Desktop OpenGL defines the missing components of {@code glVertexAttribI2i} as
     * {@code (0, 1)}. Use the equivalent four-component entry point explicitly because GLES 3
     * exposes only the four-component integer constant setters. MobileGlues consequently maps
     * {@code glVertexAttribI4i} to GLES while its smaller signed-arity desktop shims may be
     * unavailable. Keeping this on the resident VAO path preserves per-instance light/overlay
     * values without falling back to streamed geometry.</p>
     */
    private static void setIntegerVec2(int attribute, int packedValue) {
        GL30C.glVertexAttribI4i(
            attribute,
            packedValue & 0xffff,
            packedValue >>> 16 & 0xffff,
            0,
            1
        );
    }

    private static final class DrawScratch {
        final Matrix3f inverseModel = new Matrix3f();
        final Matrix4f composedModelView = new Matrix4f();
        final Vector3f savedLight0 = new Vector3f();
        final Vector3f savedLight1 = new Vector3f();
        final Vector3f localLight0 = new Vector3f();
        final Vector3f localLight1 = new Vector3f();
        float shaderRed;
        float shaderGreen;
        float shaderBlue;
        float shaderAlpha;
        boolean lightsChanged;
        boolean colorChanged;
    }
}
