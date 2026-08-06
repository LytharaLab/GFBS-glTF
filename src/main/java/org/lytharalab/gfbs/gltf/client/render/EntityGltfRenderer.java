package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lytharalab.gfbs.gltf.GFBSglTF;
import org.lytharalab.gfbs.gltf.api.animation.ModelPose;
import org.lytharalab.gfbs.gltf.api.client.GltfInstance;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderContext;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderOptions;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderPart;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderTypes;
import org.lytharalab.gfbs.gltf.api.client.node.GltfNodeManager;
import org.lytharalab.gfbs.gltf.api.client.node.GltfNodeState;
import org.lytharalab.gfbs.gltf.api.client.node.GltfPrimitiveKey;
import org.lytharalab.gfbs.gltf.api.client.node.GltfPrimitiveState;
import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfMesh;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfScene;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;
import org.lytharalab.gfbs.gltf.api.model.GltfTextureInfo;
import org.lytharalab.gfbs.gltf.api.model.PrimitiveMode;
import org.lytharalab.gfbs.gltf.core.animation.PoseTransforms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Universal renderer. Both vanilla and shader-pack paths use Minecraft's original entity shaders;
 * shader packs additionally receive lazily-created LabPBR companion textures.
 *
 * <p>Oculus/Iris shadow rendering deliberately uses a separate caster path. Camera-space
 * frustum/occlusion results and user RenderType overrides belong to the color pass and must not
 * suppress shadow geometry.</p>
 */
public final class EntityGltfRenderer {
    private static final int MAX_EMISSIVE_PASSES = 16;
    private static final Set<RenderType> WARNED_TYPES = ConcurrentHashMap.newKeySet();

    private EntityGltfRenderer() {
    }

    public static void render(GltfInstance instance, PoseStack poseStack, int packedLight,
                              int packedOverlay, GltfRenderContext context) {
        BufferBuilder builder = new BufferBuilder(8192);
        MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(builder);
        render(instance, poseStack, buffers, packedLight, packedOverlay, context);
        buffers.endBatch();
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
        float[] world = instance.nodes().computeWorldMatrices(pose);
        Matrix4f base = new Matrix4f(poseStack.last().pose());
        List<DrawItem> candidates = collect(
            instance, gpu, options, context, shaderPack, shadowPass, pose, world, base
        );
        GltfOcclusionCuller occlusion = GltfOcclusionCuller.INSTANCE;
        boolean useOcclusion = !shadowPass && options.occlusionCulling()
            && context != null && context.projection() != null;
        if (useOcclusion) {
            occlusion.beginFrame();
        }

        List<DrawItem> visible = new ArrayList<>(candidates.size());
        for (DrawItem item : candidates) {
            if (!useOcclusion || item.primitive.hasDynamicGeometry()
                || occlusion.wasVisible(item.queryKey)) {
                visible.add(item);
            }
        }
        visible.sort(Comparator
            .comparingInt((DrawItem item) -> GltfRenderTypes.sortOrder(item.renderType))
            .thenComparingDouble(item -> item.pass.kind == PassKind.BASE
                && item.material.alphaMode() == AlphaMode.BLEND
                ? item.depth : 0.0));

        for (DrawItem item : visible) {
            int light = shadowPass
                || item.fullBright
                || item.material.unlit()
                || item.pass.kind == PassKind.EMISSIVE ? LightTexture.FULL_BRIGHT : packedLight;
            emitPrimitive(
                buffers.getBuffer(item.renderType),
                item.primitive,
                item.material,
                item.pass,
                item.model,
                item.normalMatrix,
                item.skin,
                item.jointCount,
                item.morphWeights,
                light,
                packedOverlay,
                item.alpha
            );
        }

        if (useOcclusion) {
            GltfOcclusionCuller.State state = GltfOcclusionCuller.beginQueries();
            try {
                Matrix4f projection = context.projection();
                for (DrawItem item : candidates) {
                    if (item.pass.kind == PassKind.BASE
                        && !item.primitive.hasDynamicGeometry()) {
                        occlusion.issue(item.queryKey, projection, item.model, item.primitive.bounds());
                    }
                }
                occlusion.evictStale();
            } finally {
                state.restore();
            }
        }
    }

