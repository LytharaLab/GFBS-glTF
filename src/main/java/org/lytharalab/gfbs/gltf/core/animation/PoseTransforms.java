package org.lytharalab.gfbs.gltf.core.animation;

import org.lytharalab.gfbs.gltf.api.animation.ModelPose;
import org.lytharalab.gfbs.gltf.api.animation.NodePose;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;
import org.lytharalab.gfbs.gltf.api.model.GltfSkinAccess;
import org.joml.Matrix4f;

import java.util.Arrays;

/** Column-major transform evaluation shared by rigid and skinned rendering. */
public final class PoseTransforms {
    private static final ThreadLocal<SkinScratch> SKIN_SCRATCH =
        ThreadLocal.withInitial(SkinScratch::new);

    private PoseTransforms() {
    }

    public static float[] computeWorldMatrices(ModelPose pose) {
        GltfAsset asset = pose.asset();
        int nodeCount = asset.nodes().size();
        float[] result = new float[Math.multiplyExact(nodeCount, 16)];
        byte[] state = new byte[nodeCount];
        int[] path = new int[nodeCount];
        for (int start = 0; start < nodeCount; start++) {
            if (state[start] == 2) continue;
            int length = 0;
            int current = start;
            while (current >= 0 && state[current] != 2) {
                if (state[current] == 1) throw new IllegalArgumentException("Cycle in glTF node hierarchy");
                state[current] = 1;
                path[length++] = current;
                current = asset.nodes().get(current).parent();
            }
            for (int pathIndex = length - 1; pathIndex >= 0; pathIndex--) {
                int nodeId = path[pathIndex];
                GltfNode node = asset.nodes().get(nodeId);
                float[] world = localMatrix(node, pose.node(nodeId));
                if (node.parent() >= 0) world = multiply(slice(result, node.parent()), world);
                System.arraycopy(world, 0, result, nodeId * 16, 16);
                state[nodeId] = 2;
            }
        }
        return result;
    }

    public static float[] computeSkinPalette(GltfSkin skin, int meshNode, float[] worldMatrices) {
        int joints = GltfSkinAccess.joints(skin).length;
        float[] palette = new float[Math.multiplyExact(joints, 16)];
        computeSkinPaletteInto(skin, meshNode, worldMatrices, palette);
        return palette;
    }

    /** Allocation-free skin palette writer used by per-instance renderer caches. */
    public static void computeSkinPaletteInto(GltfSkin skin, int meshNode, float[] worldMatrices,
                                              float[] output) {
        int[] joints = GltfSkinAccess.joints(skin);
        float[] inverseBind = GltfSkinAccess.inverseBindMatrices(skin);
        if (meshNode < 0 || meshNode * 16 + 16 > worldMatrices.length) {
            throw new IndexOutOfBoundsException("matrix index " + meshNode);
        }
        if (output.length < Math.multiplyExact(joints.length, 16)) {
            throw new IllegalArgumentException("Skin palette output is too small");
        }

        SkinScratch scratch = SKIN_SCRATCH.get();
        scratch.inverseMesh.set(worldMatrices, meshNode * 16);
        if (Math.abs(scratch.inverseMesh.determinant()) > 1.0e-10f) {
            scratch.inverseMesh.invert();
        } else {
            // A zero-scale animation can make the mesh transform singular. The mesh itself is
            // collapsed in this state, so an identity inverse is a safe, non-crashing fallback.
            scratch.inverseMesh.identity();
        }
        for (int i = 0; i < joints.length; i++) {
            int jointOffset = Math.multiplyExact(joints[i], 16);
            if (jointOffset < 0 || jointOffset + 16 > worldMatrices.length) {
                throw new IndexOutOfBoundsException("matrix index " + joints[i]);
            }
            scratch.joint.set(worldMatrices, jointOffset);
            scratch.bind.set(inverseBind, i * 16);
            scratch.result.set(scratch.inverseMesh).mul(scratch.joint).mul(scratch.bind);
            scratch.result.get(output, i * 16);
        }
    }

    public static float[] localMatrix(GltfNode node, NodePose pose) {
        float[] fixed = node.matrix();
        if (fixed != null) return fixed;
        return trsMatrix(pose.translation(), pose.rotation(), pose.scale());
    }

    /** Builds a validated column-major local matrix from glTF translation/rotation/scale values. */
    public static float[] trsMatrix(float[] translation, float[] rotation, float[] scale) {
        float[] result = new float[16];
        trsMatrixInto(translation, rotation, scale, result, 0);
        return result;
    }

