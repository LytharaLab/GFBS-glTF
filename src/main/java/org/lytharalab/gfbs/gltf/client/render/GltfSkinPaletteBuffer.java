package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryUtil;
import org.lytharalab.gfbs.gltf.api.client.node.GltfNodeManager.SkinPalette;

import java.nio.FloatBuffer;

/** A streamed palette shared by draws on the render thread. Four texels store one matrix. */
final class GltfSkinPaletteBuffer implements AutoCloseable {
    static final int TEXTURE_UNIT = 3;
    static final int TEXELS_PER_JOINT = 4;
    private static final int FLOATS_PER_JOINT = TEXELS_PER_JOINT * 4;

    private int buffer;
    private int texture;
    private int uploadedBytes;
    private FloatBuffer staging;
    private SkinPalette previousPalette;
    private long previousRevision;
    long lastFrame;

    void upload(SkinPalette palette) {
        lastFrame = GltfGpuCache.frame();
        if (previousPalette == palette && previousRevision == palette.revision()) return;

        float[] matrices = palette.matrices();
        int floats = Math.max(FLOATS_PER_JOINT, matrices.length);
        if (staging == null || staging.capacity() < floats) {
            int capacity = Integer.highestOneBit(floats - 1) << 1;
            FloatBuffer replacement = MemoryUtil.memAllocFloat(capacity);
            MemoryUtil.memFree(staging);
            staging = replacement;
        }
        staging.clear();
        if (matrices.length == 0) {
            // The shader still needs a buffer texture when every vertex has zero weight.
            for (int i = 0; i < FLOATS_PER_JOINT; i++) staging.put(0.0f);
        } else {
            staging.put(matrices);
        }
        staging.flip();

        int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        try {
            if (buffer == 0) {
                buffer = GL15C.glGenBuffers();
                if (buffer == 0) throw new IllegalStateException("Could not allocate a GPU skin palette");
            }
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
            // Replacing the data store permits the driver to keep earlier draws in flight.
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, staging, GL15C.GL_STREAM_DRAW);
            int bytes = floats * Float.BYTES;
            if (uploadedBytes != bytes
                && GL15C.glGetBufferParameteri(GL15C.GL_ARRAY_BUFFER, GL15C.GL_BUFFER_SIZE) != bytes) {
                throw new IllegalStateException("Could not upload a GPU skin palette");
            }
            uploadedBytes = bytes;
            GltfRenderMetrics metrics = GltfRenderMetrics.active();
            if (metrics != null) {
                metrics.paletteUploads++;
                metrics.paletteUploadBytes += bytes;
            }
        } finally {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);
        }
        if (texture == 0) texture = attachTexture(buffer);
        previousPalette = palette;
        previousRevision = palette.revision();
    }

    static int attachTexture(int buffer) {
        int texture = GL11C.glGenTextures();
        if (texture == 0) throw new IllegalStateException("Could not allocate a GPU skin buffer texture");
        int previous = GL11C.glGetInteger(GL31C.GL_TEXTURE_BINDING_BUFFER);
        boolean attached = false;
        try {
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, texture);
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_RGBA32F, buffer);
            if (GL11C.glGetTexLevelParameteri(GL31C.GL_TEXTURE_BUFFER, 0,
                    GL31C.GL_TEXTURE_BUFFER_DATA_STORE_BINDING) != buffer) {
                throw new IllegalStateException("Could not attach the GPU skin palette");
            }
            attached = true;
            return texture;
        } finally {
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, previous);
            if (!attached) GL11C.glDeleteTextures(texture);
        }
    }

    int texture() { return texture; }

    static int bindTexture(int texture) {
        int active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNIT);
        int previous = GL11C.glGetInteger(GL31C.GL_TEXTURE_BINDING_BUFFER);
        GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, texture);
        GlStateManager._activeTexture(active);
        return previous;
    }

    static void restore(int previous) {
        int active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNIT);
        GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, previous);
        GlStateManager._activeTexture(active);
    }

    @Override
    public void close() {
        GL11C.glDeleteTextures(texture);
        GL15C.glDeleteBuffers(buffer);
        MemoryUtil.memFree(staging);
    }
}