    private static List<DrawItem> collect(GltfInstance instance, GltfGpuModel gpu,
                                          GltfRenderOptions options, GltfRenderContext context,
                                          boolean shaderPack, boolean shadowPass, ModelPose pose,
                                          float[] world, Matrix4f base) {
        GltfAsset asset = instance.asset();
        List<DrawItem> items = new ArrayList<>();
        GltfScene scene = asset.scenes().get(instance.scene());
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        int[] roots = scene.roots();
        for (int i = roots.length - 1; i >= 0; i--) pending.push(roots[i]);
        while (!pending.isEmpty()) {
            int nodeIndex = pending.pop();
            GltfNodeState nodeState = instance.nodes().node(nodeIndex);
            if (!nodeState.subtreeVisible() || instance.collision().isNodeHidden(nodeIndex)) continue;
            GltfNode node = asset.nodes().get(nodeIndex);
            Matrix4f model = new Matrix4f(base).mul(new Matrix4f().set(world, nodeIndex * 16));
            Matrix3f normalMatrix = normalMatrix(model);
            float[] skin = null;
            int jointCount = 0;
            if (node.skin() >= 0) {
                GltfSkin gltfSkin = asset.skins().get(node.skin());
                jointCount = gltfSkin.joints().length;
                skin = PoseTransforms.computeSkinPalette(gltfSkin, nodeIndex, world);
            }
            if (nodeState.selfVisible()) {
                for (int meshIndex : node.meshes()) {
                    GltfMesh mesh = asset.meshes().get(meshIndex);
                    float[] morphWeights = instance.nodes().resolveMorphWeights(nodeIndex, mesh, pose);
                    for (int primitiveIndex = 0; primitiveIndex < mesh.primitives().size(); primitiveIndex++) {
                        GltfPrimitive primitive = mesh.primitives().get(primitiveIndex);
                        GltfPrimitiveState primitiveState = instance.nodes().primitive(
                            new GltfPrimitiveKey(nodeIndex, meshIndex, primitiveIndex)
                        );
                        if (!primitiveState.visible()) continue;
                        if (shadowPass && (!isTriangleMode(primitive.mode())
                            || !nodeState.castShadows() || !primitiveState.castShadows())) continue;
                        GltfMaterial material = primitiveState.effectiveMaterial();
                        GltfRenderPart part = new GltfRenderPart(
                            nodeIndex, node.name(), meshIndex, primitiveIndex,
                            primitiveState.effectiveMaterialIndex()
                        );
                        if (!options.partFilter().test(part)) continue;
                        if (!shadowPass
                            && !GltfPrimitiveCuller.isVisible(primitive, model, context, options)) continue;
                        GltfRenderOptions.CullMode cullMode = resolveCullMode(
                            options, nodeState, primitiveState
                        );
                        ResourceLocationHolder texture = new ResourceLocationHolder(
                            gpu.materialTexture(material, shaderPack)
                        );
                        RenderType renderType = shadowPass
                            ? resolveShadowRenderType(material, texture, cullMode)
                            : resolveRenderType(
                                part, primitive, material, texture, options, primitiveState, cullMode
                            );
                        double depth = transformDepth(
                            model,
                            primitive.bounds().centerX(),
                            primitive.bounds().centerY(),
                            primitive.bounds().centerZ()
                        );
                        float[] tint = multiplyColor(nodeState, primitiveState);
                        float alpha = options.alpha() * nodeState.alpha() * primitiveState.alpha();
                        GltfRenderOptions.LightMode lightMode = primitiveState.lightMode().orElse(
                            nodeState.lightMode().orElse(options.lightMode())
                        );
                        boolean fullBright = lightMode == GltfRenderOptions.LightMode.FULLBRIGHT;
                        GltfOcclusionCuller.QueryKey queryKey = new GltfOcclusionCuller.QueryKey(
                            instance.id(), nodeIndex, meshIndex, primitiveIndex
                        );
                        items.add(new DrawItem(
                            primitive, material, MaterialPass.base(material, tint), renderType,
                            new Matrix4f(model), new Matrix3f(normalMatrix),
                            skin, jointCount, morphWeights, depth, queryKey, fullBright, alpha
                        ));
                        if (!shadowPass && hasVisibleEmission(material)) {
                            ResourceLocationHolder emissiveTexture = new ResourceLocationHolder(
                                gpu.emissiveTexture(material)
                            );
                            RenderType emissiveRenderType = resolveEmissiveRenderType(
                                primitive, material, emissiveTexture, cullMode
                            );
                            int emissivePasses = emissivePassCount(material);
                            float passStrength = material.emissiveStrength() / emissivePasses;
                            for (int pass = 0; pass < emissivePasses; pass++) {
                                items.add(new DrawItem(
                                    primitive,
                                    material,
                                    MaterialPass.emissive(material, passStrength, tint),
                                    emissiveRenderType,
                                    new Matrix4f(model),
                                    new Matrix3f(normalMatrix),
                                    skin,
                                    jointCount,
                                    morphWeights,
                                    depth,
                                    queryKey,
                                    true,
                                    alpha
                                ));
                            }
                        }
                    }
                }
            }
            int[] children = node.children();
            for (int i = children.length - 1; i >= 0; i--) pending.push(children[i]);
        }
        return items;
    }

