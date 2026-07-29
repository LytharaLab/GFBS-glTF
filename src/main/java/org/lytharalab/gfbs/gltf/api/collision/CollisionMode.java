package org.lytharalab.gfbs.gltf.api.collision;

public enum CollisionMode {
    /** One transformed AABB per primitive. */
    BOUNDS,
    /** Voxelize local geometry once, cache it, then transform the boxes. */
    FAST,
    /** Transform current morph/skin geometry first, then voxelize in world space. */
    PRECISE
}
