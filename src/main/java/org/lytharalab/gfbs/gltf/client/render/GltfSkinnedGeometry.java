package org.lytharalab.gfbs.gltf.client.render;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitiveAccess;

import java.nio.ByteBuffer;
import java.util.BitSet;

import static com.mojang.blaze3d.vertex.VertexFormatElement.Type.FLOAT;
import static com.mojang.blaze3d.vertex.VertexFormatElement.Type.USHORT;
import static com.mojang.blaze3d.vertex.VertexFormatElement.Usage.GENERIC;

/** Bind-pose geometry and joint slots shared across instances, skins, and materials of one asset. */
final class GltfSkinnedGeometry implements AutoCloseable {
    static final VertexFormat FORMAT = new VertexFormat(ImmutableMap.<String, VertexFormatElement>builder()
        .put("Position", new VertexFormatElement(0, FLOAT, GENERIC, 3))
        .put("Normal", new VertexFormatElement(0, FLOAT, GENERIC, 3))
        .put("Color", new VertexFormatElement(0, FLOAT, GENERIC, 4))
        .put("TexCoords", new VertexFormatElement(0, FLOAT, GENERIC, 4))
        .put("Joints", new VertexFormatElement(0, USHORT, GENERIC, 4))
        .put("Weights", new VertexFormatElement(0, FLOAT, GENERIC, 4))
        .build());
    private static final int STRIDE = FORMAT.getVertexSize();

    final int vao;
    private final int indexBuffer;
    final int vertexCount;
    final int[] jointSlots;
    private final int vertexBuffer;
    private final int indexType;
    private final int drawCount;
    private final int mode;
    private final boolean weighted;

    private GltfSkinnedGeometry(int vao, int vertexBuffer, int indexBuffer, int indexType,
                                int vertexCount, int drawCount, int mode, int[] jointSlots, boolean weighted) {
        this.vao = vao;
        this.vertexBuffer = vertexBuffer;
        this.indexBuffer = indexBuffer;
        this.indexType = indexType;
        this.vertexCount = vertexCount;
        this.drawCount = drawCount;
        this.mode = mode;
        this.jointSlots = jointSlots;
        this.weighted = weighted;
    }

    static long estimatedBytes(GltfPrimitive primitive) {
        if (GltfPrimitiveAccess.normals(primitive) == null) {
            return (long) GltfMaterialBaker.triangleCount(primitive) * 3 * STRIDE;
        }
        long bytes = (long) primitive.vertexCount() * STRIDE;
        return GltfPrimitiveAccess.indices(primitive) == null ? bytes
            : bytes + (long) primitive.indexCount() * indexSize(primitive);
    }

