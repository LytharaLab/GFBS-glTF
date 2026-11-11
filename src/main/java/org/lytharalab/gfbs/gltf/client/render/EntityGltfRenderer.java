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
import org.lytharalab.gfbs.gltf.api.animation.AnimationPoseAccess;
import org.lytharalab.gfbs.gltf.api.animation.ModelPose;
import org.lytharalab.gfbs.gltf.api.client.GltfInstance;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderContext;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderOptions;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderPart;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderTypes;
import org.lytharalab.gfbs.gltf.api.client.plugin.ClientGltfExtensionPoints;
import org.lytharalab.gfbs.gltf.api.client.plugin.GltfBuiltInRenderPass;
import org.lytharalab.gfbs.gltf.api.client.plugin.GltfCustomRenderPass;
import org.lytharalab.gfbs.gltf.api.client.plugin.GltfPrimitiveRenderContext;
import org.lytharalab.gfbs.gltf.api.client.plugin.GltfRenderExtension;
import org.lytharalab.gfbs.gltf.api.client.plugin.GltfRenderLayer;
import org.lytharalab.gfbs.gltf.api.client.plugin.GltfRenderStage;
import org.lytharalab.gfbs.gltf.api.client.plugin.GltfVertexSource;
import org.lytharalab.gfbs.gltf.api.client.node.GltfNodeState;
import org.lytharalab.gfbs.gltf.api.client.node.GltfPrimitiveState;
import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitiveAccess;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;
import org.lytharalab.gfbs.gltf.api.model.GltfSkinAccess;
import org.lytharalab.gfbs.gltf.api.plugin.GltfExtensionEntry;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPlugins;

