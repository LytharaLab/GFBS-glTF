package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfTexture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Texture-only GPU cache. Geometry is fed to Minecraft's NEW_ENTITY pipeline, so GFBS no longer
 * carries a standalone PBR program or depends on Embeddium internals.
 */
final class GltfGpuModel {
    private static final AtomicLong NEXT_RUNTIME_ID = new AtomicLong();
    private static final int MAX_TEXTURE_DIMENSION = 8192;
    private static final long MAX_TOTAL_TEXTURE_PIXELS = 64L * 1024L * 1024L;

    final GltfAsset asset;
    final List<ResourceLocation> textures = new ArrayList<>();
    private final List<ResourceLocation> materialTextures = new ArrayList<>();
    private final List<GltfMaterialTexture> materialTextureObjects = new ArrayList<>();
    private final String runtimeId = Long.toUnsignedString(NEXT_RUNTIME_ID.incrementAndGet(), 36);
    private ResourceLocation whiteTexture;
    private boolean pbrUploaded;
    private boolean deleted;

    GltfGpuModel(GltfAsset asset) {
        RenderSystem.assertOnRenderThread();
        this.asset = asset;
        try {
            uploadTextures();
            uploadWhiteTexture();
        } catch (RuntimeException | Error failure) {
            try {
                delete();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    ResourceLocation materialTexture(int materialIndex, boolean shaderPackActive) {
        if (shaderPackActive && OculusCompat.installed()) {
            ensurePbrTextures();
            return materialTextures.get(materialIndex);
        }
        GltfMaterial material = asset.materials().get(materialIndex);
        return material.baseColorTexture() < 0
            ? whiteTexture
            : textures.get(material.baseColorTexture());
    }

    private void ensurePbrTextures() {
        if (pbrUploaded) return;
        RenderSystem.assertOnRenderThread();
        try {
            uploadMaterialTextures();
            pbrUploaded = true;
        } catch (RuntimeException | Error failure) {
            clearMaterialTextures();
            throw failure;
        }
    }

    private void uploadWhiteTexture() {
        NativeImage image = new NativeImage(1, 1, true);
        image.setPixelRGBA(0, 0, 0xffffffff);
        DynamicTexture dynamic = new DynamicTexture(image);
        whiteTexture = runtimeLocation("white");
        Minecraft.getInstance().getTextureManager().register(whiteTexture, dynamic);
    }

    private void uploadTextures() {
        long totalPixels = 0L;
        int hardwareLimit = GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE);
        int dimensionLimit = Math.min(MAX_TEXTURE_DIMENSION, Math.max(1, hardwareLimit));
        for (int index = 0; index < asset.textures().size(); index++) {
            GltfTexture texture = asset.textures().get(index);
            NativeImage image = null;
            DynamicTexture dynamic = null;
            ResourceLocation location = null;
            boolean registered = false;
            boolean committed = false;
            ByteBuffer encoded = MemoryUtil.memAlloc(texture.encodedImageSize());
            try {
                encoded.put(texture.encodedImage()).flip();
                ImageInfo info = inspectImage(encoded, index);
                if (info.width > dimensionLimit || info.height > dimensionLimit) {
                    throw new IllegalArgumentException("Texture " + index + " in " + asset.id()
                        + " is " + info.width + "x" + info.height
                        + "; maximum supported dimension is " + dimensionLimit);
                }
                totalPixels = Math.addExact(totalPixels, Math.multiplyExact((long) info.width, info.height));
                if (totalPixels > MAX_TOTAL_TEXTURE_PIXELS) {
                    throw new IllegalArgumentException("Decoded textures in " + asset.id()
                        + " exceed the 64 Mi-pixel safety budget");
                }
                image = NativeImage.read(encoded);
                dynamic = new DynamicTexture(image);
                image = null;
                dynamic.setFilter(texture.magFilter() == 9729, usesMipmaps(texture.minFilter()));
                location = runtimeLocation("texture/" + index);
                Minecraft.getInstance().getTextureManager().register(location, dynamic);
                registered = true;
                RenderSystem.activeTexture(GL13C.GL_TEXTURE0);
                RenderSystem.bindTexture(dynamic.getId());
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, texture.magFilter());
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, texture.minFilter());
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, texture.wrapS());
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, texture.wrapT());
                if (usesMipmaps(texture.minFilter())) GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
                textures.add(location);
                committed = true;
            } catch (IOException exception) {
                throw new IllegalArgumentException("Invalid image " + index + " in " + asset.id(), exception);
            } finally {
                MemoryUtil.memFree(encoded);
                if (image != null) image.close();
                if (!committed) {
                    if (registered && location != null) {
                        Minecraft.getInstance().getTextureManager().release(location);
                    } else if (dynamic != null) {
                        dynamic.close();
                    }
                }
            }
        }
    }

    private void uploadMaterialTextures() {
        OculusPbrTextureBridge.install();
        for (int materialIndex = 0; materialIndex < asset.materials().size(); materialIndex++) {
            GltfMaterial material = asset.materials().get(materialIndex);
            NativeImage base = null;
            NativeImage normal = null;
            NativeImage occlusion = null;
            NativeImage metallicRoughness = null;
            NativeImage emissive = null;
            GltfMaterialTexture materialTexture = null;
            boolean committed = false;
            try {
                base = decodeOrSolid(material.baseColorTexture(), 0xffffffff);
                normal = decodeOptional(material.normalTexture());
                occlusion = decodeOptional(material.occlusionTexture());
                metallicRoughness = decodeOptional(material.metallicRoughnessTexture());
                emissive = decodeOptional(material.emissiveTexture());
                materialTexture = new GltfMaterialTexture(
                    base,
                    createLabPbrNormal(base, normal, occlusion, material),
                    createLabPbrSpecular(base, metallicRoughness, emissive, material)
                );
                base = null;
                materialTexture.setFilter(true, true);
                materialTexture.normalTexture().setFilter(true, true);
                materialTexture.specularTexture().setFilter(true, true);
                ResourceLocation location = runtimeLocation("material/" + materialIndex);
                Minecraft.getInstance().getTextureManager().register(location, materialTexture);
                materialTextures.add(location);
                materialTextureObjects.add(materialTexture);
                committed = true;
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                    "Invalid PBR texture for material " + materialIndex + " in " + asset.id(),
                    exception
                );
            } finally {
                if (base != null) base.close();
                if (normal != null) normal.close();
                if (occlusion != null) occlusion.close();
                if (metallicRoughness != null) metallicRoughness.close();
                if (emissive != null) emissive.close();
                if (!committed && materialTexture != null) {
                    materialTexture.closeCompanions();
                    materialTexture.close();
                }
            }
        }
    }

    private NativeImage decodeOrSolid(int textureIndex, int rgba) throws IOException {
        NativeImage image = decodeOptional(textureIndex);
        if (image != null) return image;
        image = new NativeImage(1, 1, true);
        image.setPixelRGBA(0, 0, rgba);
        return image;
    }

    private NativeImage decodeOptional(int textureIndex) throws IOException {
        if (textureIndex < 0) return null;
        GltfTexture texture = asset.textures().get(textureIndex);
        ByteBuffer encoded = MemoryUtil.memAlloc(texture.encodedImageSize());
        try {
            encoded.put(texture.encodedImage()).flip();
            return NativeImage.read(encoded);
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }

    private ResourceLocation runtimeLocation(String suffix) {
        return ResourceLocation.fromNamespaceAndPath(
            "gfbs_gltf", "runtime/" + runtimeId + "/" + suffix
        );
    }

    private static NativeImage createLabPbrNormal(NativeImage base, NativeImage normal,
                                                   NativeImage occlusion, GltfMaterial material) {
        int width = normal == null ? base.getWidth() : normal.getWidth();
        int height = normal == null ? base.getHeight() : normal.getHeight();
        NativeImage output = new NativeImage(width, height, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int source = normal == null ? rgba(128, 128, 255, 255)
                    : sample(normal, x, y, width, height);
                float normalX = (red(source) / 127.5f - 1.0f) * material.normalScale();
                float normalY = (green(source) / 127.5f - 1.0f) * material.normalScale();
                float length = Math.max(1.0f, (float) Math.sqrt(normalX * normalX + normalY * normalY));
                normalX /= length;
                normalY /= length;
                int ambientOcclusion = occlusion == null ? 255 : channel(mix(
                    1.0f,
                    red(sample(occlusion, x, y, width, height)) / 255.0f,
                    material.occlusionStrength()
                ));
                output.setPixelRGBA(x, y, rgba(
                    channel(normalX * 0.5f + 0.5f),
                    channel(normalY * 0.5f + 0.5f),
                    ambientOcclusion,
                    255
                ));
            }
        }
        return output;
    }

    private static NativeImage createLabPbrSpecular(NativeImage base, NativeImage metallicRoughness,
                                                     NativeImage emissive, GltfMaterial material) {
        int width = metallicRoughness == null ? base.getWidth() : metallicRoughness.getWidth();
        int height = metallicRoughness == null ? base.getHeight() : metallicRoughness.getHeight();
        NativeImage output = new NativeImage(width, height, true);
        float[] emissiveFactor = material.emissive();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pbr = metallicRoughness == null ? 0xffffffff
                    : sample(metallicRoughness, x, y, width, height);
                int smoothness = channel(
                    1.0f - material.roughnessFactor() * green(pbr) / 255.0f
                );
                float metallic = material.metallicFactor() * blue(pbr) / 255.0f;
                float emission = Math.max(
                    emissiveFactor[0], Math.max(emissiveFactor[1], emissiveFactor[2])
                );
                if (emissive != null) {
                    int sampledEmissive = sample(emissive, x, y, width, height);
                    emission *= Math.max(
                        red(sampledEmissive),
                        Math.max(green(sampledEmissive), blue(sampledEmissive))
                    ) / 255.0f;
                }
                output.setPixelRGBA(x, y, rgba(
                    smoothness,
                    metallic >= 0.5f ? 255 : 12,
                    0,
                    channel(emission)
                ));
            }
        }
        return output;
    }

    private static int sample(NativeImage image, int x, int y, int width, int height) {
        return image.getPixelRGBA(
            Math.min(image.getWidth() - 1, x * image.getWidth() / Math.max(1, width)),
            Math.min(image.getHeight() - 1, y * image.getHeight() / Math.max(1, height))
        );
    }

    private static ImageInfo inspectImage(ByteBuffer encoded, int index) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            if (!STBImage.stbi_info_from_memory(encoded.duplicate(), width, height, channels)) {
                throw new IllegalArgumentException("Invalid image " + index + ": " + STBImage.stbi_failure_reason());
            }
            if (width.get(0) <= 0 || height.get(0) <= 0) {
                throw new IllegalArgumentException("Image " + index + " has invalid dimensions");
            }
            return new ImageInfo(width.get(0), height.get(0));
        }
    }

    void delete() {
        RenderSystem.assertOnRenderThread();
        if (deleted) return;
        deleted = true;
        for (ResourceLocation texture : textures) {
            Minecraft.getInstance().getTextureManager().release(texture);
        }
        textures.clear();
        if (whiteTexture != null) {
            Minecraft.getInstance().getTextureManager().release(whiteTexture);
            whiteTexture = null;
        }
        clearMaterialTextures();
    }

    private void clearMaterialTextures() {
        for (int i = 0; i < materialTextures.size(); i++) {
            materialTextureObjects.get(i).closeCompanions();
            Minecraft.getInstance().getTextureManager().release(materialTextures.get(i));
        }
        materialTextures.clear();
        materialTextureObjects.clear();
        pbrUploaded = false;
    }

    private static boolean usesMipmaps(int filter) {
        return filter == 9984 || filter == 9985 || filter == 9986 || filter == 9987;
    }
    private static float mix(float from, float to, float amount) { return from + (to - from) * amount; }
    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value * 255.0f))); }
    private static int red(int rgba) { return rgba & 255; }
    private static int green(int rgba) { return rgba >>> 8 & 255; }
    private static int blue(int rgba) { return rgba >>> 16 & 255; }
    private static int rgba(int red, int green, int blue, int alpha) {
        return red | green << 8 | blue << 16 | alpha << 24;
    }

    private record ImageInfo(int width, int height) {
    }
}