    private static RenderType resolveShadowRenderType(GltfMaterial material,
                                                      ResourceLocationHolder texture,
                                                      GltfRenderOptions.CullMode cullMode) {
        boolean cull = switch (cullMode) {
            case FORCE_CULL -> true;
            case FORCE_NO_CULL -> false;
            case AUTO -> !material.doubleSided();
        };
        return material.alphaMode() == AlphaMode.OPAQUE
            ? GltfRenderTypes.solid(texture.value, cull)
            : GltfRenderTypes.cutout(texture.value, cull);
    }

    private static RenderType resolveRenderType(GltfRenderPart part, GltfPrimitive primitive,
                                                GltfMaterial material, ResourceLocationHolder texture,
                                                GltfRenderOptions options,
                                                GltfPrimitiveState primitiveState,
                                                GltfRenderOptions.CullMode cullMode) {
        RenderType custom = primitiveState.renderType().orElse(null);
        if (custom == null) custom = options.nodeRenderTypes().get(part.nodeName());
        if (custom == null && options.renderTypeFactory() != null) {
            custom = options.renderTypeFactory().apply(part, material);
        }
        if (custom == null) custom = options.overrideRenderType();
        if (custom != null) {
            boolean compatible = GltfRenderTypes.isCompatible(custom)
                && modeCompatible(custom.mode(), primitive.mode());
            if (!options.validateRenderTypeFormat() || compatible) return custom;
            if (WARNED_TYPES.add(custom)) {
                GFBSglTF.LOGGER.warn(
                    "Ignoring incompatible RenderType {} for {}; NEW_ENTITY and a compatible draw mode are required",
                    custom, part
                );
            }
        }
        if (!isTriangleMode(primitive.mode())) {
            return GltfRenderTypes.lines(texture.value, material.alphaMode() == AlphaMode.BLEND);
        }
        boolean cull = switch (cullMode) {
            case FORCE_CULL -> true;
            case FORCE_NO_CULL -> false;
            case AUTO -> !material.doubleSided();
        };
        return switch (material.alphaMode()) {
            case OPAQUE -> GltfRenderTypes.solid(texture.value, cull);
            case MASK -> GltfRenderTypes.cutout(texture.value, cull);
            case BLEND -> GltfRenderTypes.translucent(texture.value, cull);
        };
    }

    private static RenderType resolveEmissiveRenderType(
        GltfPrimitive primitive,
        GltfMaterial material,
        ResourceLocationHolder texture,
        GltfRenderOptions.CullMode cullMode
    ) {
        boolean cull = switch (cullMode) {
            case FORCE_CULL -> true;
            case FORCE_NO_CULL -> false;
            case AUTO -> !material.doubleSided();
        };
        return GltfRenderTypes.emissive(
            texture.value,
            cull,
            !isTriangleMode(primitive.mode())
        );
    }

    private static GltfRenderOptions.CullMode resolveCullMode(
        GltfRenderOptions options,
        GltfNodeState node,
        GltfPrimitiveState primitive
    ) {
        return primitive.cullMode().orElse(node.cullMode().orElse(options.cullMode()));
    }

    private static float[] multiplyColor(GltfNodeState node, GltfPrimitiveState primitive) {
        float[] a = node.colorMultiplier();
        float[] b = primitive.colorMultiplier();
        return new float[]{a[0] * b[0], a[1] * b[1], a[2] * b[2]};
    }

