package org.lytharalab.gfbs.gltf.api.model;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.util.Objects;

public record GltfSkin(String name, int skeletonRoot, int[] joints, float[] inverseBindMatrices) {
    /** Asset limit per skin, covering every index representable by glTF's unsigned-short JOINTS_0. */
    public static final int MAX_JOINTS = 65_536;

    public GltfSkin {
        name = name == null ? "" : name;
        Objects.requireNonNull(joints, "joints");
        Objects.requireNonNull(inverseBindMatrices, "inverseBindMatrices");
        if (skeletonRoot < -1) throw new IllegalArgumentException("Invalid skeleton root");
        if (joints.length == 0) throw new IllegalArgumentException("A skin must contain at least one joint");
        if (joints.length > MAX_JOINTS) {
            throw new IllegalArgumentException("GFBS:glTF supports at most " + MAX_JOINTS + " joints per skin");
        }
        if (inverseBindMatrices.length != joints.length * 16) {
            throw new IllegalArgumentException("Inverse bind matrix count does not match joints");
        }
        joints = joints.clone();
        inverseBindMatrices = inverseBindMatrices.clone();
        IntOpenHashSet uniqueJoints = new IntOpenHashSet(joints.length);
        for (int joint : joints) {
            if (joint < 0) throw new IllegalArgumentException("Joint indices must be non-negative");
            if (!uniqueJoints.add(joint)) throw new IllegalArgumentException("A skin cannot contain duplicate joints");
        }
        for (float value : inverseBindMatrices) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Inverse bind matrix contains a non-finite value");
        }
    }

    public int jointCount() { return joints.length; }

    @Override
    public int[] joints() { return joints.clone(); }

    @Override
    public float[] inverseBindMatrices() { return inverseBindMatrices.clone(); }

    /** Package-private zero-copy views used by the renderer hot path. */
    int[] jointsView() { return joints; }
    float[] inverseBindMatricesView() { return inverseBindMatrices; }
}