    static GltfSkinnedGeometry compile(GltfPrimitive primitive, int maxPaletteJoints) {
        int[] joints = GltfPrimitiveAccess.joints(primitive);
        float[] weights = GltfPrimitiveAccess.weights(primitive);
        BitSet used = new BitSet();
        int positiveInfluences = 0;
        for (int i = 0; i < joints.length; i++) {
            if (weights[i] > 0.0f) {
                used.set(joints[i]);
                positiveInfluences++;
            }
        }
        int[] jointSlots = used.stream().toArray();
        if (jointSlots.length > maxPaletteJoints) return null;
        short[] compactIndices = new short[used.length()];
        for (int i = 0; i < jointSlots.length; i++) compactIndices[jointSlots[i]] = (short) i;

        boolean flat = GltfPrimitiveAccess.normals(primitive) == null;
        int[] indices = flat ? null : GltfPrimitiveAccess.indices(primitive);
        int vertices = flat ? Math.multiplyExact(GltfMaterialBaker.triangleCount(primitive), 3) : primitive.vertexCount();
        int vertexBytes = Math.multiplyExact(vertices, STRIDE);
        int indexBytes = indices == null ? 0 : Math.multiplyExact(indices.length, indexSize(primitive));
        int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        int vao = 0, vbo = 0, ebo = 0;
        boolean complete = false;
        try {
            vao = GL30C.glGenVertexArrays();
            vbo = GL15C.glGenBuffers();
            if (indices != null) ebo = GL15C.glGenBuffers();
            if (vao == 0 || vbo == 0 || (indices != null && ebo == 0)) {
                throw new IllegalStateException("Could not allocate skinned geometry");
            }
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            ByteBuffer vertexData = MemoryUtil.memAlloc(vertexBytes);
            try {
                writeVertices(vertexData, primitive, compactIndices, vertices, flat);
                vertexData.flip();
                upload(GL15C.GL_ARRAY_BUFFER, vertexData);
            } finally {
                MemoryUtil.memFree(vertexData);
            }
            setupAttributes();

            int indexType = indexSize(primitive) == Short.BYTES ? GL11C.GL_UNSIGNED_SHORT : GL11C.GL_UNSIGNED_INT;
            if (indices != null) {
                GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ebo);
                ByteBuffer indexData = MemoryUtil.memAlloc(indexBytes);
                try {
                    if (indexType == GL11C.GL_UNSIGNED_SHORT) {
                        for (int index : indices) indexData.putShort((short) index);
                    } else {
                        indexData.asIntBuffer().put(indices);
                        indexData.position(indexBytes);
                    }
                    indexData.flip();
                    upload(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexData);
                } finally {
                    MemoryUtil.memFree(indexData);
                }
            }

            int mode = flat ? GL11C.GL_TRIANGLES : switch (primitive.mode()) {
                case TRIANGLES -> GL11C.GL_TRIANGLES;
                case TRIANGLE_STRIP -> GL11C.GL_TRIANGLE_STRIP;
                case TRIANGLE_FAN -> GL11C.GL_TRIANGLE_FAN;
                default -> throw new IllegalArgumentException("GPU skinning requires triangles");
            };
            complete = true;
            return new GltfSkinnedGeometry(vao, vbo, ebo, indexType, vertices,
                indices == null ? vertices : indices.length, mode, jointSlots,
                positiveInfluences >= 2L * primitive.vertexCount() && jointSlots.length > 1);
        } finally {
            GL30C.glBindVertexArray(previousVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);
            if (!complete) delete(vao, vbo, ebo);
        }
    }

    private static void upload(int target, ByteBuffer data) {
        GL15C.glBufferData(target, data, GL15C.GL_STATIC_DRAW);
        if (GL15C.glGetBufferParameteri(target, GL15C.GL_BUFFER_SIZE) != data.remaining()) {
            throw new IllegalStateException("Could not upload skinned geometry");
        }
    }

    private static void setupAttributes() {
        int location = 0;
        long offset = 0;
        for (VertexFormatElement element : FORMAT.getElements()) {
            GL20C.glEnableVertexAttribArray(location);
            if (element.getType() == USHORT) {
                GL30C.glVertexAttribIPointer(location, element.getCount(), GL11C.GL_UNSIGNED_SHORT, STRIDE, offset);
            } else {
                GL20C.glVertexAttribPointer(location, element.getCount(), GL11C.GL_FLOAT, false, STRIDE, offset);
            }
            offset += element.getByteSize();
            location++;
        }
    }

    private static void writeVertices(ByteBuffer out, GltfPrimitive primitive, short[] compactIndices,
                                      int count, boolean flat) {
        float[] positions = GltfPrimitiveAccess.positions(primitive);
        float[] normals = GltfPrimitiveAccess.normals(primitive);
        float[] colors = GltfPrimitiveAccess.colors(primitive);
        float[] uv0 = GltfPrimitiveAccess.texCoords0(primitive);
        float[] uv1 = GltfPrimitiveAccess.texCoords1(primitive);
        int[] joints = GltfPrimitiveAccess.joints(primitive);
        float[] weights = GltfPrimitiveAccess.weights(primitive);
        int colorSize = colors == null ? 0 : colors.length / primitive.vertexCount();
        float[] faceNormal = flat ? new float[3] : null;
        for (int i = 0; i < count; i++) {
            int source = flat ? GltfMaterialBaker.vertex(primitive, i / 3, i % 3) : i;
            if (flat && i % 3 == 0) faceNormal(primitive, i / 3, faceNormal);
            int p = source * 3;
            out.putFloat(positions[p]).putFloat(positions[p + 1]).putFloat(positions[p + 2]);
            if (flat) {
                out.putFloat(faceNormal[0]).putFloat(faceNormal[1]).putFloat(faceNormal[2]);
            } else {
                out.putFloat(normals[p]).putFloat(normals[p + 1]).putFloat(normals[p + 2]);
            }
            int c = source * colorSize;
            out.putFloat(colors == null ? 1.0f : colors[c]);
            out.putFloat(colors == null ? 1.0f : colors[c + 1]);
            out.putFloat(colors == null ? 1.0f : colors[c + 2]);
            out.putFloat(colorSize == 4 ? colors[c + 3] : 1.0f);
            int uv = source * 2;
            out.putFloat(uv0 == null ? 0.0f : uv0[uv]).putFloat(uv0 == null ? 0.0f : uv0[uv + 1]);
            out.putFloat(uv1 == null ? 0.0f : uv1[uv]).putFloat(uv1 == null ? 0.0f : uv1[uv + 1]);
            int influence = source * 4;
            for (int j = influence; j < influence + 4; j++) {
                out.putShort(weights[j] > 0.0f ? compactIndices[joints[j]] : (short) 0);
            }
            for (int j = influence; j < influence + 4; j++) out.putFloat(weights[j]);
        }
    }

    static void faceNormal(GltfPrimitive primitive, int triangle, float[] output) {
        GltfVertexTransforms.faceNormal(GltfPrimitiveAccess.positions(primitive),
            GltfMaterialBaker.vertex(primitive, triangle, 0), GltfMaterialBaker.vertex(primitive, triangle, 1),
            GltfMaterialBaker.vertex(primitive, triangle, 2), output);
    }

    private static int indexSize(GltfPrimitive primitive) {
        return primitive.vertexCount() <= 65_536 ? Short.BYTES : Integer.BYTES;
    }

    void draw() {
        if (indexBuffer == 0) GL11C.glDrawArrays(mode, 0, drawCount);
        else GL11C.glDrawElements(mode, drawCount, indexType, 0L);
    }

    boolean worthCachingDeformation() {
        return weighted && vertexCount >= 2_048 && vertexCount <= drawCount;
    }

    @Override
    public void close() {
        delete(vao, vertexBuffer, indexBuffer);
    }

    private static void delete(int vao, int vertexBuffer, int indexBuffer) {
        GL30C.glDeleteVertexArrays(vao);
        GL15C.glDeleteBuffers(vertexBuffer);
        GL15C.glDeleteBuffers(indexBuffer);
    }
}
