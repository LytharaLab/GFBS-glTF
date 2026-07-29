package org.lytharalab.gfbs.gltf.api.client;

import org.lytharalab.gfbs.gltf.api.animation.AnimationController;
import org.lytharalab.gfbs.gltf.api.collision.GltfCollider;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class GltfInstance implements AutoCloseable {
    private final UUID id = UUID.randomUUID();
    private final GltfAsset asset;
    private final AnimationController animations;
    private final GltfRenderOptions renderOptions;
    private final GltfCollider collision;
    private final boolean[] nodeVisibility;
    private boolean visible = true;
    private int scene;

    public GltfInstance(GltfAsset asset) {
        this.asset = Objects.requireNonNull(asset, "asset");
        this.animations = new AnimationController(asset);
        this.renderOptions = new GltfRenderOptions();
        this.nodeVisibility = new boolean[asset.nodes().size()];
        Arrays.fill(nodeVisibility, true);
        this.scene = asset.defaultScene();
        this.collision = new GltfCollider(this);
    }

    public UUID id() { return id; }
    public GltfAsset asset() { return asset; }
    public AnimationController animations() { return animations; }
    public GltfRenderOptions renderOptions() { return renderOptions; }
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
        requireNode(node);
        return nodeVisibility[node];
    }
    /**
     * Hides or shows a node. A hidden node suppresses its complete child subtree.
     */
    public void setNodeVisible(int node, boolean visible) {
        requireNode(node);
        nodeVisibility[node] = visible;
    }
    /**
     * Hides or shows every node with the exact glTF name.
     *
     * @return the number of matching nodes
     */
    public int setNodeVisible(String name, boolean visible) {
        Objects.requireNonNull(name, "name");
        int changed = 0;
        for (int node = 0; node < asset.nodes().size(); node++) {
            if (asset.nodes().get(node).name().equals(name)) {
                nodeVisibility[node] = visible;
                changed++;
            }
        }
        return changed;
    }
    public void resetNodeVisibility() {
        Arrays.fill(nodeVisibility, true);
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

    private void requireNode(int node) {
        if (node < 0 || node >= nodeVisibility.length) throw new IndexOutOfBoundsException("node " + node);
    }
}
