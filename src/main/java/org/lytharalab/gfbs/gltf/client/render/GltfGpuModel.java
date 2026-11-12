package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.GFBSglTF;
import org.lytharalab.gfbs.gltf.api.client.node.GltfPrimitiveKey;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderOptions;
import org.lytharalab.gfbs.gltf.api.client.material.GltfPbrBakeOptions;
import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfAssetAccess;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfMesh;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfTextureInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** GPU resources and draw topology shared by all instances of an immutable asset. */
final class GltfGpuModel {
    static final List<GltfMaterialBaker.Page> ORIGINAL_UV = java.util.Collections.singletonList(null);
    private static final long GEOMETRY_BUDGET = 128L * 1024 * 1024;
    private static final int MAX_GEOMETRY_ENTRIES = 4096;
    private static final int COLD_FRAMES = 180;
    private static final Map<GltfAsset, PreparedAsset> PREPARED = new WeakHashMap<>();
    final GltfAsset asset;
    final boolean hasEmission;
    private final GltfGpuTextures textures;
    private final PreparedAsset prepared;
    private final Map<GeometryKey, Geometry> geometry = new LinkedHashMap<>(64, 0.75f, true);
    private long geometryBytes;
    long lastFrame = GltfGpuCache.frame();

    GltfGpuModel(GltfAsset asset) {
        this.asset = asset;
        hasEmission = asset.materials().stream().anyMatch(GltfGeometryPipeline::hasVisibleEmission);
        textures = new GltfGpuTextures(asset);
        prepared = prepared(asset);
    }

    ScenePlan scenePlan(int scene) {
        return prepared.scenes.computeIfAbsent(scene, index -> compileScenePlan(asset, index));
    }

    static void prepareScene(GltfAsset asset, int scene) {
        PreparedAsset prepared = prepared(asset);
        ScenePlan plan = prepared.scenes.computeIfAbsent(scene, index -> compileScenePlan(asset, index));
        plan.index(asset);
        for (NodePlan node : plan.nodes) {
            for (PrimitivePlan part : node.primitives) {
                prepared.sections(part.primitive);
                GltfMaterial material = asset.materials().get(part.primitive.material());
                if (prepared.materials.requiresCharts(part.primitive, material, false, false)) {
                    prepared.materials.bake(part.primitive, material, GltfPbrBakeOptions.DEFAULT, false, false);
                }
            }
        }
    }

    private static PreparedAsset prepared(GltfAsset asset) {
        synchronized (PREPARED) {
            return PREPARED.computeIfAbsent(asset, ignored -> new PreparedAsset(asset.textures()));
        }
    }

    GltfGpuPrimitive geometry(PrimitivePlan plan, GltfMaterial material,
                              GltfGeometryPipeline.PassKind pass) {
        return geometry(plan, material, pass, null);
    }

    GltfPrimitiveClusters.Section[] sections(GltfPrimitive primitive) {
        return prepared.sections(primitive);
    }

    GltfGpuPrimitive geometry(PrimitivePlan plan, GltfMaterial material,
                              GltfGeometryPipeline.PassKind pass, GltfPrimitiveClusters.Section section) {
        boolean emissive = pass == GltfGeometryPipeline.PassKind.EMISSIVE;
        GeometryKey key = emissive
            ? new GeometryKey(plan.primitive, section, pass, material.emissiveTextureInfo(), material.emissiveRed(),
                material.emissiveGreen(), material.emissiveBlue(), 1.0f, AlphaMode.OPAQUE)
            : new GeometryKey(plan.primitive, section, pass, material.baseColorTextureInfo(), material.baseColorRed(),
                material.baseColorGreen(), material.baseColorBlue(), material.baseColorAlpha(), material.alphaMode());
        Geometry cached = geometry.get(key);
        if (cached != null) {
            cached.lastFrame = GltfGpuCache.frame();
            return cached.buffer;
        }

        long bytes = section == null ? GltfGpuPrimitive.estimatedBytes(plan.primitive)
            : (long) section.vertices() * org.lytharalab.gfbs.gltf.api.client.GltfRenderTypes.REQUIRED_FORMAT.getVertexSize();
        if (bytes > GEOMETRY_BUDGET) return null;
        // A scene larger than the resident budget can keep its already resident hot meshes while
        // streaming the excess. Avoid destroying freshly used buffers and reuploading every frame.
        if (geometry.size() >= MAX_GEOMETRY_ENTRIES || geometryBytes + bytes > GEOMETRY_BUDGET
            || !GltfGpuCache.hasGeometrySpace(bytes)) return null;
        GltfGeometryPipeline.MaterialPass materialPass = emissive
            ? GltfGeometryPipeline.MaterialPass.emissiveGpu(material)
            : GltfGeometryPipeline.MaterialPass.base(material, 1.0f, 1.0f, 1.0f);
        try {
            GltfGpuPrimitive compiled = GltfGpuPrimitive.compile(plan.primitive, material, materialPass, section);
            geometry.put(key, new Geometry(compiled, bytes));
            geometryBytes += bytes;
            GltfGpuCache.geometryAllocated(bytes);
            return compiled;
        } catch (RuntimeException failure) {
            geometry.put(key, new Geometry(null, 0));
            GFBSglTF.LOGGER.warn("Streaming glTF geometry after GPU upload failed for {} mesh {} primitive {}",
                asset.id(), plan.meshIndex, plan.primitiveIndex, failure);
            return null;
        }
    }

