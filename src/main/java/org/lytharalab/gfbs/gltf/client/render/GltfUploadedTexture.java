package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lytharalab.gfbs.gltf.api.model.GltfTexture;

/** Immutable GPU image. The caller closes the decoded pixels after uploading. */
class GltfUploadedTexture extends AbstractTexture {
    GltfUploadedTexture(NativeImage image, GltfTexture sampler, boolean labSpecular) {
        this(image, sampler == null ? GL11C.GL_NEAREST : sampler.magFilter(),
            sampler == null ? GL11C.GL_NEAREST : sampler.minFilter(),
            sampler == null ? GL11C.GL_REPEAT : sampler.wrapS(),
            sampler == null ? GL11C.GL_REPEAT : sampler.wrapT(), labSpecular, 30);
    }

    GltfUploadedTexture(NativeImage image, int mag, int min, int wrapS, int wrapT,
                        boolean labSpecular, int maxMip) {
        RenderSystem.assertOnRenderThread();
        boolean mipmaps = min >= GL11C.GL_NEAREST_MIPMAP_NEAREST && min <= GL11C.GL_LINEAR_MIPMAP_LINEAR;
        int levels = mipmaps ? Math.min(maxMip,
            31 - Integer.numberOfLeadingZeros(Math.max(image.getWidth(), image.getHeight()))) : 0;
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            TextureUtil.prepareImage(getId(), image.getWidth(), image.getHeight());
            image.upload(0, 0, 0, false);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, levels);
            if (levels > 0) {
                if (labSpecular) uploadSpecularMips(image, levels);
                else GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
            }
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER,
                labSpecular ? GL11C.GL_NEAREST : mag);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER,
                labSpecular ? (mipmaps ? GL11C.GL_NEAREST_MIPMAP_NEAREST : GL11C.GL_NEAREST) : min);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S,
                wrapS);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T,
                wrapT);
        } catch (RuntimeException | Error failure) {
            releaseId();
            throw failure;
        } finally {
            RenderSystem.bindTexture(previous);
        }
    }

    private static void uploadSpecularMips(NativeImage source, int levels) {
        NativeImage current = source;
        try {
            for (int level = 1; level <= levels; level++) {
                int width = Math.max(1, current.getWidth() >> 1);
                int height = Math.max(1, current.getHeight() >> 1);
                NativeImage next = new NativeImage(width, height, false);
                try {
                    for (int y = 0; y < height; y++) {
                        int y0 = y * 2, y1 = Math.min(y0 + 1, current.getHeight() - 1);
                        for (int x = 0; x < width; x++) {
                            int x0 = x * 2, x1 = Math.min(x0 + 1, current.getWidth() - 1);
                            next.setPixelRGBA(x, y, LabPbrEncoder.mipSpecular(
                                current.getPixelRGBA(x0, y0), current.getPixelRGBA(x1, y0),
                                current.getPixelRGBA(x0, y1), current.getPixelRGBA(x1, y1)
                            ));
                        }
                    }
                    GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, level, GL11C.GL_RGBA8,
                        width, height, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 0L);
                    next.upload(level, 0, 0, false);
                } catch (RuntimeException | Error failure) {
                    next.close();
                    throw failure;
                }
                if (current != source) current.close();
                current = next;
            }
        } finally {
            if (current != source) current.close();
        }
    }

    // TextureStateShard asks every bound texture for nearest/no-mipmap. Sampling here is glTF state.
    @Override public void setFilter(boolean blur, boolean mipmap) {}
    @Override public void load(ResourceManager resources) {}
    @Override public void close() { releaseId(); }
}
