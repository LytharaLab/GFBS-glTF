package org.lytharalab.gfbs.gltf.api.client;

/**
 * Render-thread diagnostic snapshot. Counters accumulate while collection is enabled. Byte
 * totals for resident geometry and CPU deformation reflect the caches at snapshot time.
 */
public record GltfRenderDiagnostics(
    long renderCalls, long cpuSubmissionNanos,
    long cpuSkinnedDraws, long gpuSkinnedDraws,
    long gpuGeometryUploads, long gpuGeometryCacheHits,
    long paletteMatrixUpdates, long paletteUploads, long paletteUploadBytes,
    long cpuDeformationBuilds, long cpuDeformationCacheHits, long cpuDeformationBypasses,
    long morphBuilds, long morphCacheHits,
    long gpuDeformationBuilds, long gpuDeformationReuses,
    long residentGpuGeometryBytes, long residentGpuDeformationBytes, long cachedCpuDeformationBytes
) {}