    private static boolean modeCompatible(VertexFormat.Mode renderMode, PrimitiveMode primitiveMode) {
        if (isTriangleMode(primitiveMode)) {
            return renderMode == VertexFormat.Mode.TRIANGLES;
        }
        return renderMode == VertexFormat.Mode.LINES;
    }

    private static boolean isTriangleMode(PrimitiveMode mode) {
        return mode == PrimitiveMode.TRIANGLES
            || mode == PrimitiveMode.TRIANGLE_STRIP
            || mode == PrimitiveMode.TRIANGLE_FAN;
    }

    private static Matrix3f normalMatrix(Matrix4f model) {
        Matrix3f result = new Matrix3f(model);
        if (Math.abs(result.determinant()) > 1.0e-10f) result.invert().transpose();
        else result.identity();
        return result;
    }

    private static void emitPrimitive(VertexConsumer consumer, GltfPrimitive primitive,
                                      GltfMaterial material, MaterialPass pass,
                                      Matrix4f model, Matrix3f normalMatrix,
                                      float[] skin, int jointCount, float[] morphWeights,
                                      int packedLight, int packedOverlay, float alpha) {
        GltfVertexTransforms.PreparedGeometry geometry =
            GltfVertexTransforms.prepare(primitive, morphWeights);
        float[] positions = geometry.positions();
        float[] normals = geometry.normals();
        float[] uv0 = primitive.texCoords0();
        float[] uv1 = primitive.texCoords1();
        float[] colors = primitive.colors();
        int[] joints = primitive.joints();
        float[] weights = primitive.weights();
        float[] skinScratch = new float[6];
        int[] indices = primitive.indices();
        if (indices == null) {
            indices = new int[primitive.vertexCount()];
            for (int i = 0; i < indices.length; i++) indices[i] = i;
        }
        if (!isTriangleMode(primitive.mode())) {
            emitLines(consumer, primitive.mode(), indices, material, pass, model, normalMatrix,
                positions, normals, uv0, uv1, colors, joints, weights, skin, jointCount,
                skinScratch, packedLight, packedOverlay, alpha);
            return;
        }
        switch (primitive.mode()) {
            case TRIANGLES -> {
                for (int i = 0; i < indices.length; i += 3) {
                    emitTriangle(consumer, indices[i], indices[i + 1], indices[i + 2],
                        material, pass, model, normalMatrix, positions, normals, uv0, uv1, colors,
                        joints, weights, skin, jointCount, skinScratch,
                        packedLight, packedOverlay, alpha);
                }
            }
            case TRIANGLE_STRIP -> {
                for (int i = 2; i < indices.length; i++) {
                    int a = indices[i - 2];
                    int b = indices[i - 1];
                    int c = indices[i];
                    if ((i & 1) != 0) {
                        int swap = a;
                        a = b;
                        b = swap;
                    }
                    emitTriangle(consumer, a, b, c, material, pass, model, normalMatrix,
                        positions, normals, uv0, uv1, colors, joints, weights, skin, jointCount,
                        skinScratch, packedLight, packedOverlay, alpha);
                }
            }
            case TRIANGLE_FAN -> {
                for (int i = 2; i < indices.length; i++) {
                    emitTriangle(consumer, indices[0], indices[i - 1], indices[i],
                        material, pass, model, normalMatrix, positions, normals, uv0, uv1, colors,
                        joints, weights, skin, jointCount, skinScratch,
                        packedLight, packedOverlay, alpha);
                }
            }
            default -> throw new IllegalStateException("Unexpected primitive mode");
        }
    }

