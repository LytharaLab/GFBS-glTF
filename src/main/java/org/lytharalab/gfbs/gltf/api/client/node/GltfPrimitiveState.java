package org.lytharalab.gfbs.gltf.api.client.node;

import net.minecraft.client.renderer.RenderType;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderOptions;
import org.lytharalab.gfbs.gltf.api.client.material.GltfMaterialOverride;
import org.lytharalab.gfbs.gltf.api.client.material.GltfMaterialVariant;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Mutable render/collision/material state for one primitive occurrence. */
public final class GltfPrimitiveState {
    private final GltfNodeManager owner;
    private final GltfPrimitiveKey key;
    private final GltfPrimitive definition;
    private boolean visible = true;
    private boolean collisionEnabled = true;
    private boolean castShadows = true;
    private float alpha = 1.0f;
    private final float[] colorMultiplier = {1, 1, 1};
    private GltfRenderOptions.LightMode lightMode;
    private GltfRenderOptions.CullMode cullMode;
    private RenderType renderType;
    private GltfMaterialVariant materialVariant = GltfMaterialVariant.source();
    private GltfMaterial material;
    private int materialIndex;
    private final Map<GltfMaterialVariant, ResolvedMaterial> materialCache = new HashMap<>();
    private final Map<String, Object> parameters = new LinkedHashMap<>();

    GltfPrimitiveState(GltfNodeManager owner, GltfPrimitiveKey key, GltfPrimitive definition) {
        this.owner = owner;
        this.key = key;
        this.definition = definition;
        owner.resolveMaterial(this, materialVariant);
    }

    public GltfPrimitiveKey key() { return key; }
    public GltfPrimitive definition() { return definition; }
    public boolean visible() { return visible; }
    public GltfPrimitiveState visible(boolean value) { visible = value; changed(); return this; }
    public boolean collisionEnabled() { return collisionEnabled; }
    public GltfPrimitiveState collisionEnabled(boolean value) {
        collisionEnabled = value; owner.touchCollision(); return this;
    }
    public boolean castShadows() { return castShadows; }
    public GltfPrimitiveState castShadows(boolean value) { castShadows = value; changed(); return this; }

    public float alpha() { return alpha; }
    public GltfPrimitiveState alpha(float value) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException("Primitive alpha must be between 0 and 1");
        }
        alpha = value; changed(); return this;
    }

    public float[] colorMultiplier() { return colorMultiplier.clone(); }
    public GltfPrimitiveState colorMultiplier(float red, float green, float blue) {
        colorMultiplier[0] = nonNegative(red, "Red multiplier");
        colorMultiplier[1] = nonNegative(green, "Green multiplier");
        colorMultiplier[2] = nonNegative(blue, "Blue multiplier");
        changed(); return this;
    }

    public Optional<GltfRenderOptions.LightMode> lightMode() { return Optional.ofNullable(lightMode); }
    public GltfPrimitiveState lightMode(GltfRenderOptions.LightMode mode) { lightMode = mode; changed(); return this; }
    public Optional<GltfRenderOptions.CullMode> cullMode() { return Optional.ofNullable(cullMode); }
    public GltfPrimitiveState cullMode(GltfRenderOptions.CullMode mode) { cullMode = mode; changed(); return this; }
    public Optional<RenderType> renderType() { return Optional.ofNullable(renderType); }
    public GltfPrimitiveState renderType(RenderType value) { renderType = value; changed(); return this; }

    public GltfMaterialVariant materialVariant() { return materialVariant; }
    public GltfPrimitiveState materialVariant(GltfMaterialVariant variant) {
        owner.resolveMaterial(this, variant);
        changed();
        return this;
    }
    public GltfPrimitiveState material(int index) {
        return materialVariant(GltfMaterialVariant.material(index));
    }
    public GltfPrimitiveState material(String name) {
        return material(owner.requireMaterial(name));
    }
    public GltfPrimitiveState materialOverride(GltfMaterialOverride override) {
        return materialVariant(new GltfMaterialVariant(materialVariant.materialIndex(), override));
    }
    public GltfPrimitiveState variant(String name) {
        return materialVariant(owner.requireVariant(name));
    }
    public GltfPrimitiveState resetMaterial() {
        return materialVariant(GltfMaterialVariant.source());
    }

    public int effectiveMaterialIndex() { return materialIndex; }
    public GltfMaterial effectiveMaterial() { return material; }

    public GltfPrimitiveState parameter(String name, Object value) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Parameter name is blank");
        if (value == null) parameters.remove(name); else parameters.put(name, value);
        changed(); return this;
    }
    public Optional<Object> parameter(String name) { return Optional.ofNullable(parameters.get(name)); }
    public Map<String, Object> parameters() { return Map.copyOf(parameters); }

    public GltfPrimitiveState reset() {
        visible = collisionEnabled = castShadows = true;
        alpha = 1.0f;
        colorMultiplier[0] = colorMultiplier[1] = colorMultiplier[2] = 1.0f;
        lightMode = null;
        cullMode = null;
        renderType = null;
        parameters.clear();
        owner.resolveMaterial(this, GltfMaterialVariant.source());
        owner.touchCollision();
        return this;
    }

    void resolvedMaterial(GltfMaterialVariant variant, int index, GltfMaterial value) {
        materialVariant = variant;
        materialIndex = index;
        material = value;
        materialCache.put(variant, new ResolvedMaterial(index, value));
    }
    ResolvedMaterial cachedMaterial(GltfMaterialVariant variant) { return materialCache.get(variant); }
    float[] colorMultiplierInternal() { return colorMultiplier; }
    GltfNodeManager.PrimitiveSnapshot snapshot() {
        return new GltfNodeManager.PrimitiveSnapshot(
            visible, collisionEnabled, castShadows, alpha, colorMultiplier,
            lightMode, cullMode, renderType, materialVariant, parameters
        );
    }
    void restore(GltfNodeManager.PrimitiveSnapshot snapshot) {
        visible = snapshot.visible();
        collisionEnabled = snapshot.collisionEnabled();
        castShadows = snapshot.castShadows();
        alpha = snapshot.alpha();
        System.arraycopy(snapshot.colorMultiplier(), 0, colorMultiplier, 0, 3);
        lightMode = snapshot.lightMode();
        cullMode = snapshot.cullMode();
        renderType = snapshot.renderType();
        parameters.clear();
        parameters.putAll(snapshot.parameters());
        owner.resolveMaterial(this, snapshot.materialVariant());
    }
    private void changed() { owner.touch(); }
    private static float nonNegative(float value, String label) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(label + " must be finite and non-negative");
        }
        return value;
    }

    record ResolvedMaterial(int index, GltfMaterial material) {}
}
