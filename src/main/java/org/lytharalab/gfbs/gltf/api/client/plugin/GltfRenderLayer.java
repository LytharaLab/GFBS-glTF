package org.lytharalab.gfbs.gltf.api.client.plugin;

import net.minecraft.client.renderer.RenderType;

import java.util.Objects;

/** Output selected by a custom pass for one visible primitive. */
public final class GltfRenderLayer {
    public enum LightMode { INHERIT, FULL_BRIGHT, CUSTOM }

    private final RenderType renderType;
    private final GltfVertexSource vertexSource;
    private final LightMode lightMode;
    private final int customPackedLight;
    private final float red;
    private final float green;
    private final float blue;
    private final float alpha;
    private final boolean residentGeometry;

    private GltfRenderLayer(Builder builder) {
        renderType = Objects.requireNonNull(builder.renderType, "renderType");
        vertexSource = builder.vertexSource;
        lightMode = builder.lightMode;
        customPackedLight = builder.customPackedLight;
        red = builder.red;
        green = builder.green;
        blue = builder.blue;
        alpha = builder.alpha;
        residentGeometry = builder.residentGeometry;
    }

    public static Builder builder(RenderType renderType) {
        return new Builder(renderType);
    }

    public RenderType renderType() { return renderType; }
    public GltfVertexSource vertexSource() { return vertexSource; }
    public LightMode lightMode() { return lightMode; }
    public int customPackedLight() { return customPackedLight; }
    public float red() { return red; }
    public float green() { return green; }
    public float blue() { return blue; }
    public float alpha() { return alpha; }
    public boolean residentGeometry() { return residentGeometry; }

    public static final class Builder {
        private final RenderType renderType;
        private GltfVertexSource vertexSource = GltfVertexSource.BASE_COLOR;
        private LightMode lightMode = LightMode.INHERIT;
        private int customPackedLight;
        private float red = 1.0f;
        private float green = 1.0f;
        private float blue = 1.0f;
        private float alpha = 1.0f;
        private boolean residentGeometry = true;

        private Builder(RenderType renderType) {
            this.renderType = Objects.requireNonNull(renderType, "renderType");
        }

        public Builder vertices(GltfVertexSource source) {
            vertexSource = Objects.requireNonNull(source, "source");
            return this;
        }

        public Builder inheritedLight() { lightMode = LightMode.INHERIT; return this; }
        public Builder fullBright() { lightMode = LightMode.FULL_BRIGHT; return this; }
        public Builder packedLight(int value) {
            lightMode = LightMode.CUSTOM;
            customPackedLight = value;
            return this;
        }

        public Builder color(float red, float green, float blue, float alpha) {
            checkColor(red, "red");
            checkColor(green, "green");
            checkColor(blue, "blue");
            checkColor(alpha, "alpha");
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
            return this;
        }

        public Builder residentGeometry(boolean enabled) {
            residentGeometry = enabled;
            return this;
        }

        public GltfRenderLayer build() { return new GltfRenderLayer(this); }

        private static void checkColor(float value, String channel) {
            if (!Float.isFinite(value) || value < 0.0f) {
                throw new IllegalArgumentException(channel + " must be finite and non-negative");
            }
        }
    }
}