    private static void emitLines(VertexConsumer consumer, PrimitiveMode mode, int[] indices,
                                  GltfMaterial material, MaterialPass pass,
                                  Matrix4f model, Matrix3f normalMatrix,
                                  float[] positions, float[] normals, float[] uv0, float[] uv1,
                                  float[] colors, int[] joints, float[] weights, float[] skin,
                                  int jointCount, float[] skinScratch,
                                  int light, int overlay, float alpha) {
        switch (mode) {
            case POINTS -> {
                for (int index : indices) emitLine(consumer, index, index, material, pass, model,
                    normalMatrix, positions, normals, uv0, uv1, colors, joints, weights,
                    skin, jointCount, skinScratch, light, overlay, alpha);
            }
            case LINES -> {
                for (int i = 0; i + 1 < indices.length; i += 2) emitLine(
                    consumer, indices[i], indices[i + 1], material, pass, model, normalMatrix,
                    positions, normals, uv0, uv1, colors, joints, weights, skin, jointCount,
                    skinScratch, light, overlay, alpha
                );
            }
            case LINE_STRIP, LINE_LOOP -> {
                for (int i = 1; i < indices.length; i++) emitLine(
                    consumer, indices[i - 1], indices[i], material, pass, model, normalMatrix,
                    positions, normals, uv0, uv1, colors, joints, weights, skin, jointCount,
                    skinScratch, light, overlay, alpha
                );
                if (mode == PrimitiveMode.LINE_LOOP) emitLine(
                    consumer, indices[indices.length - 1], indices[0], material, pass,
                    model, normalMatrix,
                    positions, normals, uv0, uv1, colors, joints, weights, skin, jointCount,
                    skinScratch, light, overlay, alpha
                );
            }
            default -> throw new IllegalStateException("Unexpected line mode");
        }
    }

    private static void emitLine(VertexConsumer consumer, int first, int second,
                                 GltfMaterial material, MaterialPass pass,
                                 Matrix4f model, Matrix3f normalMatrix,
                                 float[] positions, float[] normals, float[] uv0, float[] uv1,
                                 float[] colors, int[] joints, float[] weights, float[] skin,
                                 int jointCount, float[] skinScratch,
                                 int light, int overlay, float alpha) {
        float[] lineNormal = {0.0f, 1.0f, 0.0f};
        emitVertex(consumer, first, material, pass, model, normalMatrix, positions, normals,
            uv0, uv1, colors, joints, weights, skin, jointCount, light, overlay,
            lineNormal, skinScratch, alpha);
        emitVertex(consumer, second, material, pass, model, normalMatrix, positions, normals,
            uv0, uv1, colors, joints, weights, skin, jointCount, light, overlay,
            lineNormal, skinScratch, alpha);
    }

    private static void emitTriangle(VertexConsumer consumer, int a, int b, int c,
                                     GltfMaterial material, MaterialPass pass,
                                     Matrix4f model, Matrix3f normalMatrix,
                                     float[] positions, float[] normals, float[] uv0, float[] uv1,
                                     float[] colors, int[] joints, float[] weights, float[] skin,
                                     int jointCount, float[] skinScratch,
                                     int packedLight, int packedOverlay, float alpha) {
        float[] generatedNormal = normals == null
            ? GltfVertexTransforms.faceNormal(positions, a, b, c) : null;
        emitVertex(consumer, a, material, pass, model, normalMatrix,
            positions, normals, uv0, uv1,
            colors, joints, weights, skin, jointCount, packedLight, packedOverlay,
            generatedNormal, skinScratch, alpha);
        emitVertex(consumer, b, material, pass, model, normalMatrix,
            positions, normals, uv0, uv1,
            colors, joints, weights, skin, jointCount, packedLight, packedOverlay,
            generatedNormal, skinScratch, alpha);
        emitVertex(consumer, c, material, pass, model, normalMatrix,
            positions, normals, uv0, uv1,
            colors, joints, weights, skin, jointCount, packedLight, packedOverlay,
            generatedNormal, skinScratch, alpha);
    }

