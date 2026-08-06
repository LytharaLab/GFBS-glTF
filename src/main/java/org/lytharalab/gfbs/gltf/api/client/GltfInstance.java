package org.lytharalab.gfbs.gltf.api.client;

import org.lytharalab.gfbs.gltf.api.animation.AnimationController;
import org.lytharalab.gfbs.gltf.api.client.node.GltfNodeManager;
import org.lytharalab.gfbs.gltf.api.collision.GltfCollider;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;

import java.util.Objects;
import java.util.UUID;

public final class GltfInstance implements AutoCloseable {
    private final UUID id = UUID.randomUUID();
    private final GltfAsset asset;
    private final AnimationController animations;
    private final GltfRenderOptions renderOptions;
    private final GltfNodeManager nodes;
    private final GltfCollider collision;
    private boolean visible = true;
    private int scene;

    public GltfInstance(GltfAsset asset) {
        this.asset = Objects.requireNonNull(asset, "asset");
        this.animations = new AnimationController(asset);
        this.renderOptions = new GltfRenderOptions();
        this.nodes = new GltfNodeManager(asset);
        this.scene = asset.defaultScene();
        this.collision = new GltfCollider(this);
    }

    public UUID id() { return id; }
    public GltfAsset asset() { return asset; }
    public AnimationController animations() { return animations; }
    public GltfRenderOptions renderOptions() { return renderOptions; }
    /** Complete per-instance node, primitive, transform and material state graph. */
    public GltfNodeManager nodes() { return nodes; }
    public GltfCollider collision() { return collision; }
    public boolean visible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
    public int scene() { return scene; }
    public void setScene(int scene) {
        if (scene < 0 || scene >= asset.scenes().size()) throw new IndexOutOfBoundsException("scene");
        this.scene = scene;
    }
    /**
     * Returns whether a node and its subtree are enabled for rendering.
     */
    public boolean nodeVisible(int node) {
        return nodes.node(node).subtreeVisible();
    }
    /**
     * Hides or shows a node. A hidden node suppresses its complete child subtree.
     */
    public void setNodeVisible(int node, boolean visible) {
        nodes.node(node).subtreeVisible(visible);
    }
    /**
     * Hides or shows every node with the exact glTF name.
     *
     * @return the number of matching nodes
     */
    public int setNodeVisible(String name, boolean visible) {
        Objects.requireNonNull(name, "name");
        return nodes.forEachNode(name, node -> node.subtreeVisible(visible));
    }
    public void resetNodeVisibility() {
        for (var node : nodes.all()) node.subtreeVisible(true);
    }
    public void update(float deltaSeconds) {
        if (!Float.isFinite(deltaSeconds)) throw new IllegalArgumentException("Delta time must be finite");
        animations.update(deltaSeconds);
        collision.update();
    }

    @Override
    public void close() {
        collision.close();
    }
}
