package org.lytharalab.gfbs.gltf.api.client.node;

import net.minecraft.client.renderer.RenderType;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderOptions;
import org.lytharalab.gfbs.gltf.api.client.material.GltfMaterialOverride;
import org.lytharalab.gfbs.gltf.api.client.material.GltfMaterialVariant;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;

import java.util.LinkedHashMap;
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
    private Map<GltfMaterialVariant, ResolvedMaterial> materialCache;
    private Map<String, Object> parameters;

    GltfPrimitiveState(GltfNodeManager owner, GltfPrimitiveKey key, GltfPrimitive definition) {
        this.owner = owner;
        this.key = key;
        this.definition = definition;
        this.materialIndex = definition.material();
        this.material = owner.asset().materials().get(materialIndex);
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
    public float colorRed() { return colorMultiplier[0]; }
    public float colorGreen() { return colorMultiplier[1]; }
    public float colorBlue() { return colorMultiplier[2]; }
    public GltfRenderOptions.LightMode lightModeOrNull() { return lightMode; }
    public GltfRenderOptions.CullMode cullModeOrNull() { return cullMode; }
    public RenderType renderTypeOrNull() { return renderType; }
    public GltfPrimitiveState colorMultiplier(float red, float green, float blue) {
        float nextRed = nonNegative(red, "Red multiplier");
        float nextGreen = nonNegative(green, "Green multiplier");
        float nextBlue = nonNegative(blue, "Blue multiplier");
        colorMultiplier[0] = nextRed;
        colorMultiplier[1] = nextGreen;
        colorMultiplier[2] = nextBlue;
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
        if (value == null) {
            if (parameters != null) parameters.remove(name);
        } else {
            if (parameters == null) parameters = new LinkedHashMap<>();
            parameters.put(name, value);
        }
        changed(); return this;
    }
    public Optional<Object> parameter(String name) {
        return Optional.ofNullable(parameters == null ? null : parameters.get(name));
    }
    public Map<String, Object> parameters() { return parameters == null ? Map.of() : Map.copyOf(parameters); }

    public GltfPrimitiveState reset() {
        visible = collisionEnabled = castShadows = true;
        alpha = 1.0f;
        colorMultiplier[0] = colorMultiplier[1] = colorMultiplier[2] = 1.0f;
        lightMode = null;
        cullMode = null;
        renderType = null;
        parameters = null;
        materialCache = null;
        owner.resolveMaterial(this, GltfMaterialVariant.source());
        owner.touchCollision();
        return this;
    }

    void resolvedMaterial(GltfMaterialVariant variant, int index, GltfMaterial value) {
        materialVariant = variant;
        materialIndex = index;
        material = value;
        if (!variant.override().isEmpty()) {
            if (materialCache == null) materialCache = new LinkedHashMap<>(8, 0.75f, true);
            if (materialCache.size() >= 16 && !materialCache.containsKey(variant)) {
                materialCache.remove(materialCache.keySet().iterator().next());
            }
            materialCache.put(variant, new ResolvedMaterial(index, value));
        }
    }
    ResolvedMaterial cachedMaterial(GltfMaterialVariant variant) {
        return materialCache == null ? null : materialCache.get(variant);
    }
    float[] colorMultiplierInternal() { return colorMultiplier; }
    GltfNodeManager.PrimitiveSnapshot snapshot() {
        return new GltfNodeManager.PrimitiveSnapshot(
            visible, collisionEnabled, castShadows, alpha, colorMultiplier,
            lightMode, cullMode, renderType, materialVariant, parameters == null ? Map.of() : parameters
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
        parameters = snapshot.parameters().isEmpty() ? null : new LinkedHashMap<>(snapshot.parameters());
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
