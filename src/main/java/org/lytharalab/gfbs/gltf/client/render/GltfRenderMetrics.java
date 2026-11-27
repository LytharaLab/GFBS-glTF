package org.lytharalab.gfbs.gltf.client.render;

import org.lytharalab.gfbs.gltf.api.client.GltfRenderDiagnostics;
import org.lytharalab.gfbs.gltf.api.client.node.GltfNodeManager;
import org.lytharalab.gfbs.gltf.api.client.node.GltfNodeManager.SkinPalette;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;

/** Mutable render-thread counters. Disabled collection leaves the hot paths untimed. */
public final class GltfRenderMetrics {
    private static GltfRenderMetrics counters = new GltfRenderMetrics();
    private static boolean enabled;

    private GltfRenderMetrics() {}

    long renderCalls, cpuSubmissionNanos;
    long cpuSkinnedDraws, gpuSkinnedDraws;
    long gpuGeometryUploads, gpuGeometryCacheHits;
    long paletteMatrixUpdates, paletteUploads, paletteUploadBytes;
    long cpuDeformationBuilds, cpuDeformationCacheHits, cpuDeformationBypasses;
    long morphBuilds, morphCacheHits;
    long gpuDeformationBuilds, gpuDeformationReuses;

    static GltfRenderMetrics active() { return enabled ? counters : null; }

    public static void setEnabled(boolean enabled) { GltfRenderMetrics.enabled = enabled; }
    public static void reset() { counters = new GltfRenderMetrics(); }

    public static GltfRenderDiagnostics snapshot() {
        GltfRenderMetrics c = counters;
        return new GltfRenderDiagnostics(c.renderCalls, c.cpuSubmissionNanos,
            c.cpuSkinnedDraws, c.gpuSkinnedDraws, c.gpuGeometryUploads, c.gpuGeometryCacheHits,
            c.paletteMatrixUpdates, c.paletteUploads, c.paletteUploadBytes,
            c.cpuDeformationBuilds, c.cpuDeformationCacheHits, c.cpuDeformationBypasses,
            c.morphBuilds, c.morphCacheHits, c.gpuDeformationBuilds, c.gpuDeformationReuses,
            GltfGpuCache.residentGeometryBytes(), GltfGpuDeformationPool.bytes(), GltfCpuDeformationCache.bytes());
    }

    static SkinPalette palette(GltfNodeManager nodes, GltfSkin skin, int meshNode, float[] world, int[] jointSlots) {
        GltfRenderMetrics metrics = active();
        long before = metrics == null ? 0 : nodes.skinMatrixComputations();
        SkinPalette palette = nodes.skinPaletteView(skin, meshNode, world, jointSlots);
        if (metrics != null) metrics.paletteMatrixUpdates += nodes.skinMatrixComputations() - before;
        return palette;
    }
}
