package org.lytharalab.gfbs.gltf.client.model;

import com.mojang.math.Transformation;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.model.IModelBuilder;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
import net.minecraftforge.client.model.geometry.SimpleUnbakedGeometry;
import net.minecraftforge.client.model.pipeline.QuadBakingVertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lytharalab.gfbs.gltf.api.animation.ModelPose;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfMesh;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfScene;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;
import org.lytharalab.gfbs.gltf.api.model.PrimitiveMode;
import org.lytharalab.gfbs.gltf.client.render.GltfVertexTransforms;
import org.lytharalab.gfbs.gltf.core.animation.PoseTransforms;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Forge baked-model representation of a glTF scene at its default pose.
 *
 * <p>This path is intended for static blocks and items. Animated instances should keep using
 * {@code GltfRenderer}, whose geometry is submitted as native triangles.</p>
 */
public final class StaticGltfGeometry extends SimpleUnbakedGeometry<StaticGltfGeometry> {
    private static final long MAX_BAKED_QUADS = 1_000_000L;
    private static final float BOUNDARY_EPSILON = 1.0e-5f;
    private static final float DEGENERATE_EPSILON = 1.0e-16f;

    private final GltfAsset asset;
    private final Settings settings;
    private final Set<String> componentNames;

    public StaticGltfGeometry(GltfAsset asset, Settings settings) {
        this.asset = asset;
        this.settings = settings;
        this.componentNames = collectComponentNames(asset);
        validateStaticScene();
    }

    @Override
    protected void addQuads(IGeometryBakingContext context, IModelBuilder<?> modelBuilder,
                            ModelBaker baker, Function<Material, TextureAtlasSprite> spriteGetter,
                            ModelState modelState, ResourceLocation modelLocation) {
        ModelPose pose = new ModelPose(asset);
        float[] worldMatrices = PoseTransforms.computeWorldMatrices(pose);
        GltfScene scene = asset.scenes().get(settings.scene());
        BitSet visited = new BitSet(asset.nodes().size());
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        int[] roots = scene.roots();
        for (int i = roots.length - 1; i >= 0; i--) pending.push(roots[i]);

        Transformation rootTransform = context.getRootTransform();
        Transformation bakeTransform = rootTransform.isIdentity()
            ? modelState.getRotation()
            : modelState.getRotation().compose(rootTransform);
        Matrix4f forgeTransform = new Matrix4f(bakeTransform.blockCenterToCorner().getMatrix());
        Matrix4f importTransform = new Matrix4f()
            .translation(settings.translation())
            .scale(settings.scale());

        while (!pending.isEmpty()) {
            int nodeIndex = pending.pop();
            if (visited.get(nodeIndex)) continue;
            visited.set(nodeIndex);
            GltfNode node = asset.nodes().get(nodeIndex);
            boolean visible = context.isComponentVisible(componentName(node, nodeIndex), true)
                && (node.name().isBlank() || context.isComponentVisible(node.name(), true));
            if (visible) {
                bakeNode(context, modelBuilder, spriteGetter, pose, worldMatrices, forgeTransform,
                    importTransform, nodeIndex, node);
            }
            int[] children = node.children();
            for (int i = children.length - 1; i >= 0; i--) pending.push(children[i]);
        }
    }

    @Override
    public Set<String> getConfigurableComponentNames() {
        return componentNames;
    }

    private void bakeNode(IGeometryBakingContext context, IModelBuilder<?> modelBuilder,
                          Function<Material, TextureAtlasSprite> spriteGetter, ModelPose pose,
                          float[] worldMatrices, Matrix4f forgeTransform, Matrix4f importTransform,
                          int nodeIndex, GltfNode node) {
        Matrix4f nodeWorld = new Matrix4f().set(worldMatrices, nodeIndex * 16);
        Matrix4f transform = new Matrix4f(forgeTransform).mul(importTransform).mul(nodeWorld);
        Matrix3f normalTransform = normalMatrix(transform);
        float[] skin = null;
        int jointCount = 0;
        if (node.skin() >= 0) {
            GltfSkin gltfSkin = asset.skins().get(node.skin());
            jointCount = gltfSkin.joints().length;
            skin = PoseTransforms.computeSkinPalette(gltfSkin, nodeIndex, worldMatrices);
        }

        for (int meshIndex : node.meshes()) {
            GltfMesh mesh = asset.meshes().get(meshIndex);
            float[] morphWeights = pose.node(nodeIndex).weights();
            if (morphWeights == null) morphWeights = mesh.defaultMorphWeights();
            for (GltfPrimitive primitive : mesh.primitives()) {
                GltfMaterial material = asset.materials().get(primitive.material());
                TextureAtlasSprite sprite = spriteGetter.apply(resolveMaterial(
                    context, primitive.material(), material
                ));
                bakePrimitive(context, modelBuilder, primitive, material, sprite, transform,
                    normalTransform, skin, jointCount, morphWeights);
            }
        }
    }

