package org.lytharalab.gfbs.gltf.api.model;

import java.util.Objects;

public record GltfSkin(String name, int skeletonRoot, int[] joints, float[] inverseBindMatrices) {
    /** Conservative GLSL 1.50 uniform-array limit that remains valid on minimum-spec OpenGL 3.2 hardware. */
    public static final int MAX_JOINTS = 48;
    public GltfSkin {
        name = name == null ? "" : name;
        joints = Objects.requireNonNull(joints, "joints").clone();
        inverseBindMatrices = Objects.requireNonNull(inverseBindMatrices, "inverseBindMatrices").clone();
        if (skeletonRoot < -1) throw new IllegalArgumentException("Invalid skeleton root");
        if (joints.length == 0) throw new IllegalArgumentException("A skin must contain at least one joint");
        java.util.HashSet<Integer> uniqueJoints = new java.util.HashSet<>();
        for (int joint : joints) {
            if (joint < 0) throw new IllegalArgumentException("Joint indices must be non-negative");
            if (!uniqueJoints.add(joint)) throw new IllegalArgumentException("A skin cannot contain duplicate joints");
        }
        if (joints.length > MAX_JOINTS) {
            throw new IllegalArgumentException("GFBS:glTF supports at most " + MAX_JOINTS + " joints per skin");
        }
        int expectedMatrices;
        try {
            expectedMatrices = Math.multiplyExact(joints.length, 16);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Skin is too large", overflow);
        }
        if (inverseBindMatrices.length != expectedMatrices) {
            throw new IllegalArgumentException("Inverse bind matrix count does not match joints");
        }
        for (float value : inverseBindMatrices) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Inverse bind matrix contains a non-finite value");
        }
    }

    @Override
    public int[] joints() { return joints.clone(); }

    @Override
    public float[] inverseBindMatrices() { return inverseBindMatrices.clone(); }
}