    private static ScenePlan compileScenePlan(GltfAsset asset, int scene) {
        List<NodePlan> nodes = new ArrayList<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        int[] roots = asset.scenes().get(scene).roots();
        for (int i = roots.length - 1; i >= 0; i--) pending.push(roots[i]);
        while (!pending.isEmpty()) {
            int index = pending.pop();
            if (index < 0) {
                nodes.get(~index).subtreeEndExclusive = nodes.size();
                continue;
            }
            GltfNode node = asset.nodes().get(index);
            List<PrimitivePlan> primitives = new ArrayList<>();
            for (int slot = 0; slot < node.meshCount(); slot++) {
                int meshIndex = node.mesh(slot);
                GltfMesh mesh = asset.meshes().get(meshIndex);
                for (int i = 0; i < mesh.primitives().size(); i++) {
                    primitives.add(new PrimitivePlan(index, node.name(), meshIndex, i, mesh,
                        mesh.primitives().get(i), new GltfPrimitiveKey(index, meshIndex, i)));
                }
            }
            pending.push(~nodes.size());
            for (int i = node.childCount() - 1; i >= 0; i--) pending.push(node.child(i));
            nodes.add(new NodePlan(index, node, primitives.toArray(PrimitivePlan[]::new)));
        }
        return new ScenePlan(nodes.toArray(NodePlan[]::new));
    }

    ResourceLocation materialTexture(GltfMaterial material, boolean shaderPack) {
        return textures.base(material, shaderPack);
    }

    ResourceLocation materialTexture(GltfMaterial material, boolean shaderPack, GltfMaterialBaker.Page page) {
        return textures.base(material, shaderPack, page);
    }

    List<GltfMaterialBaker.Page> materialPages(GltfPrimitive primitive, GltfMaterial material,
                                               boolean shaderPack, GltfRenderOptions options) {
        if (!shaderPack || !OculusPbrTextureBridge.install()) return ORIGINAL_UV;
        if (!options.labPbrEmission()
            && !prepared.materials.requiresCharts(primitive, material, false, false)) return ORIGINAL_UV;
        return prepared.materials.bake(primitive, material, options.pbrBakeOptions(), false, options.labPbrEmission()).pages();
    }

    GltfTextureInfo materialUv(GltfMaterial material, boolean shaderPack) {
        return textures.baseUv(material, shaderPack);
    }

    ResourceLocation emissiveTexture(GltfMaterial material) { return textures.emissive(material); }

    void releaseCold(long frame) {
        var entries = geometry.values().iterator();
        while (entries.hasNext()) {
            Geometry entry = entries.next();
            if (frame - entry.lastFrame <= COLD_FRAMES) continue;
            entries.remove();
            geometryBytes -= entry.bytes;
            GltfGpuCache.geometryAllocated(-entry.bytes);
            if (entry.buffer != null) entry.buffer.close();
        }
        textures.releaseCold(frame);
    }

    void delete() {
        RenderSystem.assertOnRenderThread();
        for (Geometry entry : geometry.values()) {
            if (entry.buffer != null) entry.buffer.close();
        }
        geometry.clear();
        GltfGpuCache.geometryAllocated(-geometryBytes);
        geometryBytes = 0;
        textures.close();
    }

    static final class ScenePlan {
        private static final int INDEX_THRESHOLD = 64;
        final NodePlan[] nodes;
        private volatile GltfSceneIndex spatialIndex;

        ScenePlan(NodePlan[] nodes) { this.nodes = nodes; }

        GltfSceneIndex index(GltfAsset asset) {
            if (nodes.length < INDEX_THRESHOLD) return null;
            GltfSceneIndex index = spatialIndex;
            if (index != null) return index;
            synchronized (this) {
                if (spatialIndex == null) {
                    spatialIndex = new GltfSceneIndex(nodes, GltfAssetAccess.bindWorldMatrices(asset));
                }
                return spatialIndex;
            }
        }
    }

    private static final class PreparedAsset {
        final Map<Integer, ScenePlan> scenes = new ConcurrentHashMap<>();
        final Map<GltfPrimitive, GltfPrimitiveClusters.Section[]> clusters = new ConcurrentHashMap<>();
        final GltfMaterialBaker materials;

        PreparedAsset(List<org.lytharalab.gfbs.gltf.api.model.GltfTexture> textures) {
            materials = new GltfMaterialBaker(textures);
        }

        GltfPrimitiveClusters.Section[] sections(GltfPrimitive primitive) {
            return GltfPrimitiveClusters.eligible(primitive)
                ? clusters.computeIfAbsent(primitive, GltfPrimitiveClusters::build) : GltfPrimitiveClusters.whole();
        }
    }

    static final class NodePlan {
        final int nodeIndex;
        final GltfNode node;
        final PrimitivePlan[] primitives;
        int subtreeEndExclusive;

        NodePlan(int nodeIndex, GltfNode node, PrimitivePlan[] primitives) {
            this.nodeIndex = nodeIndex;
            this.node = node;
            this.primitives = primitives;
        }
    }

    record PrimitivePlan(int nodeIndex, String nodeName, int meshIndex, int primitiveIndex,
                         GltfMesh mesh, GltfPrimitive primitive, GltfPrimitiveKey key) {
    }

    private record GeometryKey(GltfPrimitive primitive, GltfPrimitiveClusters.Section section,
                               GltfGeometryPipeline.PassKind pass,
                               GltfTextureInfo uv, float red, float green, float blue,
                               float alpha, AlphaMode alphaMode) {}

    private static final class Geometry {
        final GltfGpuPrimitive buffer;
        final long bytes;
        long lastFrame = GltfGpuCache.frame();

        Geometry(GltfGpuPrimitive buffer, long bytes) {
            this.buffer = buffer;
            this.bytes = bytes;
        }
    }
}
