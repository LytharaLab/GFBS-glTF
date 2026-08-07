package org.lytharalab.gfbs.gltf.api.model;

public record MorphTarget(float[] positions, float[] normals, float[] tangents) {
    public MorphTarget {
        positions = positions == null ? null : positions.clone();
        normals = normals == null ? null : normals.clone();
        tangents = tangents == null ? null : tangents.clone();
        if (positions == null && normals == null && tangents == null) {
            throw new IllegalArgumentException("A morph target must contain at least one attribute");
        }
        requireFinite(positions, "position");
        requireFinite(normals, "normal");
        requireFinite(tangents, "tangent");
    }

    @Override public float[] positions() { return positions == null ? null : positions.clone(); }
    @Override public float[] normals() { return normals == null ? null : normals.clone(); }
    @Override public float[] tangents() { return tangents == null ? null : tangents.clone(); }

    float[] positionsView() { return positions; }
    float[] normalsView() { return normals; }
    float[] tangentsView() { return tangents; }

    private static void requireFinite(float[] values, String label) {
        if (values == null) return;
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Morph " + label + " is not finite");
        }
    }
}
