package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryUtil;
import org.lytharalab.gfbs.gltf.GFBSglTF;
import org.lytharalab.gfbs.gltf.api.animation.AnimationPoseAccess;
import org.lytharalab.gfbs.gltf.api.client.GltfInstance;
import org.lytharalab.gfbs.gltf.api.client.GltfSkinningVerification;
import org.lytharalab.gfbs.gltf.api.client.node.GltfPrimitiveKey;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitiveAccess;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;

import java.nio.FloatBuffer;
import java.util.Objects;
import java.util.Optional;

import static org.lytharalab.gfbs.gltf.api.client.GltfSkinningVerification.NORMAL_ABSOLUTE_TOLERANCE;
import static org.lytharalab.gfbs.gltf.api.client.GltfSkinningVerification.POSITION_ABSOLUTE_TOLERANCE;
import static org.lytharalab.gfbs.gltf.api.client.GltfSkinningVerification.POSITION_RELATIVE_TOLERANCE;

/** Captures the actual skinning vertex program before rasterization. */
public final class GltfSkinningVerifier {
    private static boolean reportedFailure;

    private GltfSkinningVerifier() {}

    public static Optional<GltfSkinningVerification> verify(GltfInstance instance, GltfPrimitiveKey key, int sampleLimit) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(key, "key");
        if (sampleLimit < 1 || sampleLimit > GltfSkinningVerification.MAX_SAMPLES) {
            throw new IllegalArgumentException("Skinning sample limit must be between 1 and "
                + GltfSkinningVerification.MAX_SAMPLES);
        }

        var asset = instance.asset();
        GltfNode node = asset.nodes().get(key.nodeIndex());
        if (!containsMesh(node, key.meshIndex())) throw new IllegalArgumentException("Mesh does not belong to the node");
        var mesh = asset.meshes().get(key.meshIndex());
        GltfPrimitive primitive = mesh.primitives().get(key.primitiveIndex());
        int maxJoints = GltfGpuSkinning.maxPaletteJoints();
        if (node.skin() < 0 || !GltfGeometryPipeline.isTriangleMode(primitive.mode()) || maxJoints == 0
            || OculusCompat.shadersEnabled() || OculusCompat.shadowPass()
            || GltfVertexTransforms.hasActiveMorph(primitive, primitive.morphTargets().isEmpty() ? null
                : instance.nodes().resolveMorphWeightsView(key.nodeIndex(), mesh, AnimationPoseAccess.pose(instance.animations())))) {
            return Optional.empty();
        }
        if (!GltfSkinFeedback.idle()
            || GL15C.glGetQueryi(GltfSkinFeedback.PRIMITIVES_WRITTEN, GL15C.GL_CURRENT_QUERY) != 0) return Optional.empty();

