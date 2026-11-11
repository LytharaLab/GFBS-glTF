package org.lytharalab.gfbs.gltf.api.client.material;

/** Quality and memory budget for reprojecting independent texture coordinates into LabPBR pages. */
public record GltfPbrBakeOptions(int pageSize, int chartSize, long maxPixels) {
    public static final GltfPbrBakeOptions DEFAULT =
        new GltfPbrBakeOptions(2048, 512, 32L * 1024 * 1024);

    public GltfPbrBakeOptions {
        if (pageSize < 16 || pageSize > 8192 || Integer.bitCount(pageSize) != 1) {
            throw new IllegalArgumentException("PBR page size must be a power of two from 16 to 8192");
        }
        if (chartSize < 1 || chartSize > pageSize / 2 || maxPixels < 1) {
            throw new IllegalArgumentException("Invalid PBR chart size or pixel budget");
        }
    }
}
