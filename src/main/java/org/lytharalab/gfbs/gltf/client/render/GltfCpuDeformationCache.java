package org.lytharalab.gfbs.gltf.client.render;

import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.client.GltfInstance;
import org.lytharalab.gfbs.gltf.api.client.node.GltfNodeManager.SkinPalette;
import org.lytharalab.gfbs.gltf.api.client.node.GltfPrimitiveKey;
import org.lytharalab.gfbs.gltf.api.client.node.GltfPrimitiveState;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitiveAccess;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded render-thread cache for local-space morphs and CPU-skinned vertices. */
final class GltfCpuDeformationCache {
    private static final long BUDGET = 128L * 1024 * 1024;
    private static final int MAX_ENTRIES = 4_096;
    private static final int COLD_FRAMES = 180;
    private static final Map<Key, Entry> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static final float[] SKIN_SCRATCH = new float[6];
    private static final float[] FACE_SCRATCH = new float[3];
    private static long bytes;

    private GltfCpuDeformationCache() {}

    static Prepared resolve(GltfInstance instance, GltfPrimitiveState state, float[] morphWeights, SkinPalette palette) {
        if (morphWeights == null && palette == null) return null;
        GltfRenderMetrics metrics = GltfRenderMetrics.active();
        GltfPrimitive primitive = state.definition();
        Key key = new Key(instance.id(), state.key());
        Entry entry = CACHE.get(key);
        if (entry == null) {
            long size = Entry.estimateBytes(primitive, palette != null);
            if (size > BUDGET) {
                if (metrics != null) metrics.cpuDeformationBypasses++;
                return null;
            }
            var entries = CACHE.values().iterator();
            while (CACHE.size() >= MAX_ENTRIES || bytes + size > BUDGET) {
                bytes -= entries.next().bytes;
                entries.remove();
            }
            entry = new Entry(instance.asset().id(), primitive, size, palette != null);
            CACHE.put(key, entry);
            bytes += size;
        }
        entry.lastFrame = GltfGpuCache.frame();
        return entry.resolve(primitive, morphWeights, palette, metrics);
    }

    static long bytes() { return bytes; }

    static void releaseCold(long frame) {
        var entries = CACHE.values().iterator();
        while (entries.hasNext()) {
            Entry entry = entries.next();
            if (frame - entry.lastFrame <= COLD_FRAMES) break;
            bytes -= entry.bytes;
            entries.remove();
        }
    }

    static void remove(ResourceLocation asset) {
        var entries = CACHE.values().iterator();
        while (entries.hasNext()) {
            Entry entry = entries.next();
            if (!entry.asset.equals(asset)) continue;
            bytes -= entry.bytes;
            entries.remove();
        }
    }

    static void clear() {
        CACHE.clear();
        bytes = 0;
    }

    /** Expanded data has one position and normal per original triangle corner. */
    record Prepared(float[] positions, float[] normals, boolean expanded) {}

    private record Key(UUID instance, GltfPrimitiveKey primitive) {}

    private static final class Entry {
        final ResourceLocation asset;
        final long bytes;
        final Prepared skinned;
        Prepared morphed;
        float[] previousWeights;
        long morphRevision;
        long lastFrame;
        long previousPaletteRevision = Long.MIN_VALUE;
        long previousMorphRevision;

        Entry(ResourceLocation asset, GltfPrimitive primitive, long bytes, boolean skin) {
            this.asset = asset;
            this.bytes = bytes;
            boolean flat = GltfPrimitiveAccess.normals(primitive) == null
                && GltfGeometryPipeline.isTriangleMode(primitive.mode());
            int components = skin ? Math.toIntExact(skinComponents(primitive, flat)) : 0;
            skinned = skin ? new Prepared(new float[components], new float[components], flat) : null;
        }

        static long estimateBytes(GltfPrimitive primitive, boolean skin) {
            boolean normals = GltfPrimitiveAccess.normals(primitive) != null;
            long morphFloats = primitive.morphTargets().isEmpty() ? 0
                : (long) primitive.vertexCount() * (normals ? 6 : 3) + primitive.morphTargets().size();
            long skinFloats = skin ? 2 * skinComponents(
                primitive, !normals && GltfGeometryPipeline.isTriangleMode(primitive.mode())) : 0;
            return (morphFloats + skinFloats) * Float.BYTES;
        }

