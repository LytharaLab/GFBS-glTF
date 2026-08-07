package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lytharalab.gfbs.gltf.GFBSglTF;
import org.lytharalab.gfbs.gltf.api.animation.ModelPose;
import org.lytharalab.gfbs.gltf.api.client.GltfInstance;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderContext;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderOptions;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderPart;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderTypes;
import org.lytharalab.gfbs.gltf.api.client.node.GltfNodeState;
import org.lytharalab.gfbs.gltf.api.client.node.GltfPrimitiveState;
import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitiveAccess;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;
import org.lytharalab.gfbs.gltf.api.model.GltfSkinAccess;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Universal glTF renderer with a resident-GPU fast path for rigid animated geometry.
 *
 * <p>Rigid primitives are compiled once into node-local {@link com.mojang.blaze3d.vertex.VertexBuffer}
 * objects and subsequently animated only by changing their model-view matrix. Skin/morph/custom
 * cases retain a compatibility CPU path, but that path is also allocation-conscious and no longer
 * clones the complete primitive arrays every pass.</p>
 */
public final class EntityGltfRenderer {
    private static final Set<RenderType> WARNED_TYPES = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<RenderScratch> SCRATCH =
        ThreadLocal.withInitial(RenderScratch::new);
    private static final ThreadLocal<ImmediateBuffers> IMMEDIATE =
        ThreadLocal.withInitial(ImmediateBuffers::new);

    private EntityGltfRenderer() {}

    public static void render(GltfInstance instance, PoseStack poseStack, int packedLight,
                              int packedOverlay, GltfRenderContext context) {
        ImmediateBuffers reusable = IMMEDIATE.get();
        if (reusable.busy) {
            BufferBuilder builder = new BufferBuilder(8192);
            MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(builder);
            render(instance, poseStack, buffers, packedLight, packedOverlay, context);
            buffers.endBatch();
            return;
        }
        reusable.busy = true;
        try {
            render(instance, poseStack, reusable.buffers, packedLight, packedOverlay, context);
            reusable.buffers.endBatch();
        } finally {
            reusable.busy = false;
        }
    }

    public static void render(GltfInstance instance, PoseStack poseStack, MultiBufferSource buffers,
                              int packedLight, int packedOverlay, GltfRenderContext context) {
        if (!instance.visible() || instance.asset().scenes().isEmpty()) return;
        RenderSystem.assertOnRenderThread();

        GltfAsset asset = instance.asset();
        GltfGpuModel gpu = GltfGpuCache.getInstance().get(asset);
        GltfRenderOptions options = instance.renderOptions();
        boolean shadowPass = OculusCompat.shadowPass();
        if (shadowPass && !options.castShadows()) return;
        boolean shaderPack = !shadowPass && OculusCompat.shadersEnabled();

        ModelPose pose = instance.animations().pose();
        float[] world = instance.nodes().computeWorldMatricesView(
            pose, instance.animations().poseRevision()
        );
        GltfGpuModel.ScenePlan scene = gpu.scenePlan(instance.scene());
        Matrix4f base = poseStack.last().pose();

        GltfOcclusionCuller occlusion = GltfOcclusionCuller.INSTANCE;
        boolean useOcclusion = !shadowPass && options.occlusionCulling()
            && context != null && context.projectionView() != null;
        if (useOcclusion) occlusion.beginFrame();

        renderPass(
            instance, gpu, scene, buffers, options, context, shaderPack, shadowPass,
            false, pose, world, base, packedLight, packedOverlay, useOcclusion, occlusion
        );
        if (!shadowPass) {
            renderPass(
                instance, gpu, scene, buffers, options, context, shaderPack, false,
                true, pose, world, base, packedLight, packedOverlay, useOcclusion, occlusion
            );
        }

        if (useOcclusion) issueOcclusionQueries(
            instance, scene, options, context, world, base, occlusion
        );
    }

