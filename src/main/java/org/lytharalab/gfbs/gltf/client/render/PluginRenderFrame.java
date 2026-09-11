package org.lytharalab.gfbs.gltf.client.render;

import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.lytharalab.gfbs.gltf.api.client.GltfInstance;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderContext;
import org.lytharalab.gfbs.gltf.api.client.plugin.GltfBuiltInRenderPass;
import org.lytharalab.gfbs.gltf.api.client.plugin.GltfCustomRenderPass;
import org.lytharalab.gfbs.gltf.api.client.plugin.GltfRenderFrame;
import org.lytharalab.gfbs.gltf.api.client.plugin.GltfRenderStage;

import java.util.EnumSet;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class PluginRenderFrame implements GltfRenderFrame {
    @FunctionalInterface
    interface CustomPassDrawer {
        void draw(GltfCustomRenderPass pass);
    }

    private final GltfInstance instance;
    private final MultiBufferSource buffers;
    private final int packedLight;
    private final int packedOverlay;
    private final GltfRenderContext context;
    private final boolean shadowPass;
    private final boolean shaderPackActive;
    private final Matrix4f baseTransform;
    private final CustomPassDrawer drawer;
    private final BooleanSupplier flusher;
    private final EnumSet<GltfBuiltInRenderPass> suppressed =
        EnumSet.noneOf(GltfBuiltInRenderPass.class);
    private GltfRenderStage stage = GltfRenderStage.BEFORE_BUILTIN_PASSES;
    private boolean open = true;

    PluginRenderFrame(GltfInstance instance, MultiBufferSource buffers, int packedLight,
                      int packedOverlay, GltfRenderContext context, boolean shadowPass,
                      boolean shaderPackActive, Matrix4f baseTransform, CustomPassDrawer drawer,
                      BooleanSupplier flusher) {
        this.instance = instance;
        this.buffers = buffers;
        this.packedLight = packedLight;
        this.packedOverlay = packedOverlay;
        this.context = context;
        this.shadowPass = shadowPass;
        this.shaderPackActive = shaderPackActive;
        this.baseTransform = new Matrix4f(baseTransform);
        this.drawer = drawer;
        this.flusher = flusher;
    }

    @Override public GltfInstance instance() { return instance; }
    @Override public MultiBufferSource buffers() { return buffers; }
    @Override public int packedLight() { return packedLight; }
    @Override public int packedOverlay() { return packedOverlay; }
    @Override public GltfRenderContext context() { return context; }
    @Override public boolean shadowPass() { return shadowPass; }
    @Override public boolean shaderPackActive() { return shaderPackActive; }
    @Override public GltfRenderStage stage() { return stage; }
    @Override public Matrix4f baseTransform() { return new Matrix4f(baseTransform); }

    @Override
    public void draw(GltfCustomRenderPass pass) {
        ensureOpen();
        pass = Objects.requireNonNull(pass, "pass");
        Objects.requireNonNull(pass.id(), "pass.id()");
        drawer.draw(pass);
    }

    @Override
    public boolean flush() {
        ensureOpen();
        return flusher.getAsBoolean();
    }

    @Override
    public void suppress(GltfBuiltInRenderPass pass) {
        ensureOpen();
        if (stage != GltfRenderStage.BEFORE_BUILTIN_PASSES) {
            throw new IllegalStateException("Built-in passes can only be suppressed before rendering");
        }
        suppressed.add(Objects.requireNonNull(pass, "pass"));
    }

    @Override
    public boolean isSuppressed(GltfBuiltInRenderPass pass) {
        return suppressed.contains(Objects.requireNonNull(pass, "pass"));
    }

    void stage(GltfRenderStage value) {
        stage = value;
    }

    void close() {
        open = false;
    }

    private void ensureOpen() {
        if (!open) throw new IllegalStateException("The glTF render frame is no longer active");
    }
}