    private void bakePrimitive(IGeometryBakingContext context, IModelBuilder<?> modelBuilder,
                               GltfPrimitive primitive, GltfMaterial material,
                               TextureAtlasSprite sprite, Matrix4f transform,
                               Matrix3f normalTransform, float[] skin, int jointCount,
                               float[] morphWeights) {
        GltfVertexTransforms.PreparedGeometry geometry =
            GltfVertexTransforms.prepare(primitive, morphWeights);
        float[] positions = geometry.positions();
        float[] normals = geometry.normals();
        float[] uv0 = primitive.texCoords0();
        float[] uv1 = primitive.texCoords1();
        float[] colors = primitive.colors();
        int[] joints = primitive.joints();
        float[] weights = primitive.weights();
        int[] indices = indices(primitive);
        float[] skinScratch = new float[6];
        boolean mirrored = transform.determinant() < 0.0f;

        TriangleConsumer consumer = (a, b, c) -> {
            BakedVertex[] vertices = transformTriangle(
                a, mirrored ? c : b, mirrored ? b : c,
                material, positions, normals, uv0, uv1, colors, joints, weights,
                skin, jointCount, skinScratch, transform, normalTransform
            );
            if (isDegenerate(vertices)) return;
            for (BakedVertex[] quadVertices : quadrangulate(vertices)) {
                BakedQuad front = bakeQuad(
                    quadVertices, sprite, false, context.useAmbientOcclusion()
                );
                addQuad(modelBuilder, front, quadVertices, material.doubleSided());
                if (material.doubleSided()) {
                    BakedQuad back = bakeQuad(
                        quadVertices, sprite, true, context.useAmbientOcclusion()
                    );
                    modelBuilder.addUnculledFace(back);
                }
            }
        };
        emitTriangles(primitive.mode(), indices, consumer);
    }

    private BakedVertex[] transformTriangle(
        int a, int b, int c, GltfMaterial material, float[] positions, float[] normals,
        float[] uv0, float[] uv1, float[] colors, int[] joints, float[] weights,
        float[] skin, int jointCount, float[] skinScratch, Matrix4f transform,
        Matrix3f normalTransform
    ) {
        int[] triangle = {a, b, c};
        float[] localFaceNormal = normals == null
            ? GltfVertexTransforms.faceNormal(positions, a, b, c) : null;
        BakedVertex[] result = new BakedVertex[3];
        for (int i = 0; i < 3; i++) {
            int vertex = triangle[i];
            int positionOffset = vertex * 3;
            float x = positions[positionOffset];
            float y = positions[positionOffset + 1];
            float z = positions[positionOffset + 2];
            float nx = normals == null ? localFaceNormal[0] : normals[positionOffset];
            float ny = normals == null ? localFaceNormal[1] : normals[positionOffset + 1];
            float nz = normals == null ? localFaceNormal[2] : normals[positionOffset + 2];
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

            Vector4f transformedPosition = transform.transform(new Vector4f(x, y, z, 1.0f));
            Vector3f transformedNormal = normalTransform.transform(new Vector3f(nx, ny, nz));
            if (transformedNormal.lengthSquared() > 1.0e-16f) transformedNormal.normalize();
            else transformedNormal.set(0.0f, 1.0f, 0.0f);

            int colorComponents = colors == null ? 0 : colors.length / (positions.length / 3);
            float[] base = material.baseColor();
            float red = colors == null ? 1.0f : colors[vertex * colorComponents];
            float green = colors == null ? 1.0f : colors[vertex * colorComponents + 1];
            float blue = colors == null ? 1.0f : colors[vertex * colorComponents + 2];
            float alpha = colors == null || colorComponents < 4
                ? 1.0f : colors[vertex * colorComponents + 3];
            float[] uv = material.baseColorTexCoord() == 1 ? uv1 : uv0;
            float u = uv == null ? 0.0f : uv[vertex * 2];
            float v = uv == null ? 0.0f : uv[vertex * 2 + 1];
            if (settings.flipV()) v = 1.0f - v;
            result[i] = new BakedVertex(
                transformedPosition.x, transformedPosition.y, transformedPosition.z,
                transformedNormal.x, transformedNormal.y, transformedNormal.z,
                u, v,
                channel(red * base[0]), channel(green * base[1]),
                channel(blue * base[2]), channel(alpha * base[3])
            );
        }
        if (normals == null) {
            float[] transformed = {
                result[0].x, result[0].y, result[0].z,
                result[1].x, result[1].y, result[1].z,
                result[2].x, result[2].y, result[2].z
            };
            float[] face = GltfVertexTransforms.faceNormal(transformed, 0, 1, 2);
            for (int i = 0; i < result.length; i++) result[i] = result[i].withNormal(face);
        }
        return result;
    }

