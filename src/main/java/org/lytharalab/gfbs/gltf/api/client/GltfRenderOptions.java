package org.lytharalab.gfbs.gltf.api.client;

import net.minecraft.client.renderer.RenderType;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Predicate;

public final class GltfRenderOptions {
    public enum CullMode { AUTO, FORCE_CULL, FORCE_NO_CULL }
    public enum LightMode { WORLD, FULLBRIGHT }

    private CullMode cullMode = CullMode.AUTO;
    private LightMode lightMode = LightMode.WORLD;
    private boolean frustumCulling = true;
    private boolean occlusionCulling;
    private boolean castShadows = true;
    private double maxRenderDistance;
    private float alpha = 1.0f;
    private RenderType overrideRenderType;
    private BiFunction<GltfRenderPart, GltfMaterial, RenderType> renderTypeFactory;
    private final Map<String, RenderType> nodeRenderTypes = new LinkedHashMap<>();
    private Predicate<GltfRenderPart> partFilter = part -> true;
    private boolean validateRenderTypeFormat = true;

    public CullMode cullMode() { return cullMode; }
    public LightMode lightMode() { return lightMode; }
    public boolean frustumCulling() { return frustumCulling; }
    public boolean occlusionCulling() { return occlusionCulling; }
    public boolean castShadows() { return castShadows; }
    public double maxRenderDistance() { return maxRenderDistance; }
    public float alpha() { return alpha; }
    public RenderType overrideRenderType() { return overrideRenderType; }
    public BiFunction<GltfRenderPart, GltfMaterial, RenderType> renderTypeFactory() { return renderTypeFactory; }
    public Map<String, RenderType> nodeRenderTypes() { return Map.copyOf(nodeRenderTypes); }
    public Predicate<GltfRenderPart> partFilter() { return partFilter; }
    public boolean validateRenderTypeFormat() { return validateRenderTypeFormat; }

    public GltfRenderOptions cull(CullMode mode) {
        cullMode = Objects.requireNonNull(mode, "mode");
        return this;
    }

    public GltfRenderOptions light(LightMode mode) {
        lightMode = Objects.requireNonNull(mode, "mode");
        return this;
    }

    public GltfRenderOptions frustumCulling(boolean enabled) {
        frustumCulling = enabled;
        return this;
    }

    public GltfRenderOptions occlusionCulling(boolean enabled) {
        occlusionCulling = enabled;
        return this;
    }

    /**
     * Controls participation in Oculus/Iris shadow-map rendering.
     *
     * <p>Enabled by default. This setting has no effect on Minecraft's vanilla blob shadows.</p>
     */
    public GltfRenderOptions castShadows(boolean enabled) {
        castShadows = enabled;
        return this;
    }

    public GltfRenderOptions maxRenderDistance(double distance) {
        if (!Double.isFinite(distance) || distance < 0.0) {
            throw new IllegalArgumentException("Maximum render distance must be finite and non-negative");
        }
        maxRenderDistance = distance;
        return this;
    }

    public GltfRenderOptions alpha(float value) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException("Alpha must be between 0 and 1");
        }
        alpha = value;
        return this;
    }

    public GltfRenderOptions renderType(RenderType type) {
        overrideRenderType = type;
        return this;
    }

    public GltfRenderOptions renderTypeFactory(
        BiFunction<GltfRenderPart, GltfMaterial, RenderType> factory
    ) {
        renderTypeFactory = factory;
        return this;
    }

    public GltfRenderOptions nodeRenderType(String nodeName, RenderType type) {
        Objects.requireNonNull(nodeName, "nodeName");
        if (type == null) nodeRenderTypes.remove(nodeName);
        else nodeRenderTypes.put(nodeName, type);
        return this;
    }

    public GltfRenderOptions partFilter(Predicate<GltfRenderPart> filter) {
        partFilter = Objects.requireNonNull(filter, "filter");
        return this;
    }

    public GltfRenderOptions validateRenderTypeFormat(boolean validate) {
        validateRenderTypeFormat = validate;
        return this;
    }

    public GltfRenderOptions clearRenderTypeOverrides() {
        overrideRenderType = null;
        renderTypeFactory = null;
        nodeRenderTypes.clear();
        return this;
    }

    public GltfRenderOptions copy() {
        GltfRenderOptions copy = new GltfRenderOptions();
        copy.cullMode = cullMode;
        copy.lightMode = lightMode;
        copy.frustumCulling = frustumCulling;
        copy.occlusionCulling = occlusionCulling;
        copy.castShadows = castShadows;
        copy.maxRenderDistance = maxRenderDistance;
        copy.alpha = alpha;
        copy.overrideRenderType = overrideRenderType;
        copy.renderTypeFactory = renderTypeFactory;
        copy.nodeRenderTypes.putAll(nodeRenderTypes);
        copy.partFilter = partFilter;
        copy.validateRenderTypeFormat = validateRenderTypeFormat;
        return copy;
    }
}
