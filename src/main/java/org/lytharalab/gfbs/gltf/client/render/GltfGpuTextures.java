package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lytharalab.gfbs.gltf.GFBSglTF;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderTypes;
import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfLabPbr;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfTexture;
import org.lytharalab.gfbs.gltf.api.model.GltfTextureInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.lytharalab.gfbs.gltf.client.render.LabPbrEncoder.*;

/** Render-thread texture residency. Identical image/sampler/material layouts share an upload. */
final class GltfGpuTextures {
    private static final AtomicLong IDS = new AtomicLong();
    private static final int COLD_FRAMES = 180;
    private static final ImageKey WHITE = new ImageKey(-1, AlphaMode.BLEND, 0.0f);
    private final GltfAsset asset;
    private final Map<Object, Entry> entries = new LinkedHashMap<>();
    private int dimensionLimit;
    private boolean warnedUv;

    GltfGpuTextures(GltfAsset asset) {
        this.asset = asset;
    }

    ResourceLocation base(GltfMaterial material, boolean shaderPack) {
        ImageKey image = ImageKey.base(material);
        if (!shaderPack || !OculusPbrTextureBridge.install()) {
            return get(image, image, null, null);
        }
        GltfTextureInfo anchor = GltfMaterialBaker.anchor(material);
        PbrKey key = new PbrKey(image, anchor, material.normalTextureInfo(),
            material.occlusionTextureInfo(), material.metallicRoughnessTextureInfo(),
            material.normalScale(), material.occlusionStrength(), material.metallicFactor(),
            material.roughnessFactor(), material.baseColorRed(), material.baseColorGreen(),
            material.baseColorBlue(), material.labPbr());
        return get(key, image, material, anchor);
    }

    GltfTextureInfo baseUv(GltfMaterial material, boolean shaderPack) {
        return shaderPack && OculusPbrTextureBridge.install()
            ? GltfMaterialBaker.anchor(material) : material.baseColorTextureInfo();
    }

    ResourceLocation base(GltfMaterial material, boolean shaderPack, GltfMaterialBaker.Page page) {
        if (page == null) return base(material, shaderPack);
        Entry entry = entries.get(page);
        if (entry != null) {
            entry.lastFrame = GltfGpuCache.frame();
            return entry.id;
        }
        try (NativeImage pixels = decodePage(page, 0)) {
            GltfUploadedTexture texture = new GltfMaterialTexture(pixels, this, page);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(GFBSglTF.MODID,
                "runtime/" + Long.toUnsignedString(IDS.incrementAndGet(), 36));
            try {
                Minecraft.getInstance().getTextureManager().register(id, texture);
            } catch (RuntimeException | Error failure) {
                texture.close();
                throw failure;
            }
            entries.put(page, new Entry(id));
            return id;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid baked glTF material in " + asset.id(), exception);
        }
    }

    GltfUploadedTexture companion(GltfMaterialBaker.Page page, int channel) {
        try (NativeImage image = decodePage(page, channel)) {
            return new GltfUploadedTexture(image, 9729, 9987, 33071, 33071, channel == 2, page.maxMip);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid baked glTF companion in " + asset.id(), exception);
        }
    }

    private static NativeImage decodePage(GltfMaterialBaker.Page page, int channel) throws IOException {
        return NativeImage.read(new java.io.ByteArrayInputStream(page.images[channel]));
    }

    ResourceLocation emissive(GltfMaterial material) {
        ImageKey image = material.emissiveTexture() < 0 ? WHITE
            : new ImageKey(material.emissiveTexture(), AlphaMode.OPAQUE, 0.0f);
        return get(image, image, null, null);
    }

    private ResourceLocation get(Object key, ImageKey image, GltfMaterial material, GltfTextureInfo anchor) {
        Entry entry = entries.get(key);
        if (entry != null) {
            entry.lastFrame = GltfGpuCache.frame();
            return entry.id;
        }
        try (NativeImage pixels = image.index < 0 ? solid(0xffffffff) : decode(image.index)) {
            if (image.alpha != AlphaMode.BLEND) applyCoverage(pixels, image);
            GltfTexture sampler = sampler(image.index);
            GltfUploadedTexture texture = material == null
                ? new GltfUploadedTexture(pixels, sampler, false)
                : new GltfMaterialTexture(pixels, sampler, this, material, anchor);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(GFBSglTF.MODID,
                "runtime/" + Long.toUnsignedString(IDS.incrementAndGet(), 36));
            try {
                Minecraft.getInstance().getTextureManager().register(id, texture);
            } catch (RuntimeException | Error failure) {
                texture.close();
                throw failure;
            }
            entries.put(key, new Entry(id));
            return id;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid base or emissive texture in " + asset.id(), failure);
        }
    }