    private BakedQuad bakeQuad(BakedVertex[] vertices, TextureAtlasSprite sprite,
                               boolean reverse, boolean ambientOcclusion) {
        int[] order = reverse ? new int[]{0, 3, 2, 1} : new int[]{0, 1, 2, 3};
        Vector3f faceNormal = geometricNormal(vertices, reverse);
        QuadBakingVertexConsumer.Buffered baker = new QuadBakingVertexConsumer.Buffered();
        baker.setSprite(sprite);
        baker.setTintIndex(-1);
        baker.setShade(settings.shade());
        baker.setHasAmbientOcclusion(ambientOcclusion);
        baker.setDirection(Direction.getNearest(faceNormal.x, faceNormal.y, faceNormal.z));
        for (int index : order) {
            BakedVertex vertex = vertices[index];
            float normalSign = reverse ? -1.0f : 1.0f;
            baker.vertex(vertex.x, vertex.y, vertex.z)
                .color(vertex.red, vertex.green, vertex.blue, vertex.alpha)
                .uv(sprite.getU(vertex.u * 16.0), sprite.getV(vertex.v * 16.0))
                .uv2(0)
                .normal(vertex.nx * normalSign, vertex.ny * normalSign, vertex.nz * normalSign)
                .endVertex();
        }
        return baker.getQuad();
    }

    private static BakedVertex[][] quadrangulate(BakedVertex[] triangle) {
        BakedVertex a = triangle[0];
        BakedVertex b = triangle[1];
        BakedVertex c = triangle[2];
        BakedVertex ab = BakedVertex.blend(a, 0.5f, b, 0.5f, c, 0.0f);
        BakedVertex bc = BakedVertex.blend(a, 0.0f, b, 0.5f, c, 0.5f);
        BakedVertex ca = BakedVertex.blend(a, 0.5f, b, 0.0f, c, 0.5f);
        BakedVertex center = BakedVertex.blend(
            a, 1.0f / 3.0f, b, 1.0f / 3.0f, c, 1.0f / 3.0f
        );
        return new BakedVertex[][]{
            {a, ab, center, ca},
            {ab, b, bc, center},
            {center, bc, c, ca}
        };
    }

    private static boolean isDegenerate(BakedVertex[] triangle) {
        float abX = triangle[1].x - triangle[0].x;
        float abY = triangle[1].y - triangle[0].y;
        float abZ = triangle[1].z - triangle[0].z;
        float acX = triangle[2].x - triangle[0].x;
        float acY = triangle[2].y - triangle[0].y;
        float acZ = triangle[2].z - triangle[0].z;
        float x = abY * acZ - abZ * acY;
        float y = abZ * acX - abX * acZ;
        float z = abX * acY - abY * acX;
        return !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
            || x * x + y * y + z * z <= DEGENERATE_EPSILON;
    }

    private void addQuad(IModelBuilder<?> modelBuilder, BakedQuad quad,
                         BakedVertex[] vertices, boolean doubleSided) {
        Direction cullFace = settings.automaticCulling() && !doubleSided
            ? boundaryFace(quad.getDirection(), vertices) : null;
        if (cullFace == null) modelBuilder.addUnculledFace(quad);
        else modelBuilder.addCulledFace(cullFace, quad);
    }

