package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import org.lytharalab.gfbs.gltf.api.model.GltfBounds;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class GltfOcclusionCuller {
    static final GltfOcclusionCuller INSTANCE = new GltfOcclusionCuller();
    private static final int NONE = 0;
    private final Map<QueryKey, Query> queries = new HashMap<>();
    private boolean supported = true;
    private int frame;

    private GltfOcclusionCuller() {
    }

    void beginFrame() {
        frame++;
    }

    boolean wasVisible(QueryKey key) {
        if (!supported) return true;
        Query query = queries.get(key);
        if (query == null) return true;
        poll(query);
        return query.lastVisible;
    }

    void issue(QueryKey key, Matrix4f projection, Matrix4f modelView, GltfBounds bounds) {
        if (!supported || projection == null || !bounds.valid()) return;
        try {
            Query query = queries.computeIfAbsent(key, ignored -> new Query());
            poll(query);
            if (!query.pending) {
                if (query.id == NONE) query.id = GL33.glGenQueries();
                GL33.glBeginQuery(GL33.GL_ANY_SAMPLES_PASSED, query.id);
                drawBox(new Matrix4f(projection).mul(modelView), bounds);
                GL33.glEndQuery(GL33.GL_ANY_SAMPLES_PASSED);
                query.pending = true;
            }
            query.lastFrame = frame;
        } catch (Throwable failure) {
            supported = false;
            dispose();
        }
    }

    void evictStale() {
        queries.entrySet().removeIf(entry -> {
            Query query = entry.getValue();
            if (frame - query.lastFrame <= 120) return false;
            delete(query);
            return true;
        });
    }

    void dispose() {
        for (Query query : queries.values()) delete(query);
        queries.clear();
    }

    private static void poll(Query query) {
        if (!query.pending || query.id == NONE) return;
        if (GL15.glGetQueryObjecti(query.id, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) return;
        query.lastVisible = GL15.glGetQueryObjecti(query.id, GL15.GL_QUERY_RESULT) != 0;
        query.pending = false;
    }

    private static void delete(Query query) {
        if (query.id == NONE) return;
        try {
            GL15.glDeleteQueries(query.id);
        } catch (Throwable ignored) {
        }
        query.id = NONE;
    }

    static State beginQueries() {
        State state = State.capture();
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        return state;
    }

    private static void drawBox(Matrix4f matrix, GltfBounds bounds) {
        RenderSystem.setShader(GameRenderer::getPositionShader);
        Tesselator tesselator = RenderSystem.renderThreadTesselator();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
        float x0 = bounds.minX();
        float y0 = bounds.minY();
        float z0 = bounds.minZ();
        float x1 = bounds.maxX();
        float y1 = bounds.maxY();
        float z1 = bounds.maxZ();
        float[][] corners = {
            {x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0},
            {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}
        };
        int[][] triangles = {
            {0, 1, 2}, {0, 2, 3}, {5, 4, 7}, {5, 7, 6},
            {4, 0, 3}, {4, 3, 7}, {1, 5, 6}, {1, 6, 2},
            {4, 5, 1}, {4, 1, 0}, {3, 2, 6}, {3, 6, 7}
        };
        for (int[] triangle : triangles) {
            for (int vertex : triangle) {
                float[] corner = corners[vertex];
                builder.vertex(matrix, corner[0], corner[1], corner[2]).endVertex();
            }
        }
        BufferUploader.drawWithShader(builder.end());
    }

    record QueryKey(UUID instance, int node, int mesh, int primitive) {
    }

    private static final class Query {
        int id;
        boolean lastVisible = true;
        boolean pending;
        int lastFrame;
    }

    record State(boolean depthTest, boolean depthMask, boolean cull,
                 boolean red, boolean green, boolean blue, boolean alpha) {
        static State capture() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer mask = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                return new State(
                    GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                    GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                    GL11.glIsEnabled(GL11.GL_CULL_FACE),
                    mask.get(0) != 0,
                    mask.get(1) != 0,
                    mask.get(2) != 0,
                    mask.get(3) != 0
                );
            }
        }

        void restore() {
            RenderSystem.colorMask(red, green, blue, alpha);
            RenderSystem.depthMask(depthMask);
            if (depthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        }
    }
}