    /** Allocation-free TRS matrix writer used by realtime instance transform caches. */
    public static void trsMatrixInto(float[] translation, float[] rotation, float[] scale,
                                     float[] output, int offset) {
        if (translation == null || translation.length != 3) {
            throw new IllegalArgumentException("Translation requires three components");
        }
        if (rotation == null || rotation.length != 4) {
            throw new IllegalArgumentException("Rotation requires four components");
        }
        if (scale == null || scale.length != 3) {
            throw new IllegalArgumentException("Scale requires three components");
        }
        if (output == null || offset < 0 || offset + 16 > output.length) {
            throw new IllegalArgumentException("Output matrix range is invalid");
        }
        for (float value : translation) checked(value);
        for (float value : rotation) checked(value);
        for (float value : scale) checked(value);
        float x = rotation[0], y = rotation[1], z = rotation[2], w = rotation[3];
        float sx = scale[0], sy = scale[1], sz = scale[2];
        for (int i = 0; i < 16; i++) output[offset + i] = 0.0f;
        output[offset] = checked((1.0 - 2.0 * ((double) y * y + (double) z * z)) * sx);
        output[offset + 1] = checked(2.0 * ((double) x * y + (double) z * w) * sx);
        output[offset + 2] = checked(2.0 * ((double) x * z - (double) y * w) * sx);
        output[offset + 4] = checked(2.0 * ((double) x * y - (double) z * w) * sy);
        output[offset + 5] = checked((1.0 - 2.0 * ((double) x * x + (double) z * z)) * sy);
        output[offset + 6] = checked(2.0 * ((double) y * z + (double) x * w) * sy);
        output[offset + 8] = checked(2.0 * ((double) x * z + (double) y * w) * sz);
        output[offset + 9] = checked(2.0 * ((double) y * z - (double) x * w) * sz);
        output[offset + 10] = checked((1.0 - 2.0 * ((double) x * x + (double) y * y)) * sz);
        output[offset + 12] = translation[0];
        output[offset + 13] = translation[1];
        output[offset + 14] = translation[2];
        output[offset + 15] = 1.0f;
    }

    public static float[] multiply(float[] a, float[] b) {
        if (a.length != 16 || b.length != 16) throw new IllegalArgumentException("Expected 4x4 matrices");
        float[] result = new float[16];
        multiplyInto(a, 0, b, 0, result, 0);
        return result;
    }

    /** Allocation-free column-major matrix multiplication. Output must not overlap either input. */
    public static void multiplyInto(float[] a, int aOffset, float[] b, int bOffset,
                                    float[] output, int outputOffset) {
        if (aOffset < 0 || bOffset < 0 || outputOffset < 0
            || aOffset + 16 > a.length || bOffset + 16 > b.length
            || outputOffset + 16 > output.length) {
            throw new IllegalArgumentException("Expected complete 4x4 matrix ranges");
        }
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                double value = 0.0;
                for (int k = 0; k < 4; k++) {
                    value += (double) a[aOffset + k * 4 + row]
                        * b[bOffset + column * 4 + k];
                }
                output[outputOffset + column * 4 + row] = checked(value);
            }
        }
    }

    private static float checked(double value) {
        if (!Double.isFinite(value) || value > Float.MAX_VALUE || value < -Float.MAX_VALUE) {
            throw new IllegalArgumentException("glTF transform overflow");
        }
        return (float) value;
    }

    public static float[] invert(float[] matrix) {
        if (matrix.length != 16) throw new IllegalArgumentException("Expected a 4x4 matrix");
        double[][] work = new double[4][8];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) work[row][column] = matrix[column * 4 + row];
            work[row][row + 4] = 1;
        }
        for (int pivot = 0; pivot < 4; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < 4; row++) if (Math.abs(work[row][pivot]) > Math.abs(work[best][pivot])) best = row;
            double pivotValue = work[best][pivot];
            if (!Double.isFinite(pivotValue) || pivotValue == 0.0d) {
                throw new IllegalArgumentException("Matrix is singular");
            }
            double[] swap = work[pivot]; work[pivot] = work[best]; work[best] = swap;
            double divisor = work[pivot][pivot];
            for (int column = 0; column < 8; column++) work[pivot][column] /= divisor;
            for (int row = 0; row < 4; row++) {
                if (row == pivot) continue;
                double factor = work[row][pivot];
                for (int column = 0; column < 8; column++) work[row][column] -= factor * work[pivot][column];
            }
        }
        float[] result = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                result[column * 4 + row] = checked(work[row][column + 4]);
            }
        }
        return result;
    }

    private static float[] slice(float[] matrices, int index) {
        int offset = Math.multiplyExact(index, 16);
        if (index < 0 || offset + 16 > matrices.length) throw new IndexOutOfBoundsException("matrix index " + index);
        return Arrays.copyOfRange(matrices, offset, offset + 16);
    }

    private static float[] identity() {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static final class SkinScratch {
        final Matrix4f inverseMesh = new Matrix4f();
        final Matrix4f joint = new Matrix4f();
        final Matrix4f bind = new Matrix4f();
        final Matrix4f result = new Matrix4f();
    }
}
