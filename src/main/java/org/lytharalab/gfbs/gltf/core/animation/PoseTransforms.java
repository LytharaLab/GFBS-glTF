package org.lytharalab.gfbs.gltf.core.animation;

import org.lytharalab.gfbs.gltf.api.animation.ModelPose;
import org.lytharalab.gfbs.gltf.api.animation.NodePose;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;

import java.util.Arrays;

/** Column-major transform evaluation shared by rigid and skinned rendering. */
public final class PoseTransforms {
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
        float[] meshWorld = slice(worldMatrices, meshNode);
        float[] inverseMesh;
        try {
            inverseMesh = invert(meshWorld);
        } catch (IllegalArgumentException singular) {
            // A zero-scale animation can make the mesh transform singular. The mesh itself is
            // collapsed in this state, so an identity inverse is a safe, non-crashing fallback.
            inverseMesh = identity();
        }
        int[] joints = skin.joints();
        float[] inverseBind = skin.inverseBindMatrices();
        float[] palette = new float[joints.length * 16];
        for (int i = 0; i < joints.length; i++) {
            float[] joint = slice(worldMatrices, joints[i]);
            float[] bind = Arrays.copyOfRange(inverseBind, i * 16, i * 16 + 16);
            float[] matrix = multiply(inverseMesh, multiply(joint, bind));
            System.arraycopy(matrix, 0, palette, i * 16, 16);
        }
        return palette;
    }

    public static float[] localMatrix(GltfNode node, NodePose pose) {
        float[] fixed = node.matrix();
        if (fixed != null) return fixed;
        float[] rotation = pose.rotation();
        float[] scale = pose.scale();
        float[] translation = pose.translation();
        float x = rotation[0], y = rotation[1], z = rotation[2], w = rotation[3];
        float sx = scale[0], sy = scale[1], sz = scale[2];
        float[] m = new float[16];
        m[0] = checked((1.0 - 2.0 * ((double) y * y + (double) z * z)) * sx);
        m[1] = checked(2.0 * ((double) x * y + (double) z * w) * sx);
        m[2] = checked(2.0 * ((double) x * z - (double) y * w) * sx);
        m[4] = checked(2.0 * ((double) x * y - (double) z * w) * sy);
        m[5] = checked((1.0 - 2.0 * ((double) x * x + (double) z * z)) * sy);
        m[6] = checked(2.0 * ((double) y * z + (double) x * w) * sy);
        m[8] = checked(2.0 * ((double) x * z + (double) y * w) * sz);
        m[9] = checked(2.0 * ((double) y * z - (double) x * w) * sz);
        m[10] = checked((1.0 - 2.0 * ((double) x * x + (double) y * y)) * sz);
        m[12] = translation[0]; m[13] = translation[1]; m[14] = translation[2]; m[15] = 1;
        return m;
    }

    public static float[] multiply(float[] a, float[] b) {
        if (a.length != 16 || b.length != 16) throw new IllegalArgumentException("Expected 4x4 matrices");
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                double value = 0;
                for (int k = 0; k < 4; k++) value += (double) a[k * 4 + row] * b[column * 4 + k];
                result[column * 4 + row] = checked(value);
            }
        }
        return result;
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
}
