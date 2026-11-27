package org.lytharalab.gfbs.gltf.client.render;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lytharalab.gfbs.gltf.GFBSglTF;

import java.util.ArrayDeque;

/** Scratch GPU buffers for primitives rendered in more than one built-in pass. */
final class GltfGpuDeformationPool {
    private static final long BUDGET = 32L * 1024 * 1024;
    private static final int MAX_BUFFERS = 64;
    private static final int STRIDE = GltfSkinFeedback.FLOATS_PER_VERTEX * Float.BYTES;
    private static final ArrayDeque<Buffer> IDLE = new ArrayDeque<>();
    private static int program, allocated, generation;
    private static long bytes;
    private static boolean unavailable;

    private GltfGpuDeformationPool() {}

    static Buffer capture(GltfSkinnedGeometry geometry, int paletteTexture) {
        if (unavailable || !geometry.worthCachingDeformation()
            || geometry.vertexCount > GltfGpuSkinning.maxCachedVertices() || !GltfSkinFeedback.idle()) return null;
        long needed = (long) geometry.vertexCount * STRIDE;
        if (needed > BUDGET) return null;
        Buffer buffer = null;
        try {
            if (program == 0) program = GltfSkinFeedback.link();
            buffer = acquire(needed);
            if (buffer == null) return null;
            GltfSkinFeedback.capture(geometry, paletteTexture, program, buffer.id, buffer.bytes, 0, null, null);
            if (buffer.texture == 0) buffer.texture = GltfSkinPaletteBuffer.attachTexture(buffer.id);
            GltfRenderMetrics metrics = GltfRenderMetrics.active();
            if (metrics != null) metrics.gpuDeformationBuilds++;
            return buffer;
        } catch (RuntimeException | LinkageError failure) {
            if (buffer != null) delete(buffer);
            unavailable = true;
            GFBSglTF.LOGGER.warn("GPU deformation caching is unavailable. Using vertex shader skinning.", failure);
            return null;
        }
    }

    private static Buffer acquire(long needed) {
        Buffer best = null;
        for (Buffer buffer : IDLE) {
            if (buffer.bytes >= needed && (best == null || buffer.bytes < best.bytes)) best = buffer;
        }
        if (best != null) {
            IDLE.remove(best);
            return best;
        }
        while (!IDLE.isEmpty() && (allocated == MAX_BUFFERS || bytes + needed > BUDGET)) {
            delete(IDLE.removeFirst());
        }
        if (allocated == MAX_BUFFERS || bytes + needed > BUDGET) return null;
        int id = GL15C.glGenBuffers();
        if (id == 0) throw new IllegalStateException("Could not allocate an OpenGL deformation buffer");
        allocated++;
        bytes += needed;
        return new Buffer(id, needed, generation);
    }

    static void release(Buffer buffer) {
        if (buffer.generation != generation) {
            delete(buffer);
            return;
        }
        buffer.lastFrame = GltfGpuCache.frame();
        IDLE.addLast(buffer);
    }

    static void releaseCold(long frame) {
        while (!IDLE.isEmpty() && frame - IDLE.peekFirst().lastFrame > 180) delete(IDLE.removeFirst());
    }

    static void clear() {
        generation++;
        while (!IDLE.isEmpty()) delete(IDLE.removeFirst());
        if (program != 0) GL20C.glDeleteProgram(program);
        program = 0;
        unavailable = false;
    }

    static long bytes() { return bytes; }

    private static void delete(Buffer buffer) {
        GL11C.glDeleteTextures(buffer.texture);
        GL15C.glDeleteBuffers(buffer.id);
        allocated--;
        bytes -= buffer.bytes;
    }

    static final class Buffer {
        final int id;
        final long bytes;
        final int generation;
        int texture;
        long lastFrame;

        private Buffer(int id, long bytes, int generation) {
            this.id = id;
            this.bytes = bytes;
            this.generation = generation;
        }

        boolean current() { return generation == GltfGpuDeformationPool.generation; }
    }
}