    private static void emitVertex(VertexConsumer consumer, int vertex, GltfMaterial material,
                                   MaterialPass pass,
                                   Matrix4f model, Matrix3f normalMatrix,
                                   float[] positions, float[] normals, float[] uv0, float[] uv1,
                                   float[] colors, int[] joints, float[] weights, float[] skin,
                                   int jointCount, int packedLight, int packedOverlay,
                                   float[] generatedNormal, float[] skinScratch, float alpha) {
        int position = vertex * 3;
        float x = positions[position];
        float y = positions[position + 1];
        float z = positions[position + 2];
        float nx = normals == null ? generatedNormal[0] : normals[position];
        float ny = normals == null ? generatedNormal[1] : normals[position + 1];
        float nz = normals == null ? generatedNormal[2] : normals[position + 2];
        if (skin != null && joints != null && weights != null) {
            GltfVertexTransforms.skinVertex(
                vertex, x, y, z, nx, ny, nz, joints, weights, skin, jointCount, skinScratch
            );
            x = skinScratch[0];
            y = skinScratch[1];
            z = skinScratch[2];
            nx = skinScratch[3];
            ny = skinScratch[4];
            nz = skinScratch[5];
        }
        int colorComponents = colors == null ? 0 : colors.length / (positions.length / 3);
        float red;
        float green;
        float blue;
        float outputAlpha;
        if (pass.kind == PassKind.BASE) {
            float[] base = material.baseColor();
            red = (colors == null ? 1.0f : colors[vertex * colorComponents])
                * base[0] * pass.redMultiplier;
            green = (colors == null ? 1.0f : colors[vertex * colorComponents + 1])
                * base[1] * pass.greenMultiplier;
            blue = (colors == null ? 1.0f : colors[vertex * colorComponents + 2])
                * base[2] * pass.blueMultiplier;
            float vertexAlpha = colors == null || colorComponents < 4
                ? 1.0f : colors[vertex * colorComponents + 3];
            float factorAlpha = material.alphaMode() == AlphaMode.MASK ? 1.0f : base[3];
            outputAlpha = vertexAlpha * factorAlpha * alpha;
        } else {
            float[] emissive = material.emissive();
            float strength = pass.colorScale * alpha;
            red = emissive[0] * strength * pass.redMultiplier;
            green = emissive[1] * strength * pass.greenMultiplier;
            blue = emissive[2] * strength * pass.blueMultiplier;
            outputAlpha = 1.0f;
        }
        GltfTextureInfo textureInfo = pass.textureInfo;
        float[] uv = textureInfo.texCoord() == 1 ? uv1 : uv0;
        float u = uv == null ? 0.0f : uv[vertex * 2];
        float v = uv == null ? 0.0f : uv[vertex * 2 + 1];
        textureInfo.transform(u, v, skinScratch);
        u = skinScratch[0];
        v = skinScratch[1];
        consumer.vertex(model, x, y, z)
            .color(
                channel(red),
                channel(green),
                channel(blue),
                channel(outputAlpha)
            )
            .uv(u, v)
            .overlayCoords(packedOverlay)
            .uv2(packedLight)
            .normal(normalMatrix, nx, ny, nz)
            .endVertex();
    }

    private static boolean hasVisibleEmission(GltfMaterial material) {
        if (material.emissiveStrength() <= 0.0f) return false;
        float[] emissive = material.emissive();
        return emissive[0] > 0.0f || emissive[1] > 0.0f || emissive[2] > 0.0f;
    }

    private static int emissivePassCount(GltfMaterial material) {
        float[] emissive = material.emissive();
        float peak = Math.max(emissive[0], Math.max(emissive[1], emissive[2]))
            * material.emissiveStrength();
        return Math.max(1, Math.min(MAX_EMISSIVE_PASSES, (int) Math.ceil(peak)));
    }

    private static double transformDepth(Matrix4f matrix, float x, float y, float z) {
        return (double) matrix.m02() * x + (double) matrix.m12() * y
            + (double) matrix.m22() * z + matrix.m32();
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }

    private record ResourceLocationHolder(net.minecraft.resources.ResourceLocation value) {
    }

    private record DrawItem(GltfPrimitive primitive, GltfMaterial material,
                            MaterialPass pass, RenderType renderType,
                            Matrix4f model, Matrix3f normalMatrix,
                            float[] skin, int jointCount, float[] morphWeights, double depth,
                            GltfOcclusionCuller.QueryKey queryKey,
                            boolean fullBright, float alpha) {
    }

    private enum PassKind {
        BASE,
        EMISSIVE
    }

    private record MaterialPass(PassKind kind, GltfTextureInfo textureInfo, float colorScale,
                                float redMultiplier, float greenMultiplier,
                                float blueMultiplier) {
        private static MaterialPass base(GltfMaterial material, float[] tint) {
            return new MaterialPass(
                PassKind.BASE, material.baseColorTextureInfo(), 1.0f,
                tint[0], tint[1], tint[2]
            );
        }

        private static MaterialPass emissive(GltfMaterial material, float strength, float[] tint) {
            return new MaterialPass(
                PassKind.EMISSIVE,
                material.emissiveTextureInfo(),
                strength,
                tint[0], tint[1], tint[2]
            );
        }
    }
}
