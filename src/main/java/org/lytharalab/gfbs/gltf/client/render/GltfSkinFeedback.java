package org.lytharalab.gfbs.gltf.client.render;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Links the active skinning vertex shader for GPU-only capture or diagnostic readback. */
final class GltfSkinFeedback {
    static final int FLOATS_PER_VERTEX = 8;
    static final int PRIMITIVES_WRITTEN = GL30C.GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN;
    private static final int BUFFER = GL30C.GL_TRANSFORM_FEEDBACK_BUFFER;
    private static final int BINDING = GL30C.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING;

    private GltfSkinFeedback() {}

    static boolean idle() {
        var capabilities = GL.getCapabilities();
        return !(capabilities.OpenGL40 || capabilities.GL_ARB_transform_feedback2)
            || !GL11C.glGetBoolean(GL40C.GL_TRANSFORM_FEEDBACK_BUFFER_ACTIVE);
    }

    static int link() {
        int renderProgram = GltfGpuSkinning.verificationShader().getId();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int count = GL20C.glGetProgrami(renderProgram, GL20C.GL_ATTACHED_SHADERS);
            IntBuffer shaders = stack.mallocInt(count);
            GL20C.glGetAttachedShaders(renderProgram, null, shaders);
            for (int i = 0; i < count; i++) {
                int shader = shaders.get(i);
                if (GL20C.glGetShaderi(shader, GL20C.GL_SHADER_TYPE) == GL20C.GL_VERTEX_SHADER) {
                    return linkVertexShader(shader);
                }
            }
        }
        throw new IllegalStateException("The active skinning vertex shader is unavailable");
    }

    private static int linkVertexShader(int vertexShader) {
        int program = GL20C.glCreateProgram();
        if (program == 0) throw new IllegalStateException("Could not create a skinning capture program");
        boolean linked = false;
        try {
            GL20C.glAttachShader(program, vertexShader);
            var attributes = GltfSkinnedGeometry.FORMAT.getElementAttributeNames();
            for (int i = 0; i < attributes.size(); i++) GL20C.glBindAttribLocation(program, i, attributes.get(i));
            GL30C.glTransformFeedbackVaryings(program,
                new CharSequence[]{"GltfSkinnedPosition", "GltfSkinnedNormal"}, GL30C.GL_INTERLEAVED_ATTRIBS);
            GL20C.glLinkProgram(program);
            GL20C.glDetachShader(program, vertexShader);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
                throw new IllegalStateException(GL20C.glGetProgramInfoLog(program));
            }
            linked = true;
            return program;
        } finally {
            if (!linked) GL20C.glDeleteProgram(program);
        }
    }

    static void capture(GltfSkinnedGeometry geometry, int paletteTexture, int program, int feedback, long outputBytes,
                        int query, int[] ranges, FloatBuffer readback) {
        int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int previousFeedback = GL11C.glGetInteger(BINDING);
        int previousTarget = GL30C.glGetIntegeri(BINDING, 0);
        long previousStart = previousTarget == 0 ? 0 : GL32C.glGetInteger64i(GL30C.GL_TRANSFORM_FEEDBACK_BUFFER_START, 0);
        long previousSize = previousTarget == 0 ? 0 : GL32C.glGetInteger64i(GL30C.GL_TRANSFORM_FEEDBACK_BUFFER_SIZE, 0);
        boolean previousDiscard = GL11C.glIsEnabled(GL30C.GL_RASTERIZER_DISCARD);
        int previousTexture = GltfSkinPaletteBuffer.bindTexture(paletteTexture);
        try {
            GL30C.glBindVertexArray(geometry.vao);
            GL15C.glBindBuffer(BUFFER, feedback);
            GL15C.glBufferData(BUFFER, outputBytes, readback == null ? GL15C.GL_STREAM_COPY : GL15C.GL_STREAM_READ);
            if (GL15C.glGetBufferParameteri(BUFFER, GL15C.GL_BUFFER_SIZE) != outputBytes) {
                throw new IllegalStateException("Could not allocate the GPU skinning capture buffer");
            }
            GL30C.glBindBufferBase(BUFFER, 0, feedback);
            GL20C.glUseProgram(program);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "GltfBones"), GltfSkinPaletteBuffer.TEXTURE_UNIT);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "GltfPass"), 1);
            GL11C.glEnable(GL30C.GL_RASTERIZER_DISCARD);
            if (query != 0) GL15C.glBeginQuery(PRIMITIVES_WRITTEN, query);
            try {
                GL30C.glBeginTransformFeedback(GL11C.GL_POINTS);
                try {
                    if (ranges == null) GL11C.glDrawArrays(GL11C.GL_POINTS, 0, geometry.vertexCount);
                    else {
                        for (int i = 0; i < ranges.length; i += 2) {
                            GL11C.glDrawArrays(GL11C.GL_POINTS, ranges[i], ranges[i + 1]);
                        }
                    }
                } finally {
                    GL30C.glEndTransformFeedback();
                }
            } finally {
                if (query != 0) GL15C.glEndQuery(PRIMITIVES_WRITTEN);
            }
            if (readback != null) {
                if (GL15C.glGetQueryObjectui(query, GL15C.GL_QUERY_RESULT) != readback.remaining() / FLOATS_PER_VERTEX) {
                    throw new IllegalStateException("Incomplete GPU skinning readback");
                }
                GL15C.glGetBufferSubData(BUFFER, 0L, readback);
            }
        } finally {
            GL30C.glBindVertexArray(previousVao);
            if (previousTarget != 0 && previousSize > 0) {
                GL30C.glBindBufferRange(BUFFER, 0, previousTarget, previousStart, previousSize);
            } else {
                GL30C.glBindBufferBase(BUFFER, 0, previousTarget);
            }
            GL15C.glBindBuffer(BUFFER, previousFeedback);
            if (!previousDiscard) GL11C.glDisable(GL30C.GL_RASTERIZER_DISCARD);
            GL20C.glUseProgram(previousProgram);
            GltfSkinPaletteBuffer.restore(previousTexture);
        }
    }
}
