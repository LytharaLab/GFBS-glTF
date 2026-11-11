package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lytharalab.gfbs.gltf.api.model.GltfTexture;
import org.lytharalab.gfbs.gltf.api.model.GltfTextureInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** CPU image decoding and glTF sampling used by runtime reprojection and atlas resources. */
final class GltfImageSampler {
    private final NativeImage image;
    private final GltfTexture texture;
    private final GltfTextureInfo info;
    private final double cosine, sine;
    private final boolean flipV;

    private GltfImageSampler(NativeImage image, GltfTexture texture, GltfTextureInfo info, boolean flipV) {
        this.image = image;
        this.texture = texture;
        this.info = info;
        this.flipV = flipV;
        cosine = Math.cos(info.rotation());
        sine = Math.sin(info.rotation());
    }

    int width() { return image.getWidth(); }
    int height() { return image.getHeight(); }
    GltfTexture sampler() { return texture; }

    double u(double u0, double v0, double u1, double v1) {
        double u = info.texCoord() == 0 ? u0 : u1;
        double v = info.texCoord() == 0 ? v0 : v1;
        return info.offsetU() + cosine * u * info.scaleU() - sine * v * info.scaleV();
    }

    double v(double u0, double v0, double u1, double v1) {
        double u = info.texCoord() == 0 ? u0 : u1;
        double v = info.texCoord() == 0 ? v0 : v1;
        return info.offsetV() + sine * u * info.scaleU() + cosine * v * info.scaleV();
    }

    int sample(double u0, double v0, double u1, double v1, boolean discrete) {
        double u = u(u0, v0, u1, v1);
        double v = v(u0, v0, u1, v1);
        if (flipV) v = 1 - v;
        if (discrete || texture.magFilter() == 9728) {
            return image.getPixelRGBA(texel(u, width(), texture.wrapS()),
                texel(v, height(), texture.wrapT()));
        }
        u = wrap(u, texture.wrapS()) * width() - 0.5;
        v = wrap(v, texture.wrapT()) * height() - 0.5;
        int x = (int) Math.floor(u), y = (int) Math.floor(v);
        float fx = (float) (u - Math.floor(u)), fy = (float) (v - Math.floor(v));
        int a = image.getPixelRGBA(index(x, width(), texture.wrapS()), index(y, height(), texture.wrapT()));
        int b = image.getPixelRGBA(index(x + 1, width(), texture.wrapS()), index(y, height(), texture.wrapT()));
        int c = image.getPixelRGBA(index(x, width(), texture.wrapS()), index(y + 1, height(), texture.wrapT()));
        int d = image.getPixelRGBA(index(x + 1, width(), texture.wrapS()), index(y + 1, height(), texture.wrapT()));
        int pixel = 0;
        for (int channel = 0; channel < 32; channel += 8) {
            float top = (a >>> channel & 255) * (1 - fx) + (b >>> channel & 255) * fx;
            float bottom = (c >>> channel & 255) * (1 - fx) + (d >>> channel & 255) * fx;
            pixel |= Math.round(top * (1 - fy) + bottom * fy) << channel;
        }
        return pixel;
    }

    private static int texel(double coordinate, int size, int mode) {
        return Math.max(0, Math.min(size - 1, (int) (wrap(coordinate, mode) * size)));
    }

    private static double wrap(double coordinate, int mode) {
        if (mode == 10497) return coordinate - Math.floor(coordinate);
        if (mode == 33648) {
            double period = coordinate - 2 * Math.floor(coordinate * 0.5);
            return period <= 1 ? period : 2 - period;
        }
        return Math.max(0, Math.min(1, coordinate));
    }

    private static int index(int coordinate, int size, int mode) {
        if (mode == 10497) return Math.floorMod(coordinate, size);
        if (mode == 33648) {
            int period = Math.floorMod(coordinate, size * 2);
            return period < size ? period : size * 2 - period - 1;
        }
        return Math.max(0, Math.min(size - 1, coordinate));
    }

    static NativeImage decode(GltfTexture texture) throws IOException {
        ByteBuffer bytes = MemoryUtil.memAlloc(texture.encodedImageSize());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            bytes.put(texture.encodedImage()).flip();
            IntBuffer width = stack.mallocInt(1), height = stack.mallocInt(1), components = stack.mallocInt(1);
            if (!STBImage.stbi_info_from_memory(bytes, width, height, components)) {
                throw new IOException("Invalid glTF image (" + STBImage.stbi_failure_reason() + ")");
            }
            if (width.get(0) < 1 || height.get(0) < 1 || width.get(0) > 8192 || height.get(0) > 8192) {
                throw new IOException("glTF image exceeds the 8192 pixel dimension limit");
            }
            return NativeImage.read(bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    static final class Sources implements AutoCloseable {
        private final List<GltfTexture> textures;
        private final Map<Integer, NativeImage> decoded = new HashMap<>();
        private final boolean flipV;

        Sources(List<GltfTexture> textures) { this(textures, false); }

        Sources(List<GltfTexture> textures, boolean flipV) {
            this.textures = textures;
            this.flipV = flipV;
        }

        GltfImageSampler get(GltfTextureInfo info) throws IOException {
            if (!info.present()) return null;
            NativeImage image = decoded.get(info.texture());
            if (image == null) {
                image = decode(textures.get(info.texture()));
                decoded.put(info.texture(), image);
            }
            return new GltfImageSampler(image, textures.get(info.texture()), info, flipV);
        }

        @Override public void close() {
            for (NativeImage image : decoded.values()) image.close();
            decoded.clear();
        }
    }
}
