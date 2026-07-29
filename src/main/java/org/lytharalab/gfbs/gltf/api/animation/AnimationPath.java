package org.lytharalab.gfbs.gltf.api.animation;

public enum AnimationPath {
    TRANSLATION(3), ROTATION(4), SCALE(3), WEIGHTS(-1);

    private final int components;
    AnimationPath(int components) { this.components = components; }
    public int components() { return components; }

    public static AnimationPath fromGltf(String path) {
        return switch (path) {
            case "translation" -> TRANSLATION;
            case "rotation" -> ROTATION;
            case "scale" -> SCALE;
            case "weights" -> WEIGHTS;
            default -> throw new IllegalArgumentException("Unsupported animation path: " + path);
        };
    }
}