        GltfSkinnedGeometry geometry = GltfGpuCache.getInstance().get(asset).skinnedGeometry(primitive, maxJoints);
        if (geometry == null) return Optional.empty();
        GltfSkin skin = asset.skins().get(node.skin());
        float[] world = instance.nodes().computeWorldMatricesView(instance.animations());
        GltfSkinPaletteBuffer palette = GltfGpuSkinning.upload(
            GltfRenderMetrics.palette(instance.nodes(), skin, key.nodeIndex(), world, geometry.jointSlots));
        if (palette == null) return Optional.empty();
        float[] cpuPalette = instance.nodes().computeSkinPaletteView(skin, key.nodeIndex(), world);
        try {
            return Optional.of(capture(geometry, palette, primitive, cpuPalette, skin.jointCount(), sampleLimit));
        } catch (RuntimeException | LinkageError failure) {
            if (!reportedFailure) {
                reportedFailure = true;
                GFBSglTF.LOGGER.warn("GPU skinning readback failed", failure);
            }
            return Optional.empty();
        }
    }

    private static boolean containsMesh(GltfNode node, int meshIndex) {
        for (int i = 0; i < node.meshCount(); i++) if (node.mesh(i) == meshIndex) return true;
        return false;
    }

    private static GltfSkinningVerification capture(GltfSkinnedGeometry geometry, GltfSkinPaletteBuffer palette,
                                                     GltfPrimitive primitive, float[] cpuPalette, int jointCount,
                                                     int sampleLimit) {
        int vertexCount = geometry.vertexCount;
        int count = Math.min(vertexCount, sampleLimit);
        int program = GltfSkinFeedback.link();
        int feedback = 0, query = 0;
        FloatBuffer values = null;
        try {
            feedback = GL15C.glGenBuffers();
            query = GL15C.glGenQueries();
            if (feedback == 0 || query == 0) {
                throw new IllegalStateException("Could not allocate the skinning readback");
            }
            int groups = count == vertexCount ? 1 : Math.min(16, count);
            int[] ranges = new int[groups * 2];
            int[] samples = new int[count];
            for (int group = 0, cursor = 0; group < groups; group++) {
                int length = count * (group + 1) / groups - cursor;
                int start = groups == 1 ? 0 : cursor + (int) ((long) group * (vertexCount - count) / (groups - 1));
                ranges[group * 2] = start;
                ranges[group * 2 + 1] = length;
                for (int i = 0; i < length; i++) samples[cursor++] = start + i;
            }
            values = MemoryUtil.memAllocFloat(count * GltfSkinFeedback.FLOATS_PER_VERTEX);
            GltfSkinFeedback.capture(geometry, palette.texture(), program, feedback,
                (long) values.remaining() * Float.BYTES, query, ranges, values);
            return compare(primitive, cpuPalette, jointCount, samples, values);
        } finally {
            MemoryUtil.memFree(values);
            GL15C.glDeleteQueries(query);
            GL15C.glDeleteBuffers(feedback);
            GL20C.glDeleteProgram(program);
        }
    }

    private static GltfSkinningVerification compare(GltfPrimitive primitive, float[] palette, int jointCount,
                                                     int[] samples, FloatBuffer gpu) {
        float[] positions = GltfPrimitiveAccess.positions(primitive);
        float[] normals = GltfPrimitiveAccess.normals(primitive);
        int[] joints = GltfPrimitiveAccess.joints(primitive);
        float[] weights = GltfPrimitiveAccess.weights(primitive);
        float[] face = new float[3];
        float[] cpu = new float[6];
        float maxPositionError = 0.0f, maxNormalError = 0.0f;
        int mismatches = 0, firstMismatch = -1;
        for (int i = 0; i < samples.length; i++) {
            int residentVertex = samples[i];
            int source = residentVertex;
            float[] normal = normals;
            int n = source * 3;
            if (normals == null) {
                int triangle = residentVertex / 3;
                source = GltfMaterialBaker.vertex(primitive, triangle, residentVertex % 3);
                GltfSkinnedGeometry.faceNormal(primitive, triangle, face);
                normal = face;
                n = 0;
            }
            int p = source * 3;
            GltfVertexTransforms.skinVertex(source, positions[p], positions[p + 1], positions[p + 2],
                normal[n], normal[n + 1], normal[n + 2], joints, weights, palette, jointCount, cpu);
            boolean mismatch = false;
            for (int c = 0; c < cpu.length; c++) {
                int offset = i * GltfSkinFeedback.FLOATS_PER_VERTEX + (c < 3 ? c : c + 1);
                float error = Math.abs(cpu[c] - gpu.get(offset));
                if (!Float.isFinite(error)) error = Float.POSITIVE_INFINITY;
                if (c < 3) {
                    maxPositionError = Math.max(maxPositionError, error);
                    mismatch |= !Float.isFinite(error)
                        || error > POSITION_ABSOLUTE_TOLERANCE + Math.abs(cpu[c]) * POSITION_RELATIVE_TOLERANCE;
                } else {
                    maxNormalError = Math.max(maxNormalError, error);
                    mismatch |= error > NORMAL_ABSOLUTE_TOLERANCE;
                }
            }
            if (mismatch) {
                if (mismatches++ == 0) firstMismatch = residentVertex;
            }
        }
        return new GltfSkinningVerification(samples.length, mismatches, maxPositionError, maxNormalError, firstMismatch);
    }
}
