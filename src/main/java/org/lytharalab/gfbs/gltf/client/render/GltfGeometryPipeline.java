package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitiveAccess;
import org.lytharalab.gfbs.gltf.api.model.GltfTextureInfo;
import org.lytharalab.gfbs.gltf.api.model.PrimitiveMode;

/** Allocation-conscious vertex emission shared by the legacy and GPU-compile paths. */
final class GltfGeometryPipeline {
    private static final float[] LINE_NORMAL = {0.0f, 1.0f, 0.0f};
    private static final ThreadLocal<EmitContext> CONTEXT =
        ThreadLocal.withInitial(EmitContext::new);

    private GltfGeometryPipeline() {}

    static void emit(VertexConsumer consumer, GltfPrimitive primitive, GltfMaterial material,
                     MaterialPass pass, Matrix4f model, Matrix3f normalMatrix,
                     float[] skin, int jointCount, float[] morphWeights,
                     int packedLight, int packedOverlay, float alpha) {
        float[] positions = GltfPrimitiveAccess.positions(primitive);
        float[] normals = GltfPrimitiveAccess.normals(primitive);
        if (GltfVertexTransforms.hasActiveMorph(primitive, morphWeights)) {
            positions = positions.clone();
            if (normals != null) normals = normals.clone();
            GltfVertexTransforms.applyMorphs(
                primitive, morphWeights, positions, normals, null
            );
        }
        float[] uv0 = GltfPrimitiveAccess.texCoords0(primitive);
        float[] uv1 = GltfPrimitiveAccess.texCoords1(primitive);
        float[] colors = GltfPrimitiveAccess.colors(primitive);
        int[] joints = GltfPrimitiveAccess.joints(primitive);
        float[] weights = GltfPrimitiveAccess.weights(primitive);
        int[] indices = GltfPrimitiveAccess.indices(primitive);
        GltfTextureInfo textureInfo = pass.textureInfo();
        float cosine = textureInfo.rotation() == 0.0f ? 1.0f : (float) Math.cos(textureInfo.rotation());
        float sine = textureInfo.rotation() == 0.0f ? 0.0f : (float) Math.sin(textureInfo.rotation());
        EmitContext ctx = CONTEXT.get();
        ctx.set(
            consumer, primitive, material, pass, model, normalMatrix, positions, normals,
            uv0, uv1, colors, joints, weights, skin, jointCount, packedLight, packedOverlay,
            alpha, cosine, sine
        );
        try {
            if (!isTriangleMode(primitive.mode())) {
                emitLines(ctx, primitive.mode(), indices);
            } else {
                emitTriangles(ctx, primitive.mode(), indices);
            }
        } finally {
            // Do not let the render thread's reusable context pin an invalidated asset after a
            // resource reload. Primitive arrays remain allocation-free during the call itself.
            ctx.clear();
        }
    }

    private static void emitTriangles(EmitContext ctx, PrimitiveMode mode, int[] indices) {
        int count = indices == null ? ctx.primitive.vertexCount() : indices.length;
        switch (mode) {
            case TRIANGLES -> {
                for (int i = 0; i + 2 < count; i += 3) {
                    emitTriangle(ctx, index(indices, i), index(indices, i + 1), index(indices, i + 2));
                }
            }
            case TRIANGLE_STRIP -> {
                for (int i = 2; i < count; i++) {
                    int a = index(indices, i - 2);
                    int b = index(indices, i - 1);
                    int c = index(indices, i);
                    if ((i & 1) != 0) {
                        int swap = a; a = b; b = swap;
                    }
                    emitTriangle(ctx, a, b, c);
                }
            }
            case TRIANGLE_FAN -> {
                int first = index(indices, 0);
                for (int i = 2; i < count; i++) {
                    emitTriangle(ctx, first, index(indices, i - 1), index(indices, i));
                }
            }
            default -> throw new IllegalStateException("Unexpected triangle mode " + mode);
        }
    }

    private static void emitLines(EmitContext ctx, PrimitiveMode mode, int[] indices) {
        int count = indices == null ? ctx.primitive.vertexCount() : indices.length;
        switch (mode) {
            case POINTS -> {
                for (int i = 0; i < count; i++) {
                    int vertex = index(indices, i);
                    emitLine(ctx, vertex, vertex);
                }
            }
            case LINES -> {
                for (int i = 0; i + 1 < count; i += 2) {
                    emitLine(ctx, index(indices, i), index(indices, i + 1));
                }
            }
            case LINE_STRIP, LINE_LOOP -> {
                for (int i = 1; i < count; i++) {
                    emitLine(ctx, index(indices, i - 1), index(indices, i));
                }
                if (mode == PrimitiveMode.LINE_LOOP && count > 1) {
                    emitLine(ctx, index(indices, count - 1), index(indices, 0));
                }
            }
            default -> throw new IllegalStateException("Unexpected line mode " + mode);
        }
    }

