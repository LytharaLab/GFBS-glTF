package org.lytharalab.gfbs.gltf.api.model;

public enum PrimitiveMode {
    POINTS(0), LINES(1), LINE_LOOP(2), LINE_STRIP(3), TRIANGLES(4),
    TRIANGLE_STRIP(5), TRIANGLE_FAN(6);

    private final int glConstant;

    PrimitiveMode(int glConstant) {
        this.glConstant = glConstant;
    }

    public int glConstant() {
        return glConstant;
    }

    public static PrimitiveMode fromGlConstant(int value) {
        for (PrimitiveMode mode : values()) {
            if (mode.glConstant == value) return mode;
        }
        throw new IllegalArgumentException("Unsupported glTF primitive mode: " + value);
    }
}
