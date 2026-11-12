package org.lytharalab.gfbs.gltf.client.render;

import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;

/** LabPBR 1.3 encoding in the ABGR integer layout used by NativeImage. */
final class LabPbrEncoder {
    private LabPbrEncoder() {}

    static int normal(int gltfNormal, int ao, float scale, int height) {
        float x = (red(gltfNormal) / 127.5f - 1.0f) * scale;
        float y = (1.0f - green(gltfNormal) / 127.5f) * scale;
        float z = Math.max(0.0f, blue(gltfNormal) / 127.5f - 1.0f);
        double length = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        if (length > 0.0) {
            x /= length;
            y /= length;
        }
        return rgba(channel(x * 0.5f + 0.5f), channel(y * 0.5f + 0.5f), ao, height);
    }

    static int specular(int metallicRoughness, GltfMaterial material, int emission) {
        float metal = material.metallicFactor() * (blue(metallicRoughness) / 255.0f);
        float perceptualRoughness = material.roughnessFactor() * (green(metallicRoughness) / 255.0f);
        float luminance = material.baseColorRed() * 0.2126f
            + material.baseColorGreen() * 0.7152f + material.baseColorBlue() * 0.0722f;
        int reflectance = material.labPbr().reflectance();
        if (reflectance < 0) {
            // LabPBR has dielectric F0 or discrete metal IDs. 255 uses the albedo as metal F0.
            reflectance = metal >= 0.5f ? 255 : Math.min(229, channel(0.04f + metal * (luminance - 0.04f)));
        }
        int smoothness = material.labPbr().smoothness();
        return rgba(smoothness < 0 ? channel(1.0f - perceptualRoughness) : smoothness, reflectance,
            Math.max(0, material.labPbr().porosityOrSubsurface()), emission);
    }

    /**
     * Converts one emissive-texture sample to the LabPBR emission channel. Zero emission uses the
     * 255 sentinel; representable emission is kept in 1..254 so mip downsampling remains discrete.
     */
    static int emission(int emissive, GltfMaterial material) {
        float strength = material.emissiveStrength();
        if (!(strength > 0.0f)) return 255;
        float red = red(emissive) / 255.0f * material.emissiveRed() * strength;
        float green = green(emissive) / 255.0f * material.emissiveGreen() * strength;
        float blue = blue(emissive) / 255.0f * material.emissiveBlue() * strength;
        return emission(Math.max(red, Math.max(green, blue)));
    }

    static int emission(GltfMaterial material) {
        return emission(Math.max(material.emissiveRed(),
            Math.max(material.emissiveGreen(), material.emissiveBlue())) * material.emissiveStrength());
    }

    static int emission(float amount) {
        if (!(amount > 0.0f) || !Float.isFinite(amount)) return 255;
        return Math.min(254, Math.max(1, Math.round(amount * 254.0f)));
    }

    static int mipSpecular(int a, int b, int c, int d) {
        return rgba((red(a) + red(b) + red(c) + red(d) + 2) / 4,
            categorical(green(a), green(b), green(c), green(d), 230, true),
            categorical(blue(a), blue(b), blue(c), blue(d), 65, false),
            categorical(a >>> 24, b >>> 24, c >>> 24, d >>> 24, 255, false));
    }

    private static int category(int value, int split, boolean discreteUpper) {
        return value < split ? 0 : discreteUpper ? value : 1;
    }

    private static int categorical(int a, int b, int c, int d, int split, boolean discreteUpper) {
        int ac = category(a, split, discreteUpper);
        int bc = category(b, split, discreteUpper);
        int cc = category(c, split, discreteUpper);
        int dc = category(d, split, discreteUpper);
        int majority = ac;
        if (ac != bc && ac != cc && ac != dc) {
            if (bc == cc || bc == dc) majority = bc;
            else if (cc == dc) majority = cc;
        }
        int sum = 0;
        int count = 0;
        if (ac == majority) { sum += a; count++; }
        if (bc == majority) { sum += b; count++; }
        if (cc == majority) { sum += c; count++; }
        if (dc == majority) { sum += d; count++; }
        return (sum + count / 2) / count;
    }

    static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value * 255.0f))); }
    static int red(int pixel) { return pixel & 255; }
    static int green(int pixel) { return pixel >>> 8 & 255; }
    static int blue(int pixel) { return pixel >>> 16 & 255; }
    static int rgba(int red, int green, int blue, int alpha) {
        return red | green << 8 | blue << 16 | alpha << 24;
    }
}