        static long skinComponents(GltfPrimitive primitive, boolean flat) {
            return flat ? (long) GltfMaterialBaker.triangleCount(primitive) * 9 : (long) primitive.vertexCount() * 3;
        }

        Prepared resolve(GltfPrimitive primitive, float[] weights, SkinPalette palette, GltfRenderMetrics metrics) {
            float[] positions = GltfPrimitiveAccess.positions(primitive);
            float[] normals = GltfPrimitiveAccess.normals(primitive);
            if (weights != null) {
                if (morphed == null) {
                    morphed = new Prepared(new float[positions.length], normals == null ? null : new float[normals.length], false);
                    previousWeights = new float[primitive.morphTargets().size()];
                }
                if (morphRevision == 0 || !sameWeights(weights)) {
                    System.arraycopy(positions, 0, morphed.positions, 0, positions.length);
                    if (normals != null) System.arraycopy(normals, 0, morphed.normals, 0, normals.length);
                    GltfVertexTransforms.applyMorphs(primitive, weights, morphed.positions, morphed.normals, null);
                    int count = Math.min(weights.length, previousWeights.length);
                    System.arraycopy(weights, 0, previousWeights, 0, count);
                    Arrays.fill(previousWeights, count, previousWeights.length, 0.0f);
                    morphRevision++;
                    if (metrics != null) metrics.morphBuilds++;
                } else if (metrics != null) {
                    metrics.morphCacheHits++;
                }
                positions = morphed.positions;
                normals = morphed.normals;
            }
            if (palette == null) return morphed;
            long activeMorphRevision = weights == null ? 0 : morphRevision;
            if (previousPaletteRevision == palette.revision() && previousMorphRevision == activeMorphRevision) {
                if (metrics != null) metrics.cpuDeformationCacheHits++;
                return skinned;
            }
            writeSkin(primitive, positions, normals, palette.matrices());
            previousPaletteRevision = palette.revision();
            previousMorphRevision = activeMorphRevision;
            if (metrics != null) metrics.cpuDeformationBuilds++;
            return skinned;
        }

        private boolean sameWeights(float[] weights) {
            for (int i = 0; i < previousWeights.length; i++) {
                float weight = i < weights.length ? weights[i] : 0.0f;
                if (Float.floatToIntBits(weight) != Float.floatToIntBits(previousWeights[i])) return false;
            }
            return true;
        }

        private void writeSkin(GltfPrimitive primitive, float[] positions, float[] normals, float[] palette) {
            int[] joints = GltfPrimitiveAccess.joints(primitive);
            float[] weights = GltfPrimitiveAccess.weights(primitive);
            int jointCount = palette.length / 16;
            float[] output = SKIN_SCRATCH;
            float[] face = skinned.expanded ? FACE_SCRATCH : null;
            int count = skinned.positions.length / 3;
            for (int i = 0; i < count; i++) {
                int vertex = i;
                if (skinned.expanded) {
                    int triangle = i / 3;
                    if (i % 3 == 0) {
                        GltfVertexTransforms.faceNormal(positions,
                            GltfMaterialBaker.vertex(primitive, triangle, 0), GltfMaterialBaker.vertex(primitive, triangle, 1),
                            GltfMaterialBaker.vertex(primitive, triangle, 2), face);
                    }
                    vertex = GltfMaterialBaker.vertex(primitive, triangle, i % 3);
                }
                int src = vertex * 3;
                float nx = normals != null ? normals[src] : face != null ? face[0] : 0.0f;
                float ny = normals != null ? normals[src + 1] : face != null ? face[1] : 1.0f;
                float nz = normals != null ? normals[src + 2] : face != null ? face[2] : 0.0f;
                GltfVertexTransforms.skinVertex(vertex, positions[src], positions[src + 1], positions[src + 2],
                    nx, ny, nz, joints, weights, palette, jointCount, output);
                System.arraycopy(output, 0, skinned.positions, i * 3, 3);
                System.arraycopy(output, 3, skinned.normals, i * 3, 3);
            }
        }
    }
}