    private Material resolveMaterial(IGeometryBakingContext context, int materialIndex,
                                     GltfMaterial material) {
        String explicit = settings.materialTextures().get(Integer.toString(materialIndex));
        if (explicit == null && !material.name().isBlank()) {
            explicit = settings.materialTextures().get(material.name());
        }
        if (explicit != null) return context.getMaterial(explicit);

        String indexed = "material_" + materialIndex;
        if (context.hasMaterial(indexed)) return context.getMaterial(indexed);
        if (!material.name().isBlank() && context.hasMaterial(material.name())) {
            return context.getMaterial(material.name());
        }
        if (context.hasMaterial("texture")) return context.getMaterial("texture");
        return context.getMaterial("particle");
    }

    private void validateStaticScene() {
        if (asset.scenes().isEmpty()) {
            throw new IllegalArgumentException("Static glTF model has no scene");
        }
        if (settings.scene() < 0 || settings.scene() >= asset.scenes().size()) {
            throw new IllegalArgumentException("Static glTF scene index is out of range");
        }
        long quads = 0;
        BitSet visited = new BitSet(asset.nodes().size());
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        for (int root : asset.scenes().get(settings.scene()).roots()) pending.push(root);
        while (!pending.isEmpty()) {
            int nodeIndex = pending.pop();
            if (visited.get(nodeIndex)) continue;
            visited.set(nodeIndex);
            GltfNode node = asset.nodes().get(nodeIndex);
            for (int meshIndex : node.meshes()) {
                for (GltfPrimitive primitive : asset.meshes().get(meshIndex).primitives()) {
                    if (!isTriangleMode(primitive.mode())) {
                        throw new IllegalArgumentException(
                            "Static Forge models support triangle glTF primitives only; found "
                                + primitive.mode()
                        );
                    }
                    int elementCount = primitive.indexCount();
                    long primitiveTriangles = switch (primitive.mode()) {
                        case TRIANGLES -> elementCount / 3L;
                        case TRIANGLE_STRIP, TRIANGLE_FAN -> elementCount - 2L;
                        default -> 0L;
                    };
                    long primitiveQuads = Math.multiplyExact(primitiveTriangles, 3L);
                    if (asset.materials().get(primitive.material()).doubleSided()) {
                        primitiveQuads = Math.multiplyExact(primitiveQuads, 2L);
                    }
                    quads = Math.addExact(quads, primitiveQuads);
                    if (quads > MAX_BAKED_QUADS) {
                        throw new IllegalArgumentException(
                            "Static glTF model exceeds the baked quad safety limit of "
                                + MAX_BAKED_QUADS
                        );
                    }
                }
            }
            for (int child : node.children()) pending.push(child);
        }
    }