    GltfUploadedTexture companion(GltfMaterial material, GltfTextureInfo anchor, boolean specular) {
        GltfLabPbr lab = material.labPbr();
        int packedIndex = specular ? lab.specularTexture() : lab.normalTexture();
        try {
            if (packedIndex >= 0) {
                try (NativeImage pixels = decode(packedIndex)) {
                    applyLabOverrides(pixels, lab, specular);
                    return new GltfUploadedTexture(pixels, sampler(packedIndex), specular);
                }
            }
            GltfTextureInfo primary = specular ? material.metallicRoughnessTextureInfo() : material.normalTextureInfo();
            GltfTextureInfo ao = specular ? GltfTextureInfo.absent() : material.occlusionTextureInfo();
            UvProjection primaryUv = projection(anchor, primary);
            UvProjection aoUv = projection(anchor, ao);
            try (NativeImage source = primaryUv == null ? null : decode(primary.texture());
                 NativeImage occlusion = aoUv == null ? null : decode(ao.texture())) {
                int width = Math.max(source == null ? 1 : source.getWidth(), occlusion == null ? 1 : occlusion.getWidth());
                int height = Math.max(source == null ? 1 : source.getHeight(), occlusion == null ? 1 : occlusion.getHeight());
                // Two very differently shaped inputs can imply an unreasonable cross-product.
                if ((long) width * height > 16L * 1024 * 1024) {
                    NativeImage reference = source == null ? occlusion : source;
                    width = reference.getWidth();
                    height = reference.getHeight();
                }
                try (NativeImage output = new NativeImage(width, height, false)) {
                    int alpha = specular ? byteOr(lab.emission(), 255) : byteOr(lab.height(), 255);
                    GltfTexture sourceSampler = sampler(primary.texture());
                    GltfTexture aoSampler = sampler(ao.texture());
                    for (int y = 0; y < height; y++) {
                        double v = (y + 0.5) / height;
                        for (int x = 0; x < width; x++) {
                            double u = (x + 0.5) / width;
                            int pixel = source == null ? 0xffffffff : primaryUv.sample(source, sourceSampler, u, v);
                            if (specular) {
                                output.setPixelRGBA(x, y, LabPbrEncoder.specular(pixel, material, alpha));
                            } else {
                                int ambient = occlusion == null ? 255 : channel(1.0f
                                    - (1.0f - red(aoUv.sample(occlusion, aoSampler, u, v)) / 255.0f)
                                        * material.occlusionStrength());
                                output.setPixelRGBA(x, y, source == null ? rgba(128, 128, ambient, alpha)
                                    : LabPbrEncoder.normal(pixel, ambient, material.normalScale(), alpha));
                            }
                        }
                    }
                    GltfTexture outputSampler = source == null && occlusion == null ? null : sampler(anchor.texture());
                    applyLabOverrides(output, lab, specular);
                    return new GltfUploadedTexture(output, outputSampler, specular);
                }
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid LabPBR companion in " + asset.id(), failure);
        }
    }

    private UvProjection projection(GltfTextureInfo anchor, GltfTextureInfo source) {
        if (!source.present()) return null;
        UvProjection projection = UvProjection.between(anchor, source);
        if (projection == null && !warnedUv) {
            warnedUv = true;
            GFBSglTF.LOGGER.warn("A glTF companion in {} cannot share the material's Iris UV set; using neutral data for that channel",
                asset.id());
        }
        return projection;
    }

    private static void applyCoverage(NativeImage image, ImageKey key) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getPixelRGBA(x, y);
                int alpha = key.alpha == AlphaMode.OPAQUE || (pixel >>> 24) >= key.cutoff ? 255 : 0;
                image.setPixelRGBA(x, y, (pixel & 0xffffff) | alpha << 24);
            }
        }
    }

    private static void applyLabOverrides(NativeImage image, GltfLabPbr lab, boolean specular) {
        if (specular ? lab.smoothness() < 0 && lab.reflectance() < 0
            && lab.porosityOrSubsurface() < 0 && lab.emission() < 0
            : lab.normalX() < 0 && lab.normalY() < 0 && lab.ambientOcclusion() < 0 && lab.height() < 0) return;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getPixelRGBA(x, y);
                image.setPixelRGBA(x, y, specular
                    ? rgba(byteOr(lab.smoothness(), red(pixel)), byteOr(lab.reflectance(), green(pixel)),
                        byteOr(lab.porosityOrSubsurface(), blue(pixel)), byteOr(lab.emission(), pixel >>> 24))
                    : rgba(byteOr(lab.normalX(), red(pixel)), byteOr(lab.normalY(), green(pixel)),
                        byteOr(lab.ambientOcclusion(), blue(pixel)), byteOr(lab.height(), pixel >>> 24)));
            }
        }
    }

    private static int byteOr(int value, int fallback) { return value < 0 ? fallback : value; }

    private static NativeImage solid(int rgba) {
        NativeImage image = new NativeImage(1, 1, false);
        image.setPixelRGBA(0, 0, rgba);
        return image;
    }

    private GltfTexture sampler(int index) { return index < 0 ? null : asset.textures().get(index); }

    private NativeImage decode(int index) throws IOException {
        GltfTexture texture = asset.textures().get(index);
        if (dimensionLimit == 0) dimensionLimit = Math.min(8192, GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE));
        ByteBuffer encoded = MemoryUtil.memAlloc(texture.encodedImageSize());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            encoded.put(texture.encodedImage()).flip();
            IntBuffer width = stack.mallocInt(1), height = stack.mallocInt(1), channels = stack.mallocInt(1);
            if (!STBImage.stbi_info_from_memory(encoded, width, height, channels)) {
                throw new IOException("Invalid image " + index + " (" + STBImage.stbi_failure_reason() + ")");
            }
            int w = width.get(0), h = height.get(0);
            if (w <= 0 || h <= 0 || w > dimensionLimit || h > dimensionLimit) {
                throw new IOException("Image " + index + " exceeds the " + dimensionLimit + " pixel dimension limit");
            }
            return NativeImage.read(encoded);
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }

    void releaseCold(long frame) {
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (frame - entry.lastFrame <= COLD_FRAMES) continue;
            iterator.remove();
            release(entry);
        }
    }

    void close() {
        for (Entry entry : entries.values()) release(entry);
        entries.clear();
    }

    private static void release(Entry entry) {
        GltfRenderTypes.forgetTexture(entry.id);
        Minecraft.getInstance().getTextureManager().release(entry.id);
    }

    private record ImageKey(int index, AlphaMode alpha, float cutoff) {
        static ImageKey base(GltfMaterial material) {
            int index = material.baseColorTexture();
            if (material.alphaMode() != AlphaMode.MASK) {
                return index < 0 ? WHITE : new ImageKey(index, material.alphaMode(), 0.0f);
            }
            float factor = material.baseColorAlpha();
            float cutoff = material.alphaCutoff() == 0.0f ? 0.0f
                : factor == 0.0f ? 256.0f : (material.alphaCutoff() / factor) * 255.0f;
            return new ImageKey(index, AlphaMode.MASK, Math.min(256.0f, cutoff));
        }
    }

    private record PbrKey(ImageKey base, GltfTextureInfo anchor, GltfTextureInfo normal,
                          GltfTextureInfo occlusion, GltfTextureInfo metallicRoughness,
                          float normalScale, float occlusionStrength, float metallic, float roughness,
                          float red, float green, float blue, GltfLabPbr labPbr) {}

    private static final class Entry {
        final ResourceLocation id;
        long lastFrame = GltfGpuCache.frame();
        Entry(ResourceLocation id) { this.id = id; }
    }

    /** Maps a texel in the Iris UV domain back through the source texture transform. */
    private record UvProjection(double u0, double v0, double ux, double uy, double vx, double vy) {
        private static final UvProjection IDENTITY = new UvProjection(0, 0, 1, 0, 0, 1);

        static UvProjection between(GltfTextureInfo target, GltfTextureInfo source) {
            if (target.texCoord() != source.texCoord()) return null;
            if (target.offsetU() == source.offsetU() && target.offsetV() == source.offsetV()
                && target.scaleU() == source.scaleU() && target.scaleV() == source.scaleV()
                && target.rotation() == source.rotation()) return IDENTITY;
            if (target.scaleU() == 0 || target.scaleV() == 0) return null;
            double tc = Math.cos(target.rotation()), ts = Math.sin(target.rotation());
            double sc = Math.cos(source.rotation()), ss = Math.sin(source.rotation());
            double su = (double) source.scaleU() / target.scaleU();
            double sv = (double) source.scaleV() / target.scaleV();
            double ux = sc * su * tc + ss * sv * ts;
            double uy = sc * su * ts - ss * sv * tc;
            double vx = ss * su * tc - sc * sv * ts;
            double vy = ss * su * ts + sc * sv * tc;
            return new UvProjection(source.offsetU() - ux * target.offsetU() - uy * target.offsetV(),
                source.offsetV() - vx * target.offsetU() - vy * target.offsetV(), ux, uy, vx, vy);
        }

        int sample(NativeImage image, GltfTexture sampler, double u, double v) {
            int x = texel(u0 + ux * u + uy * v, image.getWidth(), sampler.wrapS());
            int y = texel(v0 + vx * u + vy * v, image.getHeight(), sampler.wrapT());
            return image.getPixelRGBA(x, y);
        }

        private static int texel(double coordinate, int length, int wrap) {
            if (wrap == 10497) coordinate -= Math.floor(coordinate);
            else if (wrap == 33648) {
                coordinate -= 2.0 * Math.floor(coordinate * 0.5);
                coordinate = coordinate <= 1.0 ? coordinate : 2.0 - coordinate;
            }
            return Math.max(0, Math.min(length - 1, (int) (coordinate * length)));
        }
    }
}
