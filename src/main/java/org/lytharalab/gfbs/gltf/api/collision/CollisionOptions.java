package org.lytharalab.gfbs.gltf.api.collision;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class CollisionOptions {
    private boolean enabled;
    private boolean hideColliderNodes = true;
    private final Set<String> groups = new LinkedHashSet<>();
    private boolean includeDescendants = true;
    private float precision = 0.25f;
    private CollisionMode mode = CollisionMode.FAST;
    private boolean solid;
    private float margin;
    private long maxVoxels = 24_000_000L;
    private int maxBoxes = 40_000;
    private int asyncTriangleThreshold = 20_000;

    public boolean enabled() { return enabled; }
    public boolean hideColliderNodes() { return hideColliderNodes; }
    public Set<String> groups() { return Set.copyOf(groups); }
    public boolean includeDescendants() { return includeDescendants; }
    public float precision() { return precision; }
    public CollisionMode mode() { return mode; }
    public boolean solid() { return solid; }
    public float margin() { return margin; }
    public long maxVoxels() { return maxVoxels; }
    public int maxBoxes() { return maxBoxes; }
    public int asyncTriangleThreshold() { return asyncTriangleThreshold; }

    public CollisionOptions enabled(boolean value) { enabled = value; return this; }
    public CollisionOptions hideColliderNodes(boolean value) { hideColliderNodes = value; return this; }
    public CollisionOptions groups(String... names) {
        groups.clear();
        if (names != null) groups.addAll(Arrays.asList(names));
        groups.removeIf(Objects::isNull);
        return this;
    }
    public CollisionOptions groups(Collection<String> names) {
        groups.clear();
        if (names != null) groups.addAll(names);
        groups.removeIf(Objects::isNull);
        return this;
    }
    public CollisionOptions wholeModel() { groups.clear(); return this; }
    public CollisionOptions includeDescendants(boolean value) { includeDescendants = value; return this; }
    public CollisionOptions precision(float value) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            throw new IllegalArgumentException("Collision precision must be positive and finite");
        }
        precision = value;
        return this;
    }
    public CollisionOptions mode(CollisionMode value) {
        mode = Objects.requireNonNull(value, "mode");
        return this;
    }
    public CollisionOptions solid(boolean value) { solid = value; return this; }
    public CollisionOptions margin(float value) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException("Collision margin must be finite and non-negative");
        }
        margin = value;
        return this;
    }
    public CollisionOptions maxVoxels(long value) {
        if (value < 1) throw new IllegalArgumentException("Maximum voxels must be positive");
        maxVoxels = value;
        return this;
    }
    public CollisionOptions maxBoxes(int value) {
        if (value < 1) throw new IllegalArgumentException("Maximum boxes must be positive");
        maxBoxes = value;
        return this;
    }
    public CollisionOptions asyncTriangleThreshold(int value) {
        if (value < 0) throw new IllegalArgumentException("Async threshold must be non-negative");
        asyncTriangleThreshold = value;
        return this;
    }

    public CollisionOptions copy() {
        CollisionOptions copy = new CollisionOptions();
        copy.enabled = enabled;
        copy.hideColliderNodes = hideColliderNodes;
        copy.groups.addAll(groups);
        copy.includeDescendants = includeDescendants;
        copy.precision = precision;
        copy.mode = mode;
        copy.solid = solid;
        copy.margin = margin;
        copy.maxVoxels = maxVoxels;
        copy.maxBoxes = maxBoxes;
        copy.asyncTriangleThreshold = asyncTriangleThreshold;
        return copy;
    }

    public long signature() {
        long result = mode.ordinal();
        result = result * 31 + Float.floatToIntBits(precision);
        result = result * 31 + Float.floatToIntBits(margin);
        result = result * 31 + Long.hashCode(maxVoxels);
        result = result * 31 + maxBoxes;
        result = result * 31 + Boolean.hashCode(solid);
        result = result * 31 + Boolean.hashCode(includeDescendants);
        result = result * 31 + groups.hashCode();
        return result;
    }
}