    private static int[] indices(GltfPrimitive primitive) {
        int[] indices = primitive.indices();
        if (indices != null) return indices;
        indices = new int[primitive.vertexCount()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        return indices;
    }

    private static void emitTriangles(PrimitiveMode mode, int[] indices,
                                      TriangleConsumer consumer) {
        switch (mode) {
            case TRIANGLES -> {
                for (int i = 0; i < indices.length; i += 3) {
                    consumer.accept(indices[i], indices[i + 1], indices[i + 2]);
                }
            }
            case TRIANGLE_STRIP -> {
                for (int i = 2; i < indices.length; i++) {
                    int a = indices[i - 2];
                    int b = indices[i - 1];
                    if ((i & 1) != 0) {
                        int swap = a;
                        a = b;
                        b = swap;
                    }
                    consumer.accept(a, b, indices[i]);
                }
            }
            case TRIANGLE_FAN -> {
                for (int i = 2; i < indices.length; i++) {
                    consumer.accept(indices[0], indices[i - 1], indices[i]);
                }
            }
            default -> throw new IllegalArgumentException("Non-triangle primitive: " + mode);
        }
    }

    private static Matrix3f normalMatrix(Matrix4f transform) {
        Matrix3f normal = new Matrix3f(transform);
        if (Math.abs(normal.determinant()) > 1.0e-10f) normal.invert().transpose();
        else normal.identity();
        return normal;
    }

    private static Vector3f geometricNormal(BakedVertex[] vertices, boolean reverse) {
        Vector3f first = new Vector3f(
            vertices[1].x - vertices[0].x,
            vertices[1].y - vertices[0].y,
            vertices[1].z - vertices[0].z
        );
        Vector3f second = new Vector3f(
            vertices[2].x - vertices[0].x,
            vertices[2].y - vertices[0].y,
            vertices[2].z - vertices[0].z
        );
        first.cross(second);
        if (first.lengthSquared() <= 1.0e-16f) first.set(0.0f, 1.0f, 0.0f);
        else first.normalize();
        return reverse ? first.negate() : first;
    }

    private static Direction boundaryFace(Direction direction, BakedVertex[] vertices) {
        for (BakedVertex vertex : vertices) {
            float value = switch (direction.getAxis()) {
                case X -> vertex.x;
                case Y -> vertex.y;
                case Z -> vertex.z;
            };
            float boundary = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? 1.0f : 0.0f;
            if (Math.abs(value - boundary) > BOUNDARY_EPSILON) return null;
        }
        return direction;
    }

    private static Set<String> collectComponentNames(GltfAsset asset) {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < asset.nodes().size(); i++) {
            GltfNode node = asset.nodes().get(i);
            names.add(componentName(node, i));
            if (!node.name().isBlank()) names.add(node.name());
        }
        return Set.copyOf(names);
    }

    private static String componentName(GltfNode node, int index) {
        return "node_" + index;
    }

    private static boolean isTriangleMode(PrimitiveMode mode) {
        return mode == PrimitiveMode.TRIANGLES
            || mode == PrimitiveMode.TRIANGLE_STRIP
            || mode == PrimitiveMode.TRIANGLE_FAN;
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }

    @FunctionalInterface
    private interface TriangleConsumer {
        void accept(int a, int b, int c);
    }

    private record BakedVertex(
        float x, float y, float z,
        float nx, float ny, float nz,
        float u, float v,
        int red, int green, int blue, int alpha
    ) {
        private static BakedVertex blend(BakedVertex a, float aWeight,
                                         BakedVertex b, float bWeight,
                                         BakedVertex c, float cWeight) {
            float nx = a.nx * aWeight + b.nx * bWeight + c.nx * cWeight;
            float ny = a.ny * aWeight + b.ny * bWeight + c.ny * cWeight;
            float nz = a.nz * aWeight + b.nz * bWeight + c.nz * cWeight;
            float normalLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (normalLength > 1.0e-8f) {
                nx /= normalLength;
                ny /= normalLength;
                nz /= normalLength;
            } else {
                nx = 0.0f;
                ny = 1.0f;
                nz = 0.0f;
            }
            return new BakedVertex(
                a.x * aWeight + b.x * bWeight + c.x * cWeight,
                a.y * aWeight + b.y * bWeight + c.y * cWeight,
                a.z * aWeight + b.z * bWeight + c.z * cWeight,
                nx, ny, nz,
                a.u * aWeight + b.u * bWeight + c.u * cWeight,
                a.v * aWeight + b.v * bWeight + c.v * cWeight,
                Math.round(a.red * aWeight + b.red * bWeight + c.red * cWeight),
                Math.round(a.green * aWeight + b.green * bWeight + c.green * cWeight),
                Math.round(a.blue * aWeight + b.blue * bWeight + c.blue * cWeight),
                Math.round(a.alpha * aWeight + b.alpha * bWeight + c.alpha * cWeight)
            );
        }

        private BakedVertex withNormal(float[] normal) {
            return new BakedVertex(
                x, y, z, normal[0], normal[1], normal[2],
                u, v, red, green, blue, alpha
            );
        }
    }

    public record Settings(
        int scene,
        Vector3f scale,
        Vector3f translation,
        boolean flipV,
        boolean shade,
        boolean automaticCulling,
        Map<String, String> materialTextures
    ) {
        public Settings {
            scale = new Vector3f(scale);
            translation = new Vector3f(translation);
            materialTextures = Map.copyOf(materialTextures);
        }

        @Override
        public Vector3f scale() {
            return new Vector3f(scale);
        }

        @Override
        public Vector3f translation() {
            return new Vector3f(translation);
        }
    }
}
