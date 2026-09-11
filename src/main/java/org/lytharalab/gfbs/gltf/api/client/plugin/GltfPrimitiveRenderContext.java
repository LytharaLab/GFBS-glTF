package org.lytharalab.gfbs.gltf.api.client.plugin;

import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lytharalab.gfbs.gltf.api.client.GltfInstance;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderContext;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderPart;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;

import java.util.Objects;

/** Read-only primitive state supplied to a custom render pass. */
public final class GltfPrimitiveRenderContext {
    private final GltfInstance instance;
    private final GltfRenderContext renderContext;
    private final GltfRenderPart part;
    private final GltfPrimitive primitive;
    private final GltfMaterial material;
    private final ResourceLocation baseColorTexture;
    private final ResourceLocation emissiveTexture;
    private final Matrix4f modelMatrix;
    private final boolean shaderPackActive;
    private final boolean shadowPass;
    private final boolean triangleGeometry;
    private final boolean dynamicGeometry;
    private final boolean cull;
    private final int packedLight;
    private final int packedOverlay;
    private final float red;
    private final float green;
    private final float blue;
    private final float alpha;

    public GltfPrimitiveRenderContext(
        GltfInstance instance, GltfRenderContext renderContext, GltfRenderPart part,
        GltfPrimitive primitive, GltfMaterial material, ResourceLocation baseColorTexture,
        ResourceLocation emissiveTexture, Matrix4f modelMatrix, boolean shaderPackActive,
        boolean shadowPass, boolean triangleGeometry, boolean dynamicGeometry, boolean cull,
        int packedLight, int packedOverlay, float red, float green, float blue, float alpha
    ) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.renderContext = renderContext;
        this.part = Objects.requireNonNull(part, "part");
        this.primitive = Objects.requireNonNull(primitive, "primitive");
        this.material = Objects.requireNonNull(material, "material");
        this.baseColorTexture = Objects.requireNonNull(baseColorTexture, "baseColorTexture");
        this.emissiveTexture = Objects.requireNonNull(emissiveTexture, "emissiveTexture");
        this.modelMatrix = new Matrix4f(Objects.requireNonNull(modelMatrix, "modelMatrix"));
        this.shaderPackActive = shaderPackActive;
        this.shadowPass = shadowPass;
        this.triangleGeometry = triangleGeometry;
        this.dynamicGeometry = dynamicGeometry;
        this.cull = cull;
        this.packedLight = packedLight;
        this.packedOverlay = packedOverlay;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    public GltfInstance instance() { return instance; }
    public GltfRenderContext renderContext() { return renderContext; }
    public GltfRenderPart part() { return part; }
    public GltfPrimitive primitive() { return primitive; }
    public GltfMaterial material() { return material; }
    public ResourceLocation baseColorTexture() { return baseColorTexture; }
    public ResourceLocation emissiveTexture() { return emissiveTexture; }
    public Matrix4f modelMatrix() { return new Matrix4f(modelMatrix); }
    public boolean shaderPackActive() { return shaderPackActive; }
    public boolean shadowPass() { return shadowPass; }
    public boolean triangleGeometry() { return triangleGeometry; }
    public boolean dynamicGeometry() { return dynamicGeometry; }
    public boolean cull() { return cull; }
    public int packedLight() { return packedLight; }
    public int packedOverlay() { return packedOverlay; }
    public float red() { return red; }
    public float green() { return green; }
    public float blue() { return blue; }
    public float alpha() { return alpha; }
}