import java.util.Collections;
import java.util.List;
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
    private static final Set<GltfRenderExtension> QUARANTINED_EXTENSIONS =
        Collections.newSetFromMap(new ConcurrentHashMap<>());
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
        RenderScratch scratch = SCRATCH.get();
        if (scratch.busy) scratch = new RenderScratch();
        scratch.busy = true;
        try {
            renderInternal(instance, poseStack, buffers, packedLight, packedOverlay, context, scratch);
        } finally {
            scratch.blended.clear();
            scratch.busy = false;
        }
    }

    private static void renderInternal(GltfInstance instance, PoseStack poseStack, MultiBufferSource buffers,
                                       int packedLight, int packedOverlay, GltfRenderContext context,
                                       RenderScratch scratch) {
        GltfAsset asset = instance.asset();
        GltfGpuModel gpu = GltfGpuCache.getInstance().get(asset);
        GltfRenderOptions options = instance.renderOptions();
        boolean shadowPass = OculusCompat.shadowPass();
        if (shadowPass && !options.castShadows()) return;
        boolean shaderPack = !shadowPass && OculusCompat.shadersEnabled();
        GltfRenderContext cullingContext = shadowPass
            ? options.shadowFrustumCulling() && options.frustumCulling() ? OculusCompat.shadowContext() : null
            : context;

        ModelPose pose = AnimationPoseAccess.pose(instance.animations());
        float[] world = instance.nodes().computeWorldMatricesView(instance.animations());
        GltfGpuModel.ScenePlan scene = gpu.scenePlan(instance.scene());
        Matrix4f base = poseStack.last().pose();
        GltfSceneIndex index = !shadowPass && !AnimationPoseAccess.externallyWritable(instance.animations())
            && GltfPrimitiveCuller.enabled(cullingContext, options) ? scene.index(asset) : null;
        GltfSceneIndex.Selection selection = index == null ? null : scratch.selection;
        if (index != null) index.select(instance, base, cullingContext, options, selection);

        GltfOcclusionCuller occlusion = GltfOcclusionCuller.INSTANCE;
        boolean useOcclusion = !shaderPack && !shadowPass && options.occlusionCulling()
            && context != null && context.projectionView() != null;
        if (useOcclusion) occlusion.beginFrame();

        List<GltfExtensionEntry<GltfRenderExtension>> extensions =
            GltfPlugins.extensionEntries(ClientGltfExtensionPoints.RENDER_EXTENSIONS);
        PluginRenderFrame pluginFrame = null;
        if (!extensions.isEmpty()) {
            pluginFrame = new PluginRenderFrame(
                instance, buffers, packedLight, packedOverlay, context, shadowPass, shaderPack,
                base, pass -> renderCustomPass(
                    instance, gpu, scene, buffers, options, cullingContext, shaderPack, shadowPass,
                    pose, world, base, packedLight, packedOverlay, useOcclusion, occlusion, pass,
                    scratch, selection
                ), () -> flush(buffers)
            );
        }

        try {
            if (pluginFrame != null) {
                invokeExtensions(
                    extensions, GltfRenderStage.BEFORE_BUILTIN_PASSES, pluginFrame
                );
            }
            if (shadowPass) {
                if (pluginFrame == null || !pluginFrame.isSuppressed(GltfBuiltInRenderPass.SHADOW)) {
                    renderPass(
                        instance, gpu, scene, buffers, options, cullingContext, shaderPack, true,
                        false, pose, world, base, packedLight, packedOverlay, useOcclusion, occlusion,
                        scratch, selection
                    );
                }
                if (pluginFrame != null) {
                    invokeExtensions(extensions, GltfRenderStage.AFTER_SHADOW_PASS, pluginFrame);
                }
            } else {
                if (pluginFrame == null || !pluginFrame.isSuppressed(GltfBuiltInRenderPass.BASE)) {
                    renderPass(
                        instance, gpu, scene, buffers, options, context, shaderPack, false,
                        false, pose, world, base, packedLight, packedOverlay, useOcclusion, occlusion,
                        scratch, selection
                    );
                }
                if (pluginFrame != null) {
                    invokeExtensions(extensions, GltfRenderStage.AFTER_BASE_PASS, pluginFrame);
                }
                if ((gpu.hasEmission || instance.nodes().hasMaterialOverrides())
                    && (pluginFrame == null || !pluginFrame.isSuppressed(GltfBuiltInRenderPass.EMISSIVE))) {
                    renderPass(
                        instance, gpu, scene, buffers, options, context, shaderPack, false,
                        true, pose, world, base, packedLight, packedOverlay, useOcclusion, occlusion,
                        scratch, selection
                    );
                }
                if (pluginFrame != null) {
                    invokeExtensions(extensions, GltfRenderStage.AFTER_EMISSIVE_PASS, pluginFrame);
                }
            }

            if (pluginFrame != null) {
                invokeExtensions(extensions, GltfRenderStage.AFTER_RENDER, pluginFrame);
            }
        } finally {
            if (pluginFrame != null) pluginFrame.close();
        }

        if (useOcclusion) issueOcclusionQueries(
            instance, scene, options, context, world, base, occlusion, scratch, selection
        );
    }

    private static void invokeExtensions(
        List<GltfExtensionEntry<GltfRenderExtension>> extensions,
        GltfRenderStage stage,
        PluginRenderFrame frame
    ) {
        frame.stage(stage);
        for (GltfExtensionEntry<GltfRenderExtension> entry : extensions) {
            GltfRenderExtension extension = entry.extension();
            if (QUARANTINED_EXTENSIONS.contains(extension)) continue;
            try {
                extension.render(stage, frame);
            } catch (Throwable failure) {
                QUARANTINED_EXTENSIONS.add(extension);
                GFBSglTF.LOGGER.error(
                    "Quarantined render extension from plugin {} after {} hook failed",
                    entry.pluginId(), stage, failure
                );
            }
        }
    }

    private static boolean flush(MultiBufferSource buffers) {
        if (!(buffers instanceof MultiBufferSource.BufferSource source)) return false;
        source.endBatch();
        return true;
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
        GltfOcclusionCuller occlusion,
        RenderScratch scratch,
        GltfSceneIndex.Selection selection
    ) {
        GltfGpuModel.NodePlan[] nodes = scene.nodes;
        int count = selection == null ? nodes.length : selection.size();
        for (int planIndex = 0; planIndex < count; planIndex++) {
            GltfGpuModel.NodePlan nodePlan = nodes[selection == null ? planIndex : selection.get(planIndex)];
            int nodeIndex = nodePlan.nodeIndex;
            if (selection != null && !indexedVisible(instance, nodeIndex)) continue;
            GltfNodeState nodeState = instance.nodes().node(nodeIndex);
            if (!nodeState.subtreeVisible() || instance.collision().isNodeHidden(nodeIndex)) {
                if (selection == null) planIndex = nodePlan.subtreeEndExclusive - 1;
                continue;
            }
            if (!nodeState.selfVisible() || nodePlan.primitives.length == 0) continue;
            if (shadowPass && !nodeState.castShadows()) continue;

            scratch.world.set(world, nodeIndex * 16);
            scratch.model.set(base).mul(scratch.world);
            boolean mirrored = scratch.model.determinant3x3() < 0.0f;
            boolean normalReady = false;
            float[] skin = null;
            int jointCount = 0;
            boolean skinReady = false;

            for (GltfGpuModel.PrimitivePlan primitivePlan : nodePlan.primitives) {
                GltfPrimitive primitive = primitivePlan.primitive();
                GltfPrimitiveState primitiveState = instance.nodes().primitive(primitivePlan.key());
                if (!primitiveState.visible()) continue;
                if (shadowPass && (!GltfGeometryPipeline.isTriangleMode(primitive.mode())
                    || !primitiveState.castShadows())) continue;

                float[] morphWeights = primitive.morphTargets().isEmpty() ? null
                    : instance.nodes().resolveMorphWeightsView(nodeIndex, primitivePlan.mesh(), pose);
                boolean activeMorph = GltfVertexTransforms.hasActiveMorph(primitive, morphWeights);
                boolean skinnedGeometry = nodePlan.node.skin() >= 0
                    && GltfPrimitiveAccess.joints(primitive) != null
                    && GltfPrimitiveAccess.weights(primitive) != null;
                boolean dynamicGeometry = activeMorph || skinnedGeometry;

                GltfMaterial material = primitiveState.effectiveMaterial();
                if (emissivePass && !GltfGeometryPipeline.hasVisibleEmission(material)) continue;
                if (emissivePass && shaderPack
                    && GltfMaterialBaker.emissionInSpecular(material, options.labPbrEmission())) continue;
                GltfRenderPart part = null;
                if (options.hasPartFilterOverride()) {
                    part = part(primitivePlan, primitiveState);
                    if (!options.partFilter().test(part)) continue;
                }
                if (!dynamicGeometry && !GltfPrimitiveCuller.isVisible(
                    primitive.bounds(), scratch.model, context, options, shadowPass
                )) {
                    continue;
                }

                GltfOcclusionCuller.QueryKey queryKey = null;
                if (useOcclusion && !dynamicGeometry) {
                    queryKey = new GltfOcclusionCuller.QueryKey(
                        instance.id(), nodeIndex, primitivePlan.meshIndex(),
                        primitivePlan.primitiveIndex()
                    );
                    if (!occlusion.wasVisible(queryKey, context.projectionView(), scratch.model)) continue;
                }

                GltfRenderOptions.CullMode cullMode = resolveCullMode(
                    options, nodeState, primitiveState
                );
                float red = nodeState.colorRed() * primitiveState.colorRed();
                float green = nodeState.colorGreen() * primitiveState.colorGreen();
                float blue = nodeState.colorBlue() * primitiveState.colorBlue();
                float alpha = options.alpha() * nodeState.alpha() * primitiveState.alpha();
                if (alpha <= 0.0f) continue;
                GltfRenderOptions.LightMode lightMode = resolveLightMode(
                    options, nodeState, primitiveState
                );
                boolean fullBright = lightMode == GltfRenderOptions.LightMode.FULLBRIGHT;
                int light = shadowPass || emissivePass || fullBright || material.unlit()
                    ? LightTexture.FULL_BRIGHT : packedLight;

                boolean noCustomRenderType = primitiveState.renderTypeOrNull() == null
                    && !options.hasRenderTypeOverrides();
                boolean blended = alpha < 1.0f || material.alphaMode() == AlphaMode.BLEND;
                boolean streamSmall = !emissivePass && scene.nodes.length >= 64 && primitive.indexCount() <= 96;
                boolean rigidFast = !shaderPack && !shadowPass && !mirrored && !dynamicGeometry && !streamSmall
                    && GltfGeometryPipeline.isTriangleMode(primitive.mode())
                    && !blended && noCustomRenderType
                    && (emissivePass || material.unlit() || GltfVertexTransforms.conformal(scratch.model))
                    && !(emissivePass && material.alphaMode() == AlphaMode.MASK);

                List<GltfMaterialBaker.Page> pages = emissivePass
                    ? GltfGpuModel.ORIGINAL_UV : gpu.materialPages(primitive, material, shaderPack || shadowPass, options);
                for (GltfMaterialBaker.Page page : pages) {
                    if (page != null && !dynamicGeometry
                        && !GltfPrimitiveCuller.isVisible(page.bounds, scratch.model, context, options, shadowPass)) continue;
                    RenderType renderType;
                    if (emissivePass) {
                        ResourceLocation texture = gpu.emissiveTexture(material);
                        renderType = resolveEmissiveRenderType(primitive, material, texture, cullMode);
                    } else if (shadowPass) {
                        ResourceLocation texture = gpu.materialTexture(material, true, page);
                        renderType = resolveShadowRenderType(material, texture, cullMode);
                    } else {
                        ResourceLocation texture = gpu.materialTexture(material, shaderPack, page);
                        if (part == null && (options.renderTypeFactory() != null
                            || primitiveState.renderTypeOrNull() != null)) {
                            part = part(primitivePlan, primitiveState);
                        }
                        renderType = resolveRenderType(
                            part, primitivePlan, material, texture, options, primitiveState, cullMode, blended
                        );
                    }

                    GltfPrimitiveClusters.Section[] sections = page == null && !dynamicGeometry && !blended
                        && noCustomRenderType && GltfPrimitiveCuller.enabled(context, options, shadowPass)
                        ? gpu.sections(primitive) : GltfPrimitiveClusters.whole();
                    for (GltfPrimitiveClusters.Section section : sections) {
                        if (section != null
                            && !GltfPrimitiveCuller.isVisible(section.bounds(), scratch.model, context, options, shadowPass)) continue;
                        if (rigidFast) {
                            GltfGeometryPipeline.PassKind passKind = emissivePass
                                ? GltfGeometryPipeline.PassKind.EMISSIVE
                                : GltfGeometryPipeline.PassKind.BASE;
                            GltfGpuPrimitive resident = gpu.geometry(
                                primitivePlan, material, passKind, section
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
                                gltfSkin, nodeIndex, world
                            );
                            skinReady = true;
                        }
                        GltfGeometryPipeline.MaterialPass materialPass = emissivePass
                            ? GltfGeometryPipeline.MaterialPass.emissive(material, red, green, blue)
                            : GltfGeometryPipeline.MaterialPass.base(gpu.materialUv(material, shaderPack || shadowPass), red, green, blue).withPage(page);
                        if (blended && !shadowPass && !emissivePass && noCustomRenderType) {
                            scratch.blended.add(new BlendedDraw(renderType, primitive, material, materialPass,
                                new Matrix4f(scratch.model), new Matrix3f(scratch.normal), skin, jointCount,
                                morphWeights, light, packedOverlay, alpha, distanceSquared(primitive, scratch.model)));
                            continue;
                        }
                        GltfGeometryPipeline.emit(
                            buffers.getBuffer(renderType), primitive, material, materialPass,
                            scratch.model, scratch.normal, skin, jointCount, morphWeights,
                            light, packedOverlay, alpha, section
                        );
                    }
                }
            }
        }
        if (!scratch.blended.isEmpty()) {
            scratch.blended.sort(java.util.Comparator.comparingDouble(BlendedDraw::distance).reversed());
            for (BlendedDraw draw : scratch.blended) draw.emit(buffers);
            scratch.blended.clear();
        }
    }

    private static void renderCustomPass(
        GltfInstance instance,
        GltfGpuModel gpu,
        GltfGpuModel.ScenePlan scene,
        MultiBufferSource buffers,
        GltfRenderOptions options,
        GltfRenderContext context,
        boolean shaderPack,
        boolean shadowPass,
        ModelPose pose,
        float[] world,
        Matrix4f base,
        int packedLight,
        int packedOverlay,
        boolean useOcclusion,
        GltfOcclusionCuller occlusion,
        GltfCustomRenderPass customPass,
        RenderScratch scratch,
        GltfSceneIndex.Selection selection
    ) {
        GltfGpuModel.NodePlan[] nodes = scene.nodes;
        int count = selection == null ? nodes.length : selection.size();
        for (int planIndex = 0; planIndex < count; planIndex++) {
            GltfGpuModel.NodePlan nodePlan = nodes[selection == null ? planIndex : selection.get(planIndex)];
            int nodeIndex = nodePlan.nodeIndex;
            if (selection != null && !indexedVisible(instance, nodeIndex)) continue;
            GltfNodeState nodeState = instance.nodes().node(nodeIndex);
            if (!nodeState.subtreeVisible() || instance.collision().isNodeHidden(nodeIndex)) {
                if (selection == null) planIndex = nodePlan.subtreeEndExclusive - 1;
                continue;
            }
            if (!nodeState.selfVisible() || nodePlan.primitives.length == 0) continue;
            if (shadowPass && !nodeState.castShadows()) continue;

            scratch.world.set(world, nodeIndex * 16);
            scratch.model.set(base).mul(scratch.world);
            boolean normalReady = false;
            float[] skin = null;
            int jointCount = 0;
            boolean skinReady = false;

            for (GltfGpuModel.PrimitivePlan primitivePlan : nodePlan.primitives) {
                GltfPrimitive primitive = primitivePlan.primitive();
                GltfPrimitiveState primitiveState = instance.nodes().primitive(primitivePlan.key());
                if (!primitiveState.visible()) continue;
                if (shadowPass && (!GltfGeometryPipeline.isTriangleMode(primitive.mode())
                    || !primitiveState.castShadows())) continue;

                float[] morphWeights = primitive.morphTargets().isEmpty() ? null
                    : instance.nodes().resolveMorphWeightsView(nodeIndex, primitivePlan.mesh(), pose);
                boolean activeMorph = GltfVertexTransforms.hasActiveMorph(primitive, morphWeights);
                boolean skinnedGeometry = nodePlan.node.skin() >= 0
                    && GltfPrimitiveAccess.joints(primitive) != null
                    && GltfPrimitiveAccess.weights(primitive) != null;
                boolean dynamicGeometry = activeMorph || skinnedGeometry;
                GltfMaterial material = primitiveState.effectiveMaterial();
                GltfRenderPart part = part(primitivePlan, primitiveState);

                if (options.hasPartFilterOverride() && !options.partFilter().test(part)) continue;
                if (!dynamicGeometry && !GltfPrimitiveCuller.isVisible(
                    primitive.bounds(), scratch.model, context, options, shadowPass
                )) continue;
                if (useOcclusion && !dynamicGeometry) {
                    GltfOcclusionCuller.QueryKey queryKey = new GltfOcclusionCuller.QueryKey(
                        instance.id(), nodeIndex, primitivePlan.meshIndex(),
                        primitivePlan.primitiveIndex()
                    );
                    if (!occlusion.wasVisible(queryKey, context.projectionView(), scratch.model)) continue;
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
                int inheritedLight = shadowPass
                    || lightMode == GltfRenderOptions.LightMode.FULLBRIGHT || material.unlit()
                    ? LightTexture.FULL_BRIGHT : packedLight;

                for (GltfMaterialBaker.Page page : gpu.materialPages(primitive, material, shaderPack || shadowPass, options)) {
                    if (page != null && !dynamicGeometry
                        && !GltfPrimitiveCuller.isVisible(page.bounds, scratch.model, context, options, shadowPass)) continue;
                    ResourceLocation baseTexture = gpu.materialTexture(material, shaderPack || shadowPass, page);
                    ResourceLocation emissionTexture = gpu.emissiveTexture(material);
                    GltfPrimitiveRenderContext primitiveContext = new GltfPrimitiveRenderContext(
                        instance, context, part, primitive, material, baseTexture, emissionTexture,
                        scratch.model, shaderPack, shadowPass,
                        GltfGeometryPipeline.isTriangleMode(primitive.mode()), dynamicGeometry,
                        cull(cullMode, material), inheritedLight, packedOverlay,
                        red, green, blue, alpha
                    );

                    GltfRenderLayer layer;
                    try {
                        layer = customPass.layer(primitiveContext);
                    } catch (Exception failure) {
                        throw new IllegalStateException(
                            "Custom glTF render pass " + customPass.id() + " failed for " + part,
                            failure
                        );
                    }
                    if (layer == null) continue;

                    if (page != null && page.index > 0 && layer.vertexSource() == GltfVertexSource.EMISSIVE) continue;
                    RenderType renderType = layer.renderType();
                    boolean compatible = GltfRenderTypes.isCompatible(renderType)
                        && modeCompatible(renderType.mode(), primitive.mode());
                    if (options.validateRenderTypeFormat() && !compatible) {
                        if (WARNED_TYPES.add(renderType)) {
                            GFBSglTF.LOGGER.warn(
                                "Ignoring incompatible RenderType {} from custom pass {} for {}; "
                                    + "NEW_ENTITY and a compatible draw mode are required",
                                renderType, customPass.id(), part
                            );
                        }
                        continue;
                    }

                    int light = switch (layer.lightMode()) {
                        case INHERIT -> inheritedLight;
                        case FULL_BRIGHT -> LightTexture.FULL_BRIGHT;
                        case CUSTOM -> layer.customPackedLight();
                    };
                    float layerRed = red * layer.red();
                    float layerGreen = green * layer.green();
                    float layerBlue = blue * layer.blue();
                    float layerAlpha = alpha * layer.alpha();
                    GltfGeometryPipeline.PassKind passKind =
                        layer.vertexSource() == GltfVertexSource.EMISSIVE
                            ? GltfGeometryPipeline.PassKind.EMISSIVE
                            : GltfGeometryPipeline.PassKind.BASE;
                    boolean rigidFast = page == null && !shaderPack && !shadowPass && scratch.model.determinant3x3() >= 0.0f
                        && layer.residentGeometry() && !dynamicGeometry
                        && GltfGeometryPipeline.isTriangleMode(primitive.mode())
                        && (passKind == GltfGeometryPipeline.PassKind.EMISSIVE || material.unlit()
                            || GltfVertexTransforms.conformal(scratch.model))
                        && material.alphaMode() != AlphaMode.BLEND;

                    if (rigidFast) {
                        GltfGpuPrimitive resident = gpu.geometry(primitivePlan, material, passKind);
                        if (resident != null) {
                            boolean emissiveVertices = passKind == GltfGeometryPipeline.PassKind.EMISSIVE;
                            float strength = emissiveVertices
                                ? material.emissiveStrength() * layerAlpha : 1.0f;
                            boolean transformLights = layer.lightMode() == GltfRenderLayer.LightMode.INHERIT
                                && inheritedLight != LightTexture.FULL_BRIGHT
                                && !emissiveVertices;
                            resident.draw(
                                renderType, scratch.model, transformLights, light, packedOverlay,
                                layerRed * strength, layerGreen * strength, layerBlue * strength,
                                emissiveVertices ? 1.0f : layerAlpha
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
                            gltfSkin, nodeIndex, world
                        );
                        skinReady = true;
                    }
                    GltfGeometryPipeline.MaterialPass materialPass =
                        passKind == GltfGeometryPipeline.PassKind.EMISSIVE
                            ? GltfGeometryPipeline.MaterialPass.emissive(
                                material, layerRed, layerGreen, layerBlue
                            )
                            : GltfGeometryPipeline.MaterialPass.base(
                                gpu.materialUv(material, shaderPack || shadowPass), layerRed, layerGreen, layerBlue
                            );
                    if (passKind == GltfGeometryPipeline.PassKind.BASE) materialPass = materialPass.withPage(page);
                    GltfGeometryPipeline.emit(
                        buffers.getBuffer(renderType), primitive, material, materialPass,
                        scratch.model, scratch.normal, skin, jointCount, morphWeights,
                        light, packedOverlay, layerAlpha
                    );
                }
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
        GltfOcclusionCuller occlusion,
        RenderScratch scratch,
        GltfSceneIndex.Selection selection
    ) {
        if (!occlusion.canIssue()) return;
        GltfOcclusionCuller.State state = GltfOcclusionCuller.beginQueries();
        try {
            Matrix4f projection = context.projectionView();
            GltfGpuModel.NodePlan[] nodes = scene.nodes;
            int count = selection == null ? nodes.length : selection.size();
            for (int planIndex = 0; planIndex < count && occlusion.canIssue(); planIndex++) {
                GltfGpuModel.NodePlan nodePlan = nodes[selection == null ? planIndex : selection.get(planIndex)];
                int nodeIndex = nodePlan.nodeIndex;
                if (selection != null && !indexedVisible(instance, nodeIndex)) continue;
                GltfNodeState nodeState = instance.nodes().node(nodeIndex);
                if (!nodeState.subtreeVisible() || instance.collision().isNodeHidden(nodeIndex)) {
                    if (selection == null) planIndex = nodePlan.subtreeEndExclusive - 1;
                    continue;
                }
                if (!nodeState.selfVisible() || nodePlan.primitives.length == 0) continue;
                scratch.world.set(world, nodeIndex * 16);
                scratch.model.set(base).mul(scratch.world);
                for (GltfGpuModel.PrimitivePlan plan : nodePlan.primitives) {
                    if (!occlusion.canIssue()) break;
                    GltfPrimitive primitive = plan.primitive();
                    GltfPrimitiveState primitiveState = instance.nodes().primitive(plan.key());
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
                            instance.id(), nodeIndex, plan.meshIndex(), plan.primitiveIndex()
                        ),
                        projection, scratch.model, primitive.bounds()
                    );
                }
            }
        } finally {
            state.restore();
        }
    }

    private static boolean indexedVisible(GltfInstance instance, int nodeIndex) {
        return instance.nodes().isSubtreeVisible(nodeIndex)
            && !instance.collision().isInHiddenSubtree(nodeIndex);
    }

    private static GltfRenderPart part(
        GltfGpuModel.PrimitivePlan plan, GltfPrimitiveState state
    ) {
        return new GltfRenderPart(
            plan.nodeIndex(), plan.nodeName(), plan.meshIndex(), plan.primitiveIndex(),
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
        GltfRenderOptions.CullMode cullMode,
        boolean blended
    ) {
        GltfPrimitive primitive = plan.primitive();
        RenderType custom = primitiveState.renderTypeOrNull();
        if (custom == null) custom = options.nodeRenderType(plan.nodeName());
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
            return GltfRenderTypes.lines(texture, blended);
        }
        boolean cull = cull(cullMode, material);
        if (material.unlit()) return GltfRenderTypes.unlit(texture, cull, blended);
        if (blended) return GltfRenderTypes.translucent(texture, cull);
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
        return GltfRenderTypes.emissive(texture, cull(cullMode, material), primitive.mode(),
            material.alphaMode() == AlphaMode.MASK);
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
        return renderMode == VertexFormat.Mode.LINES || renderMode == VertexFormat.Mode.DEBUG_LINES;
    }

    private static double distanceSquared(GltfPrimitive primitive, Matrix4f model) {
        var bounds = primitive.bounds();
        float x = bounds.valid() ? bounds.centerX() : 0;
        float y = bounds.valid() ? bounds.centerY() : 0;
        float z = bounds.valid() ? bounds.centerZ() : 0;
        double dx = model.m00() * x + model.m10() * y + model.m20() * z + model.m30();
        double dy = model.m01() * x + model.m11() * y + model.m21() * z + model.m31();
        double dz = model.m02() * x + model.m12() * y + model.m22() * z + model.m32();
        return dx * dx + dy * dy + dz * dz;
    }

    private record BlendedDraw(RenderType type, GltfPrimitive primitive, GltfMaterial material,
                               GltfGeometryPipeline.MaterialPass pass, Matrix4f model, Matrix3f normal,
                               float[] skin, int joints, float[] weights, int light, int overlay,
                               float alpha, double distance) {
        void emit(MultiBufferSource buffers) {
            GltfGeometryPipeline.emit(buffers.getBuffer(type), primitive, material, pass, model, normal,
                skin, joints, weights, light, overlay, alpha);
        }
    }

    private static final class RenderScratch {
        final Matrix4f world = new Matrix4f();
        final Matrix4f model = new Matrix4f();
        final Matrix3f normal = new Matrix3f();
        final GltfSceneIndex.Selection selection = new GltfSceneIndex.Selection();
        final java.util.ArrayList<BlendedDraw> blended = new java.util.ArrayList<>();
        boolean busy;
    }

    private static final class ImmediateBuffers {
        final BufferBuilder builder = new BufferBuilder(8192);
        final MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(builder);
        boolean busy;
    }
}
