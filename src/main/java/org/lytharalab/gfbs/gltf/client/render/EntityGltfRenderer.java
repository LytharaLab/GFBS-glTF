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
import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfMesh;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfScene;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;
import org.lytharalab.gfbs.gltf.api.model.MorphTarget;
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
        float[] world = PoseTransforms.computeWorldMatrices(pose);
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
            .thenComparingDouble(item -> item.material.alphaMode() == AlphaMode.BLEND
                ? item.depth : 0.0));

        for (DrawItem item : visible) {
            int light = shadowPass
                || options.lightMode() == GltfRenderOptions.LightMode.FULLBRIGHT
                || isEmissive(item.material) ? LightTexture.FULL_BRIGHT : packedLight;
            emitPrimitive(
                buffers.getBuffer(item.renderType),
                item.renderType.mode(),
                item.primitive,
                item.material,
                item.model,
                item.normalMatrix,
                item.skin,
                item.jointCount,
                item.morphWeights,
                light,
                packedOverlay,
                options.alpha()
            );
        }

        if (useOcclusion) {
            GltfOcclusionCuller.State state = GltfOcclusionCuller.beginQueries();
            try {
                Matrix4f projection = context.projection();
                for (DrawItem item : candidates) {
                    if (!item.primitive.hasDynamicGeometry()) {
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
            if (!instance.nodeVisible(nodeIndex) || instance.collision().isNodeHidden(nodeIndex)) continue;
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
            for (int meshIndex : node.meshes()) {
                GltfMesh mesh = asset.meshes().get(meshIndex);
                float[] morphWeights = pose.node(nodeIndex).weights();
                if (morphWeights == null) morphWeights = mesh.defaultMorphWeights();
                for (int primitiveIndex = 0; primitiveIndex < mesh.primitives().size(); primitiveIndex++) {
                    GltfPrimitive primitive = mesh.primitives().get(primitiveIndex);
                    if (shadowPass && !isTriangleMode(primitive.mode())) continue;
                    GltfRenderPart part = new GltfRenderPart(
                        nodeIndex, node.name(), meshIndex, primitiveIndex, primitive.material()
                    );
                    if (!options.partFilter().test(part)) continue;
                    if (!shadowPass
                        && !GltfPrimitiveCuller.isVisible(primitive, model, context, options)) continue;
                    GltfMaterial material = asset.materials().get(primitive.material());
                    ResourceLocationHolder texture = new ResourceLocationHolder(
                        gpu.materialTexture(primitive.material(), shaderPack)
                    );
                    RenderType renderType = shadowPass
                        ? resolveShadowRenderType(material, texture, options)
                        : resolveRenderType(part, primitive, material, texture, options);
                    double depth = transformDepth(
                        model,
                        primitive.bounds().centerX(),
                        primitive.bounds().centerY(),
                        primitive.bounds().centerZ()
                    );
                    items.add(new DrawItem(
                        primitive, material, renderType, new Matrix4f(model), new Matrix3f(normalMatrix),
                        skin, jointCount, morphWeights, depth,
                        new GltfOcclusionCuller.QueryKey(
                            instance.id(), nodeIndex, meshIndex, primitiveIndex
                        )
                    ));
                }
            }
            int[] children = node.children();
            for (int i = children.length - 1; i >= 0; i--) pending.push(children[i]);
        }
        return items;
    }

    private static RenderType resolveShadowRenderType(GltfMaterial material,
                                                      ResourceLocationHolder texture,
                                                      GltfRenderOptions options) {
        boolean cull = switch (options.cullMode()) {
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
                                                GltfRenderOptions options) {
        RenderType custom = options.nodeRenderTypes().get(part.nodeName());
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
        boolean cull = switch (options.cullMode()) {
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

    private static boolean modeCompatible(VertexFormat.Mode renderMode, PrimitiveMode primitiveMode) {
        if (isTriangleMode(primitiveMode)) {
            return renderMode == VertexFormat.Mode.QUADS || renderMode == VertexFormat.Mode.TRIANGLES;
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

    private static void emitPrimitive(VertexConsumer consumer, VertexFormat.Mode renderMode,
                                      GltfPrimitive primitive, GltfMaterial material,
                                      Matrix4f model, Matrix3f normalMatrix,
                                      float[] skin, int jointCount, float[] morphWeights,
                                      int packedLight, int packedOverlay, float alpha) {
        float[] positions = primitive.positions();
        float[] normals = primitive.normals();
        float[] uv0 = primitive.texCoords0();
        float[] uv1 = primitive.texCoords1();
        float[] colors = primitive.colors();
        int[] joints = primitive.joints();
        float[] weights = primitive.weights();
        applyMorphs(primitive, morphWeights, positions, normals);
        int[] indices = primitive.indices();
        if (indices == null) {
            indices = new int[primitive.vertexCount()];
            for (int i = 0; i < indices.length; i++) indices[i] = i;
        }
        if (!isTriangleMode(primitive.mode())) {
            emitLines(consumer, primitive.mode(), indices, material, model, normalMatrix,
                positions, normals, uv0, uv1, colors, joints, weights, skin, jointCount,
                packedLight, packedOverlay, alpha);
            return;
        }
        switch (primitive.mode()) {
            case TRIANGLES -> {
                for (int i = 0; i < indices.length; i += 3) {
                    emitTriangle(consumer, renderMode, indices[i], indices[i + 1], indices[i + 2],
                        material, model, normalMatrix, positions, normals, uv0, uv1, colors,
                        joints, weights, skin, jointCount, packedLight, packedOverlay, alpha);
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
                    emitTriangle(consumer, renderMode, a, b, c, material, model, normalMatrix,
                        positions, normals, uv0, uv1, colors, joints, weights, skin, jointCount,
                        packedLight, packedOverlay, alpha);
                }
            }
            case TRIANGLE_FAN -> {
                for (int i = 2; i < indices.length; i++) {
                    emitTriangle(consumer, renderMode, indices[0], indices[i - 1], indices[i],
                        material, model, normalMatrix, positions, normals, uv0, uv1, colors,
                        joints, weights, skin, jointCount, packedLight, packedOverlay, alpha);
                }
            }
            default -> throw new IllegalStateException("Unexpected primitive mode");
        }
    }

    private static void emitLines(VertexConsumer consumer, PrimitiveMode mode, int[] indices,
                                  GltfMaterial material, Matrix4f model, Matrix3f normalMatrix,
                                  float[] positions, float[] normals, float[] uv0, float[] uv1,
                                  float[] colors, int[] joints, float[] weights, float[] skin,
                                  int jointCount, int light, int overlay, float alpha) {
        switch (mode) {
            case POINTS -> {
                for (int index : indices) emitLine(consumer, index, index, material, model,
                    normalMatrix, positions, normals, uv0, uv1, colors, joints, weights,
                    skin, jointCount, light, overlay, alpha);
            }
            case LINES -> {
                for (int i = 0; i + 1 < indices.length; i += 2) emitLine(
                    consumer, indices[i], indices[i + 1], material, model, normalMatrix,
                    positions, normals, uv0, uv1, colors, joints, weights, skin, jointCount,
                    light, overlay, alpha
                );
            }
            case LINE_STRIP, LINE_LOOP -> {
                for (int i = 1; i < indices.length; i++) emitLine(
                    consumer, indices[i - 1], indices[i], material, model, normalMatrix,
                    positions, normals, uv0, uv1, colors, joints, weights, skin, jointCount,
                    light, overlay, alpha
                );
                if (mode == PrimitiveMode.LINE_LOOP) emitLine(
                    consumer, indices[indices.length - 1], indices[0], material, model, normalMatrix,
                    positions, normals, uv0, uv1, colors, joints, weights, skin, jointCount,
                    light, overlay, alpha
                );
            }
            default -> throw new IllegalStateException("Unexpected line mode");
        }
    }

    private static void emitLine(VertexConsumer consumer, int first, int second,
                                 GltfMaterial material, Matrix4f model, Matrix3f normalMatrix,
                                 float[] positions, float[] normals, float[] uv0, float[] uv1,
                                 float[] colors, int[] joints, float[] weights, float[] skin,
                                 int jointCount, int light, int overlay, float alpha) {
        emitVertex(consumer, first, material, model, normalMatrix, positions, normals,
            uv0, uv1, colors, joints, weights, skin, jointCount, light, overlay,
            new float[]{0, 1, 0}, alpha);
        emitVertex(consumer, second, material, model, normalMatrix, positions, normals,
            uv0, uv1, colors, joints, weights, skin, jointCount, light, overlay,
            new float[]{0, 1, 0}, alpha);
    }

    private static void emitTriangle(VertexConsumer consumer, VertexFormat.Mode renderMode,
                                     int a, int b, int c, GltfMaterial material,
                                     Matrix4f model, Matrix3f normalMatrix,
                                     float[] positions, float[] normals, float[] uv0, float[] uv1,
                                     float[] colors, int[] joints, float[] weights, float[] skin,
                                     int jointCount, int packedLight, int packedOverlay, float alpha) {
        float[] generatedNormal = normals == null ? faceNormal(positions, a, b, c) : null;
        emitVertex(consumer, a, material, model, normalMatrix, positions, normals, uv0, uv1,
            colors, joints, weights, skin, jointCount, packedLight, packedOverlay,
            generatedNormal, alpha);
        emitVertex(consumer, b, material, model, normalMatrix, positions, normals, uv0, uv1,
            colors, joints, weights, skin, jointCount, packedLight, packedOverlay,
            generatedNormal, alpha);
        emitVertex(consumer, c, material, model, normalMatrix, positions, normals, uv0, uv1,
            colors, joints, weights, skin, jointCount, packedLight, packedOverlay,
            generatedNormal, alpha);
        if (renderMode == VertexFormat.Mode.QUADS) {
            emitVertex(consumer, c, material, model, normalMatrix, positions, normals, uv0, uv1,
                colors, joints, weights, skin, jointCount, packedLight, packedOverlay,
                generatedNormal, alpha);
        }
    }

    private static void emitVertex(VertexConsumer consumer, int vertex, GltfMaterial material,
                                   Matrix4f model, Matrix3f normalMatrix,
                                   float[] positions, float[] normals, float[] uv0, float[] uv1,
                                   float[] colors, int[] joints, float[] weights, float[] skin,
                                   int jointCount, int packedLight, int packedOverlay,
                                   float[] generatedNormal, float alpha) {
        int position = vertex * 3;
        float x = positions[position];
        float y = positions[position + 1];
        float z = positions[position + 2];
        float nx = normals == null ? generatedNormal[0] : normals[position];
        float ny = normals == null ? generatedNormal[1] : normals[position + 1];
        float nz = normals == null ? generatedNormal[2] : normals[position + 2];
        if (skin != null && joints != null && weights != null) {
            float[] skinned = skinVertex(
                vertex, x, y, z, nx, ny, nz, joints, weights, skin, jointCount
            );
            x = skinned[0];
            y = skinned[1];
            z = skinned[2];
            nx = skinned[3];
            ny = skinned[4];
            nz = skinned[5];
        }
        float[] base = material.baseColor();
        int colorComponents = colors == null ? 0 : colors.length / (positions.length / 3);
        float red = colors == null ? 1.0f : colors[vertex * colorComponents];
        float green = colors == null ? 1.0f : colors[vertex * colorComponents + 1];
        float blue = colors == null ? 1.0f : colors[vertex * colorComponents + 2];
        float vertexAlpha = colors == null || colorComponents < 4
            ? 1.0f : colors[vertex * colorComponents + 3];
        float[] uv = material.baseColorTexCoord() == 1 ? uv1 : uv0;
        float u = uv == null ? 0.0f : uv[vertex * 2];
        float v = uv == null ? 0.0f : uv[vertex * 2 + 1];
        consumer.vertex(model, x, y, z)
            .color(
                channel(red * base[0]),
                channel(green * base[1]),
                channel(blue * base[2]),
                channel(vertexAlpha * base[3] * alpha)
            )
            .uv(u, v)
            .overlayCoords(packedOverlay)
            .uv2(packedLight)
            .normal(normalMatrix, nx, ny, nz)
            .endVertex();
    }

    private static float[] faceNormal(float[] positions, int a, int b, int c) {
        int first = a * 3;
        int second = b * 3;
        int third = c * 3;
        float abX = positions[second] - positions[first];
        float abY = positions[second + 1] - positions[first + 1];
        float abZ = positions[second + 2] - positions[first + 2];
        float acX = positions[third] - positions[first];
        float acY = positions[third + 1] - positions[first + 1];
        float acZ = positions[third + 2] - positions[first + 2];
        float x = abY * acZ - abZ * acY;
        float y = abZ * acX - abX * acZ;
        float z = abX * acY - abY * acX;
        double lengthSquared = (double) x * x + (double) y * y + (double) z * z;
        if (!Double.isFinite(lengthSquared) || lengthSquared <= 1.0e-16) {
            return new float[]{0.0f, 1.0f, 0.0f};
        }
        float inverseLength = (float) (1.0 / Math.sqrt(lengthSquared));
        return new float[]{x * inverseLength, y * inverseLength, z * inverseLength};
    }

    private static float[] skinVertex(int vertex, float x, float y, float z,
                                      float nx, float ny, float nz, int[] joints,
                                      float[] weights, float[] palette, int jointCount) {
        float px = 0.0f;
        float py = 0.0f;
        float pz = 0.0f;
        float totalWeight = 0.0f;
        float tx = 0.0f;
        float ty = 0.0f;
        float tz = 0.0f;
        int offset = vertex * 4;
        for (int i = 0; i < 4; i++) {
            float weight = Math.max(0.0f, weights[offset + i]);
            int joint = Math.max(0, Math.min(jointCount - 1, joints[offset + i]));
            int matrix = joint * 16;
            px += (palette[matrix] * x + palette[matrix + 4] * y
                + palette[matrix + 8] * z + palette[matrix + 12]) * weight;
            py += (palette[matrix + 1] * x + palette[matrix + 5] * y
                + palette[matrix + 9] * z + palette[matrix + 13]) * weight;
            pz += (palette[matrix + 2] * x + palette[matrix + 6] * y
                + palette[matrix + 10] * z + palette[matrix + 14]) * weight;
            totalWeight += weight;
            tx += (palette[matrix] * nx + palette[matrix + 4] * ny
                + palette[matrix + 8] * nz) * weight;
            ty += (palette[matrix + 1] * nx + palette[matrix + 5] * ny
                + palette[matrix + 9] * nz) * weight;
            tz += (palette[matrix + 2] * nx + palette[matrix + 6] * ny
                + palette[matrix + 10] * nz) * weight;
        }
        if (totalWeight <= 1.0e-8f) return new float[]{x, y, z, nx, ny, nz};
        float inverseWeight = 1.0f / totalWeight;
        float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (length > 1.0e-8f) {
            tx /= length;
            ty /= length;
            tz /= length;
        }
        return new float[]{
            px * inverseWeight, py * inverseWeight, pz * inverseWeight, tx, ty, tz
        };
    }

    private static void applyMorphs(GltfPrimitive primitive, float[] weights,
                                    float[] positions, float[] normals) {
        for (int targetIndex = 0; targetIndex < primitive.morphTargets().size(); targetIndex++) {
            float weight = weights != null && targetIndex < weights.length ? weights[targetIndex] : 0.0f;
            if (weight == 0.0f) continue;
            MorphTarget target = primitive.morphTargets().get(targetIndex);
            addWeighted(positions, target.positions(), weight);
            addWeighted(normals, target.normals(), weight);
        }
        normalizeNormals(normals);
    }

    private static void addWeighted(float[] base, float[] delta, float weight) {
        if (base == null || delta == null) return;
        for (int i = 0; i < Math.min(base.length, delta.length); i++) {
            base[i] += delta[i] * weight;
        }
    }

    private static void normalizeNormals(float[] normals) {
        if (normals == null) return;
        for (int i = 0; i < normals.length; i += 3) {
            float length = (float) Math.sqrt(
                normals[i] * normals[i]
                    + normals[i + 1] * normals[i + 1]
                    + normals[i + 2] * normals[i + 2]
            );
            if (length > 1.0e-8f) {
                normals[i] /= length;
                normals[i + 1] /= length;
                normals[i + 2] /= length;
            }
        }
    }

    private static boolean isEmissive(GltfMaterial material) {
        if (material.emissiveTexture() >= 0) return true;
        float[] emissive = material.emissive();
        return emissive[0] > 0.0f || emissive[1] > 0.0f || emissive[2] > 0.0f;
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
                            RenderType renderType, Matrix4f model, Matrix3f normalMatrix,
                            float[] skin, int jointCount, float[] morphWeights, double depth,
                            GltfOcclusionCuller.QueryKey queryKey) {
    }
}
