package org.lytharalab.gfbs.gltf.api.animation;

import java.util.Arrays;

/** Immutable node mask used by an animation layer. */
public final class AnimationMask {
    private final boolean[] nodes;
    private final boolean all;

    private AnimationMask(boolean[] nodes) {
        this.nodes = nodes;
        boolean complete = true;
        for (boolean node : nodes) {
            if (!node) { complete = false; break; }
        }
        this.all = complete;
    }

    public static AnimationMask all(int nodeCount) {
        if (nodeCount < 0) throw new IllegalArgumentException("Node count must be non-negative");
        boolean[] nodes = new boolean[nodeCount];
        Arrays.fill(nodes, true);
        return new AnimationMask(nodes);
    }

    public static AnimationMask ofNodes(int nodeCount, int... includedNodes) {
        if (nodeCount < 0) throw new IllegalArgumentException("Node count must be non-negative");
        boolean[] nodes = new boolean[nodeCount];
        for (int node : includedNodes) {
            if (node < 0 || node >= nodeCount) throw new IndexOutOfBoundsException("node " + node);
            nodes[node] = true;
        }
        return new AnimationMask(nodes);
    }

    public int nodeCount() { return nodes.length; }
    public boolean includes(int node) { return nodes[node]; }

    /** Returns true when this mask includes every node. */
    public boolean isAll() { return all; }
}