    private static void renderPass(
        GltfInstance instance,
        GltfGpuModel gpu,
        GltfGpuModel.ScenePlan scene,
        MultiBufferSource buffers,
        GltfRenderOptions options,
        GltfRenderContext context,
        boolean shaderPack,
        boolean shadowPass,
        boolean emissivePass,
        ModelPose pose,
        float[] world,
        Matrix4f base,
        int packedLight,
        int packedOverlay,
        boolean useOcclusion,
        GltfOcclusionCuller occlusion
    ) {
        RenderScratch scratch = SCRATCH.get();
        GltfGpuModel.NodePlan[] nodes = scene.nodes;
        for (int planIndex = 0; planIndex < nodes.length; planIndex++) {
            GltfGpuModel.NodePlan nodePlan = nodes[planIndex];
            int nodeIndex = nodePlan.nodeIndex;
            GltfNodeState nodeState = instance.nodes().node(nodeIndex);
            if (!nodeState.subtreeVisible() || instance.collision().isNodeHidden(nodeIndex)) {
                planIndex = nodePlan.subtreeEndExclusive - 1;
                continue;
            }
            if (!nodeState.selfVisible()) continue;
            if (shadowPass && !nodeState.castShadows()) continue;

            scratch.world.set(world, nodeIndex * 16);
            scratch.model.set(base).mul(scratch.world);
            boolean normalReady = false;
            float[] skin = null;
            int jointCount = 0;
            boolean skinReady = false;

            for (GltfGpuModel.PrimitivePlan primitivePlan : nodePlan.primitives) {
                GltfPrimitive primitive = primitivePlan.primitive;
                GltfPrimitiveState primitiveState = instance.nodes().primitive(primitivePlan.key);
                if (!primitiveState.visible()) continue;
                if (shadowPass && (!GltfGeometryPipeline.isTriangleMode(primitive.mode())
                    || !primitiveState.castShadows())) continue;

                float[] morphWeights = primitive.morphTargets().isEmpty() ? null
                    : instance.nodes().resolveMorphWeightsView(nodeIndex, primitivePlan.mesh, pose);
                boolean activeMorph = GltfVertexTransforms.hasActiveMorph(primitive, morphWeights);
                boolean skinnedGeometry = nodePlan.node.skin() >= 0
                    && GltfPrimitiveAccess.joints(primitive) != null
                    && GltfPrimitiveAccess.weights(primitive) != null;
                boolean dynamicGeometry = activeMorph || skinnedGeometry;

                GltfMaterial material = primitiveState.effectiveMaterial();
                if (emissivePass && !GltfGeometryPipeline.hasVisibleEmission(material)) continue;
                GltfRenderPart part = null;
                if (options.hasPartFilterOverride()) {
                    part = part(primitivePlan, primitiveState);
                    if (!options.partFilter().test(part)) continue;
                }
                if (!shadowPass
                    && !GltfPrimitiveCuller.isVisible(
                        primitive, scratch.model, context, options, dynamicGeometry
                    )) {
                    continue;
                }

                GltfOcclusionCuller.QueryKey queryKey = null;
                if (useOcclusion && !dynamicGeometry) {
                    queryKey = new GltfOcclusionCuller.QueryKey(
                        instance.id(), nodeIndex, primitivePlan.meshIndex,
                        primitivePlan.primitiveIndex
                    );
                    if (!occlusion.wasVisible(queryKey)) continue;
                }

                GltfRenderOptions.CullMode cullMode = resolveCullMode(
                    options, nodeState, primitiveState
                );
                float red = nodeState.colorRed() * primitiveState.colorRed();
                float green = nodeState.colorGreen() * primitiveState.colorGreen();
                float blue = nodeState.colorBlue() * primitiveState.colorBlue();
                float alpha = options.alpha() * nodeState.alpha() * primitiveState.alpha();
                GltfRenderOptions.LightMode lightMode = resolveLightMode(
                    options, nodeState, primitiveState
                );
                boolean fullBright = lightMode == GltfRenderOptions.LightMode.FULLBRIGHT;
                int light = shadowPass || emissivePass || fullBright || material.unlit()
                    ? LightTexture.FULL_BRIGHT : packedLight;

                boolean noCustomRenderType = primitiveState.renderTypeOrNull() == null
                    && !options.hasRenderTypeOverrides();
                boolean rigidFast = !dynamicGeometry
                    && GltfGeometryPipeline.isTriangleMode(primitive.mode())
                    && (shadowPass || material.alphaMode() != AlphaMode.BLEND)
                    && (shadowPass || noCustomRenderType);

                RenderType renderType;
                if (emissivePass) {
                    ResourceLocation texture = gpu.emissiveTexture(material);
                    renderType = resolveEmissiveRenderType(primitive, material, texture, cullMode);
                } else if (shadowPass) {
                    ResourceLocation texture = gpu.materialTexture(material, false);
                    renderType = resolveShadowRenderType(material, texture, cullMode);
                } else {
                    ResourceLocation texture = gpu.materialTexture(material, shaderPack);
                    if (part == null && (options.renderTypeFactory() != null
                        || primitiveState.renderTypeOrNull() != null)) {
                        part = part(primitivePlan, primitiveState);
                    }
                    renderType = resolveRenderType(
                        part, primitivePlan, material, texture, options, primitiveState, cullMode
                    );
                }

                if (rigidFast) {
                    GltfGeometryPipeline.PassKind passKind = emissivePass
                        ? GltfGeometryPipeline.PassKind.EMISSIVE
                        : GltfGeometryPipeline.PassKind.BASE;
                    GltfGpuPrimitive resident = gpu.geometry(
                        primitivePlan, material, passKind
                    );
                    if (resident != null) {
                        boolean transformLights = !shadowPass && !emissivePass
                            && !fullBright && !material.unlit();
                        float drawRed = emissivePass
                            ? red * material.emissiveStrength() * alpha : red;
                        float drawGreen = emissivePass
                            ? green * material.emissiveStrength() * alpha : green;
                        float drawBlue = emissivePass
                            ? blue * material.emissiveStrength() * alpha : blue;
                        resident.draw(
                            renderType, scratch.model, transformLights, light, packedOverlay,
                            drawRed, drawGreen, drawBlue, emissivePass ? 1.0f : alpha
                        );
                        continue;
                    }
                }

                if (!normalReady) {
                    scratch.normal.set(scratch.model);
                    float determinant = scratch.normal.determinant();
                    if (Float.isFinite(determinant) && Math.abs(determinant) > 1.0e-10f) {
                        scratch.normal.invert().transpose();
                    } else {
                        scratch.normal.identity();
                    }
                    normalReady = true;
                }
                if (!skinReady && skinnedGeometry) {
                    GltfSkin gltfSkin = instance.asset().skins().get(nodePlan.node.skin());
                    jointCount = GltfSkinAccess.joints(gltfSkin).length;
                    skin = instance.nodes().computeSkinPaletteView(
                        gltfSkin, nodeIndex, pose, instance.animations().poseRevision()
                    );
                    skinReady = true;
                }
                GltfGeometryPipeline.MaterialPass materialPass = emissivePass
                    ? GltfGeometryPipeline.MaterialPass.emissive(material, red, green, blue)
                    : GltfGeometryPipeline.MaterialPass.base(material, red, green, blue);
                GltfGeometryPipeline.emit(
                    buffers.getBuffer(renderType), primitive, material, materialPass,
                    scratch.model, scratch.normal, skin, jointCount, morphWeights,
                    light, packedOverlay, alpha
                );
            }
        }
    }