    private static int index(int[] indices, int element) {
        return indices == null ? element : indices[element];
    }

    private static void emitLine(EmitContext ctx, int first, int second) {
        emitVertex(ctx, first, LINE_NORMAL);
        emitVertex(ctx, second, LINE_NORMAL);
    }

    private static void emitTriangle(EmitContext ctx, int a, int b, int c) {
        float[] generated = null;
        if (ctx.normals == null) {
            GltfVertexTransforms.faceNormal(ctx.positions, a, b, c, ctx.faceNormal);
            generated = ctx.faceNormal;
        }
        emitVertex(ctx, a, generated);
        emitVertex(ctx, b, generated);
        emitVertex(ctx, c, generated);
    }

    private static void emitVertex(EmitContext ctx, int vertex, float[] generatedNormal) {
        int position = vertex * 3;
        float x = ctx.positions[position];
        float y = ctx.positions[position + 1];
        float z = ctx.positions[position + 2];
        float nx = ctx.normals == null ? generatedNormal[0] : ctx.normals[position];
        float ny = ctx.normals == null ? generatedNormal[1] : ctx.normals[position + 1];
        float nz = ctx.normals == null ? generatedNormal[2] : ctx.normals[position + 2];
        if (ctx.skin != null && ctx.joints != null && ctx.weights != null) {
            GltfVertexTransforms.skinVertex(
                vertex, x, y, z, nx, ny, nz, ctx.joints, ctx.weights,
                ctx.skin, ctx.jointCount, ctx.scratch
            );
            x = ctx.scratch[0]; y = ctx.scratch[1]; z = ctx.scratch[2];
            nx = ctx.scratch[3]; ny = ctx.scratch[4]; nz = ctx.scratch[5];
        }

        int colorComponents = ctx.colors == null ? 0
            : ctx.colors.length / Math.max(1, ctx.positions.length / 3);
        float red;
        float green;
        float blue;
        float outputAlpha;
        if (ctx.pass.kind() == PassKind.BASE) {
            red = (ctx.colors == null ? 1.0f : ctx.colors[vertex * colorComponents])
                * ctx.material.baseColorRed() * ctx.pass.redMultiplier();
            green = (ctx.colors == null ? 1.0f : ctx.colors[vertex * colorComponents + 1])
                * ctx.material.baseColorGreen() * ctx.pass.greenMultiplier();
            blue = (ctx.colors == null ? 1.0f : ctx.colors[vertex * colorComponents + 2])
                * ctx.material.baseColorBlue() * ctx.pass.blueMultiplier();
            float vertexAlpha = ctx.colors == null || colorComponents < 4
                ? 1.0f : ctx.colors[vertex * colorComponents + 3];
            float factorAlpha = ctx.material.alphaMode() == AlphaMode.MASK
                ? 1.0f : ctx.material.baseColorAlpha();
            outputAlpha = vertexAlpha * factorAlpha * ctx.alpha;
        } else {
            float strength = ctx.pass.colorScale() * ctx.alpha;
            red = ctx.material.emissiveRed() * strength * ctx.pass.redMultiplier();
            green = ctx.material.emissiveGreen() * strength * ctx.pass.greenMultiplier();
            blue = ctx.material.emissiveBlue() * strength * ctx.pass.blueMultiplier();
            outputAlpha = 1.0f;
        }

        GltfTextureInfo textureInfo = ctx.pass.textureInfo();
        float[] uv = textureInfo.texCoord() == 1 ? ctx.uv1 : ctx.uv0;
        float u = uv == null ? 0.0f : uv[vertex * 2];
        float v = uv == null ? 0.0f : uv[vertex * 2 + 1];
        float scaledU = u * textureInfo.scaleU();
        float scaledV = v * textureInfo.scaleV();
        u = textureInfo.offsetU() + ctx.cosine * scaledU - ctx.sine * scaledV;
        v = textureInfo.offsetV() + ctx.sine * scaledU + ctx.cosine * scaledV;

        // Avoid VertexConsumer's Matrix overloads here: vanilla's convenience implementations
        // materialize temporary JOML vectors. This path can still handle deformed geometry, so
        // perform the tiny affine transforms as scalars and keep it allocation-free per vertex.
        float tx = ctx.model.m00() * x + ctx.model.m10() * y
            + ctx.model.m20() * z + ctx.model.m30();
        float ty = ctx.model.m01() * x + ctx.model.m11() * y
            + ctx.model.m21() * z + ctx.model.m31();
        float tz = ctx.model.m02() * x + ctx.model.m12() * y
            + ctx.model.m22() * z + ctx.model.m32();
        float tnx = ctx.normalMatrix.m00() * nx + ctx.normalMatrix.m10() * ny
            + ctx.normalMatrix.m20() * nz;
        float tny = ctx.normalMatrix.m01() * nx + ctx.normalMatrix.m11() * ny
            + ctx.normalMatrix.m21() * nz;
        float tnz = ctx.normalMatrix.m02() * nx + ctx.normalMatrix.m12() * ny
            + ctx.normalMatrix.m22() * nz;

        ctx.consumer.vertex(tx, ty, tz)
            .color(channel(red), channel(green), channel(blue), channel(outputAlpha))
            .uv(u, v)
            .overlayCoords(ctx.packedOverlay)
            .uv2(ctx.packedLight)
            .normal(tnx, tny, tnz)
            .endVertex();
    }