    private static void issueOcclusionQueries(
        GltfInstance instance,
        GltfGpuModel.ScenePlan scene,
        GltfRenderOptions options,
        GltfRenderContext context,
        float[] world,
        Matrix4f base,
        GltfOcclusionCuller occlusion
    ) {
        GltfOcclusionCuller.State state = GltfOcclusionCuller.beginQueries();
        RenderScratch scratch = SCRATCH.get();
        try {
            Matrix4f projection = context.projectionView();
            GltfGpuModel.NodePlan[] nodes = scene.nodes;
            for (int planIndex = 0; planIndex < nodes.length; planIndex++) {
                GltfGpuModel.NodePlan nodePlan = nodes[planIndex];
                int nodeIndex = nodePlan.nodeIndex;
                GltfNodeState nodeState = instance.nodes().node(nodeIndex);
                if (!nodeState.subtreeVisible() || instance.collision().isNodeHidden(nodeIndex)) {
                    planIndex = nodePlan.subtreeEndExclusive - 1;
                    continue;
                }
                if (!nodeState.selfVisible()) continue;
                scratch.world.set(world, nodeIndex * 16);
                scratch.model.set(base).mul(scratch.world);
                for (GltfGpuModel.PrimitivePlan plan : nodePlan.primitives) {
                    GltfPrimitive primitive = plan.primitive;
                    GltfPrimitiveState primitiveState = instance.nodes().primitive(plan.key);
                    if (!primitiveState.visible() || primitive.hasDynamicGeometry()) continue;
                    if (!GltfPrimitiveCuller.isVisible(primitive, scratch.model, context, options)) {
                        continue;
                    }
                    GltfRenderPart part = null;
                    if (options.hasPartFilterOverride()) {
                        part = part(plan, primitiveState);
                        if (!options.partFilter().test(part)) continue;
                    }
                    occlusion.issue(
                        new GltfOcclusionCuller.QueryKey(
                            instance.id(), nodeIndex, plan.meshIndex, plan.primitiveIndex
                        ),
                        projection, scratch.model, primitive.bounds()
                    );
                }
            }
            occlusion.evictStale();
        } finally {
            state.restore();
        }
    }