    static boolean isTriangleMode(PrimitiveMode mode) {
        return mode == PrimitiveMode.TRIANGLES
            || mode == PrimitiveMode.TRIANGLE_STRIP
            || mode == PrimitiveMode.TRIANGLE_FAN;
    }

    static boolean hasVisibleEmission(GltfMaterial material) {
        return material.emissiveStrength() > 0.0f
            && (material.emissiveRed() > 0.0f
                || material.emissiveGreen() > 0.0f
                || material.emissiveBlue() > 0.0f);
    }

    static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }

    enum PassKind { BASE, EMISSIVE }

    record MaterialPass(PassKind kind, GltfTextureInfo textureInfo, float colorScale,
                        float redMultiplier, float greenMultiplier, float blueMultiplier) {
        static MaterialPass base(GltfMaterial material, float red, float green, float blue) {
            return new MaterialPass(PassKind.BASE, material.baseColorTextureInfo(), 1.0f,
                red, green, blue);
        }

        static MaterialPass emissive(GltfMaterial material, float red, float green, float blue) {
            // CPU fallback has only 8-bit vertex color, so high strengths saturate rather than
            // re-submitting the complete mesh up to sixteen times.
            return new MaterialPass(PassKind.EMISSIVE, material.emissiveTextureInfo(),
                material.emissiveStrength(), red, green, blue);
        }

        static MaterialPass emissiveGpu(GltfMaterial material) {
            // Resident geometry keeps emissive strength out of the immutable vertex buffer; the
            // draw path supplies it through ColorModulator, preserving HDR-ish strengths in one draw.
            return new MaterialPass(PassKind.EMISSIVE, material.emissiveTextureInfo(),
                1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    private static final class EmitContext {
        VertexConsumer consumer;
        GltfPrimitive primitive;
        GltfMaterial material;
        MaterialPass pass;
        Matrix4f model;
        Matrix3f normalMatrix;
        float[] positions;
        float[] normals;
        float[] uv0;
        float[] uv1;
        float[] colors;
        int[] joints;
        float[] weights;
        float[] skin;
        int jointCount;
        int packedLight;
        int packedOverlay;
        float alpha;
        final float[] scratch = new float[6];
        final float[] faceNormal = new float[3];
        float cosine;
        float sine;

        void set(
            VertexConsumer consumer, GltfPrimitive primitive, GltfMaterial material,
            MaterialPass pass, Matrix4f model, Matrix3f normalMatrix, float[] positions,
            float[] normals, float[] uv0, float[] uv1, float[] colors, int[] joints,
            float[] weights, float[] skin, int jointCount, int packedLight, int packedOverlay,
            float alpha, float cosine, float sine
        ) {
            this.consumer = consumer;
            this.primitive = primitive;
            this.material = material;
            this.pass = pass;
            this.model = model;
            this.normalMatrix = normalMatrix;
            this.positions = positions;
            this.normals = normals;
            this.uv0 = uv0;
            this.uv1 = uv1;
            this.colors = colors;
            this.joints = joints;
            this.weights = weights;
            this.skin = skin;
            this.jointCount = jointCount;
            this.packedLight = packedLight;
            this.packedOverlay = packedOverlay;
            this.alpha = alpha;
            this.cosine = cosine;
            this.sine = sine;
        }

        void clear() {
            consumer = null;
            primitive = null;
            material = null;
            pass = null;
            model = null;
            normalMatrix = null;
            positions = normals = uv0 = uv1 = colors = weights = skin = null;
            joints = null;
            jointCount = packedLight = packedOverlay = 0;
            alpha = 1.0f;
            cosine = 1.0f;
            sine = 0.0f;
        }
    }
}