    private static GltfRenderPart part(
        GltfGpuModel.PrimitivePlan plan, GltfPrimitiveState state
    ) {
        return new GltfRenderPart(
            plan.nodeIndex, plan.nodeName, plan.meshIndex, plan.primitiveIndex,
            state.effectiveMaterialIndex()
        );
    }

    private static RenderType resolveShadowRenderType(
        GltfMaterial material, ResourceLocation texture, GltfRenderOptions.CullMode cullMode
    ) {
        boolean cull = cull(cullMode, material);
        return material.alphaMode() == AlphaMode.OPAQUE
            ? GltfRenderTypes.solid(texture, cull)
            : GltfRenderTypes.cutout(texture, cull);
    }

    private static RenderType resolveRenderType(
        GltfRenderPart part,
        GltfGpuModel.PrimitivePlan plan,
        GltfMaterial material,
        ResourceLocation texture,
        GltfRenderOptions options,
        GltfPrimitiveState primitiveState,
        GltfRenderOptions.CullMode cullMode
    ) {
        GltfPrimitive primitive = plan.primitive;
        RenderType custom = primitiveState.renderTypeOrNull();
        if (custom == null) custom = options.nodeRenderType(plan.nodeName);
        if (custom == null && options.renderTypeFactory() != null) {
            if (part == null) part = part(plan, primitiveState);
            custom = options.renderTypeFactory().apply(part, material);
        }
        if (custom == null) custom = options.overrideRenderType();
        if (custom != null) {
            boolean compatible = GltfRenderTypes.isCompatible(custom)
                && modeCompatible(custom.mode(), primitive.mode());
            if (!options.validateRenderTypeFormat() || compatible) return custom;
            if (WARNED_TYPES.add(custom)) {
                if (part == null) part = part(plan, primitiveState);
                GFBSglTF.LOGGER.warn(
                    "Ignoring incompatible RenderType {} for {}; NEW_ENTITY and a compatible draw mode are required",
                    custom, part
                );
            }
        }
        if (!GltfGeometryPipeline.isTriangleMode(primitive.mode())) {
            return GltfRenderTypes.lines(texture, material.alphaMode() == AlphaMode.BLEND);
        }
        boolean cull = cull(cullMode, material);
        return switch (material.alphaMode()) {
            case OPAQUE -> GltfRenderTypes.solid(texture, cull);
            case MASK -> GltfRenderTypes.cutout(texture, cull);
            case BLEND -> GltfRenderTypes.translucent(texture, cull);
        };
    }

    private static RenderType resolveEmissiveRenderType(
        GltfPrimitive primitive, GltfMaterial material, ResourceLocation texture,
        GltfRenderOptions.CullMode cullMode
    ) {
        return GltfRenderTypes.emissive(
            texture, cull(cullMode, material),
            !GltfGeometryPipeline.isTriangleMode(primitive.mode())
        );
    }

    private static boolean cull(GltfRenderOptions.CullMode mode, GltfMaterial material) {
        return switch (mode) {
            case FORCE_CULL -> true;
            case FORCE_NO_CULL -> false;
            case AUTO -> !material.doubleSided();
        };
    }

    private static GltfRenderOptions.CullMode resolveCullMode(
        GltfRenderOptions options, GltfNodeState node, GltfPrimitiveState primitive
    ) {
        GltfRenderOptions.CullMode value = primitive.cullModeOrNull();
        if (value == null) value = node.cullModeOrNull();
        return value == null ? options.cullMode() : value;
    }

    private static GltfRenderOptions.LightMode resolveLightMode(
        GltfRenderOptions options, GltfNodeState node, GltfPrimitiveState primitive
    ) {
        GltfRenderOptions.LightMode value = primitive.lightModeOrNull();
        if (value == null) value = node.lightModeOrNull();
        return value == null ? options.lightMode() : value;
    }

    private static boolean modeCompatible(VertexFormat.Mode renderMode,
                                          org.lytharalab.gfbs.gltf.api.model.PrimitiveMode mode) {
        if (GltfGeometryPipeline.isTriangleMode(mode)) {
            return renderMode == VertexFormat.Mode.TRIANGLES;
        }
        return renderMode == VertexFormat.Mode.LINES;
    }

    private static final class RenderScratch {
        final Matrix4f world = new Matrix4f();
        final Matrix4f model = new Matrix4f();
        final Matrix3f normal = new Matrix3f();
    }

    private static final class ImmediateBuffers {
        final BufferBuilder builder = new BufferBuilder(8192);
        final MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(builder);
        boolean busy;
    }
}
