package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import org.lytharalab.gfbs.gltf.api.client.material.GltfPbrBakeOptions;
import org.lytharalab.gfbs.gltf.api.model.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lytharalab.gfbs.gltf.client.render.LabPbrEncoder.*;

/**
 * CPU-only material conversion shared by entity textures and static atlas resources.
 * Independent UV sets receive triangle charts with the normal-map UV orientation preserved.
 */
public final class GltfMaterialBaker {
    private static final long CACHE_BYTES = 128L * 1024 * 1024;
    private final List<GltfTexture> textures;
    private final Map<Key, Material> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cachedBytes;

    public GltfMaterialBaker(List<GltfTexture> textures) { this.textures = List.copyOf(textures); }

    public Material bake(GltfPrimitive primitive, GltfMaterial material,
                         GltfPbrBakeOptions options, boolean staticAtlas, boolean labEmission) {
        return bake(primitive, material, options, staticAtlas, labEmission, false);
    }

    public synchronized Material bake(GltfPrimitive primitive, GltfMaterial material,
                                      GltfPbrBakeOptions options, boolean staticAtlas,
                                      boolean labEmission, boolean flipV) {
        Key key = new Key(primitive, material, options, staticAtlas, labEmission, flipV);
        Material existing = cache.get(key);
        if (existing != null) return existing;
        try (GltfImageSampler.Sources sources = new GltfImageSampler.Sources(textures, flipV)) {
            Pixels pixels = new Pixels(sources, material, labEmission);
            List<Page> pages = requiresCharts(primitive, material, staticAtlas)
                ? chartPages(primitive, material, options, pixels)
                : List.of(singlePage(primitive, material, options, pixels));
            Material result = new Material(pages, pixels.emissionInSpecular);
            long bytes = pages.stream().mapToLong(Page::encodedBytes).sum();
            while (!cache.isEmpty() && (cachedBytes + bytes > CACHE_BYTES || cache.size() >= 512)) {
                Key oldest = cache.keySet().iterator().next();
                Material removed = cache.remove(oldest);
                cachedBytes -= removed.pages().stream().mapToLong(Page::encodedBytes).sum();
            }
            if (bytes <= CACHE_BYTES) {
                cache.put(key, result);
                cachedBytes += bytes;
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not bake glTF material " + material.name(), exception);
        }
    }

    public boolean requiresCharts(GltfPrimitive primitive, GltfMaterial material, boolean staticAtlas) {
        if (!GltfGeometryPipeline.isTriangleMode(primitive.mode())) return false;
        GltfTextureInfo anchor = anchor(material);
        for (GltfTextureInfo info : List.of(material.baseColorTextureInfo(), material.normalTextureInfo(),
            material.occlusionTextureInfo(), material.metallicRoughnessTextureInfo())) {
            if (!info.present()) continue;
            if (!sameUv(anchor, info)) return true;
            GltfTexture a = texture(anchor.texture()), b = texture(info.texture());
            if (a != null && (a.wrapS() != b.wrapS() || a.wrapT() != b.wrapT())) return true;
        }
        if (material.labPbr().normalTexture() < 0 && material.normalTexture() >= 0
            && (anchor.rotation() != 0 || anchor.scaleU() < 0 || anchor.scaleV() < 0)) return true;
        if (staticAtlas) {
            float[] uv = anchor.texCoord() == 0 ? GltfPrimitiveAccess.texCoords0(primitive)
                : GltfPrimitiveAccess.texCoords1(primitive);
            if (uv != null) {
                float[] point = new float[2];
                for (int i = 0; i < uv.length; i += 2) {
                    anchor.transform(uv[i], uv[i + 1], point);
                    if (point[0] < 0 || point[0] > 1 || point[1] < 0 || point[1] > 1) return true;
                }
            }
        }
        return false;
    }

    public static boolean emissionInSpecular(GltfMaterial material, boolean enabled) {
        return enabled && (material.labPbr().specularTexture() >= 0 || material.labPbr().emission() >= 0
            || emissionRatio(material) >= 0);
    }

    private static float emissionRatio(GltfMaterial material) {
        if (!GltfGeometryPipeline.hasVisibleEmission(material)) return -1;
        if (!material.baseColorTextureInfo().equals(material.emissiveTextureInfo())) return -1;
        float r = material.emissiveRed() * material.emissiveStrength();
        float g = material.emissiveGreen() * material.emissiveStrength();
        float b = material.emissiveBlue() * material.emissiveStrength();
        float base = Math.max(material.baseColorRed(), Math.max(material.baseColorGreen(), material.baseColorBlue()));
        if (base <= 0) return -1;
        float ratio = Math.max(r, Math.max(g, b)) / base;
        if (ratio > 1 || Math.abs(r - ratio * material.baseColorRed()) > 0.0001f
            || Math.abs(g - ratio * material.baseColorGreen()) > 0.0001f
            || Math.abs(b - ratio * material.baseColorBlue()) > 0.0001f) return -1;
        return ratio;
    }

    public static GltfTextureInfo anchor(GltfMaterial material) {
        if (material.baseColorTexture() >= 0 || material.labPbr().normalTexture() >= 0
            || material.labPbr().specularTexture() >= 0) return material.baseColorTextureInfo();
        if (material.normalTexture() >= 0) return material.normalTextureInfo();
        if (material.metallicRoughnessTexture() >= 0) return material.metallicRoughnessTextureInfo();
        if (material.occlusionTexture() >= 0) return material.occlusionTextureInfo();
        return material.baseColorTextureInfo();
    }

    private static boolean sameUv(GltfTextureInfo a, GltfTextureInfo b) {
        return a.texCoord() == b.texCoord() && a.offsetU() == b.offsetU() && a.offsetV() == b.offsetV()
            && a.scaleU() == b.scaleU() && a.scaleV() == b.scaleV() && a.rotation() == b.rotation();
    }

    private GltfTexture texture(int index) { return index < 0 ? null : textures.get(index); }

    private static Page singlePage(GltfPrimitive primitive, GltfMaterial material,
                                   GltfPbrBakeOptions options, Pixels pixels) throws IOException {
        GltfTextureInfo anchor = anchor(material);
        int width = pixels.width(), height = pixels.height();
        if ((long) width * height > options.maxPixels()) {
            double scale = Math.sqrt((double) options.maxPixels() / ((long) width * height));
            width = (int) Math.min(options.maxPixels(), Math.max(1, (int) (width * scale)));
            height = (int) Math.min(options.maxPixels() / width, Math.max(1, (int) (height * scale)));
        }
        try (Images images = new Images(width, height)) {
            double c = Math.cos(anchor.rotation()), s = Math.sin(anchor.rotation());
            int[] value = new int[3];
            for (int y = 0; y < height; y++) {
                double v = (y + 0.5) / height - anchor.offsetV();
                for (int x = 0; x < width; x++) {
                    double u = (x + 0.5) / width - anchor.offsetU();
                    double rawU = anchor.scaleU() == 0 ? 0 : (c * u + s * v) / anchor.scaleU();
                    double rawV = anchor.scaleV() == 0 ? 0 : (-s * u + c * v) / anchor.scaleV();
                    pixels.sample(rawU, rawV, rawU, rawV, value);
                    images.set(x, y, value);
                }
            }
            return new Page(0, 0, triangleCount(primitive), width, height, null, anchor,
                primitive.bounds(), images.encoded(), 30);
        }
    }

    private static List<Page> chartPages(GltfPrimitive primitive, GltfMaterial material,
                                         GltfPbrBakeOptions options, Pixels pixels) throws IOException {
        int count = triangleCount(primitive);
        Chart[] charts = new Chart[count];
        GltfTextureInfo normalUv = material.labPbr().normalTexture() < 0 && material.normalTexture() >= 0
            ? new GltfTextureInfo(-1, material.normalTexCoord()) : anchor(material);
        double required = 0;
        for (int triangle = 0; triangle < count; triangle++) {
            Chart chart = new Chart(primitive, triangle, normalUv, pixels, options.chartSize());
            charts[triangle] = chart;
            required += chart.pixelCount();
        }
        if (required > options.maxPixels()) {
            double scale = Math.sqrt(options.maxPixels() / required);
            required = 0;
            for (Chart chart : charts) {
                chart.resize(Math.max(1, (int) (chart.innerWidth * scale)),
                    Math.max(1, (int) (chart.innerHeight * scale)));
                required += chart.pixelCount();
            }
            if (required > options.maxPixels()) {
                throw new IllegalArgumentException("PBR charts need " + (long) required
                    + " texels after minimum sizing; increase GltfPbrBakeOptions.maxPixels");
            }
        }
        List<Page> pages = new ArrayList<>();
        int start = 0, x = 0, y = 0, row = 0, usedWidth = 0;
        for (int i = 0; i < charts.length; i++) {
            Chart chart = charts[i];
            if (x + chart.width() > options.pageSize()) { x = 0; y += row; row = 0; }
            if (y + chart.height() > options.pageSize()) {
                pages.add(bakePage(pages.size(), primitive, charts, start, i, usedWidth, y + row, pixels));
                start = i; x = 0; y = 0; row = 0; usedWidth = 0;
            }
            chart.x = x; chart.y = y;
            x += chart.width();
            row = Math.max(row, chart.height());
            usedWidth = Math.max(usedWidth, x);
        }
        if (start < charts.length) {
            pages.add(bakePage(pages.size(), primitive, charts, start, charts.length, usedWidth, y + row, pixels));
        }
        return List.copyOf(pages);
    }

    private static Page bakePage(int index, GltfPrimitive primitive, Chart[] all, int start, int end,
                                  int width, int height, Pixels pixels) throws IOException {
        Chart[] charts = Arrays.copyOfRange(all, start, end);
        GltfBounds bounds = GltfBounds.EMPTY;
        int maxMip = 30;
        float[] uv0 = GltfPrimitiveAccess.texCoords0(primitive), uv1 = GltfPrimitiveAccess.texCoords1(primitive);
        int[] values = new int[3];
        try (Images images = new Images(width, height)) {
            images.fill(new int[]{0xffffffff, rgba(128, 128, 255, 255), rgba(0, 10, 0, 255)});
            for (int i = 0; i < charts.length; i++) {
                Chart chart = charts[i];
                int triangle = start + i;
                int a = vertex(primitive, triangle, 0), b = vertex(primitive, triangle, 1), c = vertex(primitive, triangle, 2);
                double[] raw0 = triangleUv(uv0, a, b, c), raw1 = triangleUv(uv1, a, b, c);
                double determinant = (chart.bx - chart.ax) * (chart.cy - chart.ay)
                    - (chart.by - chart.ay) * (chart.cx - chart.ax);
                for (int py = 0; py < chart.height(); py++) {
                    double sy = (py - chart.padding + 0.5) / chart.innerHeight;
                    for (int px = 0; px < chart.width(); px++) {
                        double sx = (px - chart.padding + 0.5) / chart.innerWidth;
                        double wb = ((sx - chart.ax) * (chart.cy - chart.ay)
                            - (sy - chart.ay) * (chart.cx - chart.ax)) / determinant;
                        double wc = ((chart.bx - chart.ax) * (sy - chart.ay)
                            - (chart.by - chart.ay) * (sx - chart.ax)) / determinant;
                        double wa = Math.max(0, 1 - wb - wc);
                        wb = Math.max(0, wb); wc = Math.max(0, wc);
                        double total = wa + wb + wc;
                        wa /= total; wb /= total; wc /= total;
                        pixels.sample(blend(raw0, 0, wa, wb, wc), blend(raw0, 1, wa, wb, wc),
                            blend(raw1, 0, wa, wb, wc), blend(raw1, 1, wa, wb, wc), values);
                        images.set(chart.x + px, chart.y + py, values);
                    }
                }
                maxMip = Math.min(maxMip, 31 - Integer.numberOfLeadingZeros(chart.padding));
                bounds = bounds.union(triangleBounds(primitive, a, b, c));
            }
            return new Page(index, start, end - start, width, height, charts, GltfTextureInfo.absent(),
                bounds, images.encoded(), maxMip);
        }
    }

    private static double blend(double[] uv, int component, double a, double b, double c) {
        return uv[component] * a + uv[2 + component] * b + uv[4 + component] * c;
    }

    private static double[] triangleUv(float[] uv, int a, int b, int c) {
        return uv == null ? new double[6] : new double[]{
            uv[a * 2], uv[a * 2 + 1], uv[b * 2], uv[b * 2 + 1], uv[c * 2], uv[c * 2 + 1]};
    }

    private static GltfBounds triangleBounds(GltfPrimitive primitive, int a, int b, int c) {
        float[] p = GltfPrimitiveAccess.positions(primitive);
        return new GltfBounds(Math.min(p[a * 3], Math.min(p[b * 3], p[c * 3])),
            Math.min(p[a * 3 + 1], Math.min(p[b * 3 + 1], p[c * 3 + 1])),
            Math.min(p[a * 3 + 2], Math.min(p[b * 3 + 2], p[c * 3 + 2])),
            Math.max(p[a * 3], Math.max(p[b * 3], p[c * 3])),
            Math.max(p[a * 3 + 1], Math.max(p[b * 3 + 1], p[c * 3 + 1])),
            Math.max(p[a * 3 + 2], Math.max(p[b * 3 + 2], p[c * 3 + 2])));
    }

    public static int triangleCount(GltfPrimitive primitive) {
        return switch (primitive.mode()) {
            case TRIANGLES -> primitive.indexCount() / 3;
            case TRIANGLE_STRIP, TRIANGLE_FAN -> Math.max(0, primitive.indexCount() - 2);
            default -> 0;
        };
    }

    public static int vertex(GltfPrimitive primitive, int triangle, int corner) {
        int element = switch (primitive.mode()) {
            case TRIANGLES -> triangle * 3 + corner;
            case TRIANGLE_STRIP -> triangle + ((triangle & 1) != 0 && corner < 2 ? 1 - corner : corner);
            case TRIANGLE_FAN -> corner == 0 ? 0 : triangle + corner;
            default -> throw new IllegalArgumentException("Triangle geometry required");
        };
        int[] indices = GltfPrimitiveAccess.indices(primitive);
        return indices == null ? element : indices[element];
    }

    private record Key(GltfPrimitive primitive, GltfMaterial material, GltfPbrBakeOptions options,
                       boolean staticAtlas, boolean labEmission, boolean flipV) {}

    public record Material(List<Page> pages, boolean emissionInSpecular) {
        public Material { pages = List.copyOf(pages); }
    }

    /** Internal read-only page data. Chart coordinates are shared by all companion textures. */
    public static final class Page {
        public final int index, firstTriangle, triangleCount, width, height, maxMip;
        public final GltfTextureInfo textureInfo;
        public final GltfBounds bounds;
        public final byte[][] images;
        private final Chart[] charts;

        private Page(int index, int first, int count, int width, int height, Chart[] charts,
                      GltfTextureInfo textureInfo, GltfBounds bounds, byte[][] images, int maxMip) {
            this.index = index; this.firstTriangle = first; this.triangleCount = count;
            this.width = width; this.height = height; this.charts = charts; this.textureInfo = textureInfo;
            this.bounds = bounds; this.images = images; this.maxMip = maxMip;
        }

        public boolean remapped() { return charts != null; }
        public boolean contains(int triangle) {
            return triangle >= firstTriangle && triangle < firstTriangle + triangleCount;
        }

        public float u(int triangle, int corner) {
            Chart chart = charts[triangle - firstTriangle];
            double u = corner == 0 ? chart.ax : corner == 1 ? chart.bx : chart.cx;
            return (float) ((chart.x + chart.padding + u * chart.innerWidth) / width);
        }

        public float v(int triangle, int corner) {
            Chart chart = charts[triangle - firstTriangle];
            double v = corner == 0 ? chart.ay : corner == 1 ? chart.by : chart.cy;
            return (float) ((chart.y + chart.padding + v * chart.innerHeight) / height);
        }

        long encodedBytes() {
            long bytes = 0;
            for (byte[] image : images) bytes += image.length;
            return bytes;
        }
    }

    private static final class Chart {
        double ax, ay, bx, by, cx, cy;
        int x, y, innerWidth, innerHeight, padding;

        Chart(GltfPrimitive primitive, int triangle, GltfTextureInfo reference, Pixels pixels, int maximum) {
            int a = vertex(primitive, triangle, 0), b = vertex(primitive, triangle, 1), c = vertex(primitive, triangle, 2);
            float[] raw = reference.texCoord() == 0 ? GltfPrimitiveAccess.texCoords0(primitive)
                : GltfPrimitiveAccess.texCoords1(primitive);
            double[] uv = triangleUv(raw, a, b, c);
            double cosine = Math.cos(reference.rotation()), sine = Math.sin(reference.rotation());
            for (int i = 0; i < 6; i += 2) {
                double u = uv[i] * reference.scaleU(), v = uv[i + 1] * reference.scaleV();
                uv[i] = reference.offsetU() + cosine * u - sine * v;
                uv[i + 1] = reference.offsetV() + sine * u + cosine * v;
            }
            double minU = Math.min(uv[0], Math.min(uv[2], uv[4])), minV = Math.min(uv[1], Math.min(uv[3], uv[5]));
            double spanU = Math.max(uv[0], Math.max(uv[2], uv[4])) - minU;
            double spanV = Math.max(uv[1], Math.max(uv[3], uv[5])) - minV;
            double determinant = (uv[2] - uv[0]) * (uv[5] - uv[1]) - (uv[3] - uv[1]) * (uv[4] - uv[0]);
            if (Math.abs(determinant) < 1.0e-20) {
                ax = ay = by = cx = 0; bx = cy = 1; spanU = spanV = 1;
            } else {
                ax = (uv[0] - minU) / spanU; ay = (uv[1] - minV) / spanV;
                bx = (uv[2] - minU) / spanU; by = (uv[3] - minV) / spanV;
                cx = (uv[4] - minU) / spanU; cy = (uv[5] - minV) / spanV;
            }
            double footprint = pixels.footprint(primitive, a, b, c);
            double span = Math.max(spanU, spanV);
            resize(Math.max(1, Math.min(maximum, (int) Math.ceil(footprint * spanU / span))),
                Math.max(1, Math.min(maximum, (int) Math.ceil(footprint * spanV / span))));
        }

        void resize(int width, int height) {
            innerWidth = width; innerHeight = height;
            padding = Math.max(1, Math.min(8, Math.min(width, height) / 4));
        }
        int width() { return innerWidth + padding * 2; }
        int height() { return innerHeight + padding * 2; }
        long pixelCount() { return (long) width() * height(); }
    }

    private static final class Images implements AutoCloseable {
        final NativeImage base, normal, specular;
        Images(int width, int height) {
            base = new NativeImage(width, height, false);
            normal = new NativeImage(width, height, false);
            specular = new NativeImage(width, height, false);
        }
        void set(int x, int y, int[] values) {
            base.setPixelRGBA(x, y, values[0]); normal.setPixelRGBA(x, y, values[1]); specular.setPixelRGBA(x, y, values[2]);
        }
        void fill(int[] values) {
            for (int y = 0; y < base.getHeight(); y++) {
                for (int x = 0; x < base.getWidth(); x++) set(x, y, values);
            }
        }
        byte[][] encoded() throws IOException {
            return new byte[][]{base.asByteArray(), normal.asByteArray(), specular.asByteArray()};
        }
        @Override public void close() { base.close(); normal.close(); specular.close(); }
    }

    private static final class Pixels {
        final GltfMaterial material;
        final GltfLabPbr lab;
        final GltfImageSampler base, normal, specular, metallic, occlusion;
        final List<GltfImageSampler> samplers = new ArrayList<>();
        final boolean emissionInSpecular;
        final int automaticEmission;

        Pixels(GltfImageSampler.Sources sources, GltfMaterial material, boolean emission) throws IOException {
            this.material = material; lab = material.labPbr();
            base = add(sources.get(material.baseColorTextureInfo()));
            normal = add(sources.get(lab.normalTexture() >= 0
                ? nativeInfo(material.baseColorTextureInfo(), lab.normalTexture()) : material.normalTextureInfo()));
            specular = add(sources.get(nativeInfo(material.baseColorTextureInfo(), lab.specularTexture())));
            metallic = add(sources.get(material.metallicRoughnessTextureInfo()));
            occlusion = add(sources.get(material.occlusionTextureInfo()));
            emissionInSpecular = emissionInSpecular(material, emission);
            float ratio = emission ? emissionRatio(material) : -1;
            automaticEmission = ratio < 0 ? 255 : Math.round(ratio * 254);
        }

        private static GltfTextureInfo nativeInfo(GltfTextureInfo uv, int texture) {
            return new GltfTextureInfo(texture, uv.texCoord(), uv.offsetU(), uv.offsetV(), uv.scaleU(), uv.scaleV(), uv.rotation());
        }

        private GltfImageSampler add(GltfImageSampler sampler) {
            if (sampler != null) samplers.add(sampler);
            return sampler;
        }

        int width() { return samplers.stream().mapToInt(GltfImageSampler::width).max().orElse(1); }
        int height() { return samplers.stream().mapToInt(GltfImageSampler::height).max().orElse(1); }

        double footprint(GltfPrimitive primitive, int a, int b, int c) {
            double[] uv0 = triangleUv(GltfPrimitiveAccess.texCoords0(primitive), a, b, c);
            double[] uv1 = triangleUv(GltfPrimitiveAccess.texCoords1(primitive), a, b, c);
            double maximum = 2;
            for (GltfImageSampler sampler : samplers) {
                double firstU = sampler.u(uv0[0], uv0[1], uv1[0], uv1[1]);
                double firstV = sampler.v(uv0[0], uv0[1], uv1[0], uv1[1]);
                for (int corner = 2; corner <= 4; corner += 2) {
                    double u = sampler.u(uv0[corner], uv0[corner + 1], uv1[corner], uv1[corner + 1]);
                    double v = sampler.v(uv0[corner], uv0[corner + 1], uv1[corner], uv1[corner + 1]);
                    maximum = Math.max(maximum, Math.hypot((u - firstU) * sampler.width(), (v - firstV) * sampler.height()));
                }
            }
            return maximum;
        }

        void sample(double u0, double v0, double u1, double v1, int[] out) {
            int albedo = base == null ? -1 : base.sample(u0, v0, u1, v1, false);
            int alpha = albedo >>> 24;
            if (material.alphaMode() == AlphaMode.OPAQUE) alpha = 255;
            else if (material.alphaMode() == AlphaMode.MASK) {
                alpha = alpha / 255.0f * material.baseColorAlpha() >= material.alphaCutoff() ? 255 : 0;
            }
            out[0] = albedo & 0xffffff | alpha << 24;
            int normalPixel;
            if (lab.normalTexture() >= 0) normalPixel = normal.sample(u0, v0, u1, v1, false);
            else {
                int ao = occlusion == null ? 255 : channel(1 - material.occlusionStrength()
                    * (1 - red(occlusion.sample(u0, v0, u1, v1, false)) / 255.0f));
                normalPixel = normal == null ? rgba(128, 128, ao, 255)
                    : LabPbrEncoder.normal(normal.sample(u0, v0, u1, v1, false), ao, material.normalScale(), 255);
            }
            out[1] = rgba(inherit(lab.normalX(), red(normalPixel)), inherit(lab.normalY(), green(normalPixel)),
                inherit(lab.ambientOcclusion(), blue(normalPixel)), inherit(lab.height(), normalPixel >>> 24));
            int specularPixel = specular == null
                ? LabPbrEncoder.specular(metallic == null ? -1 : metallic.sample(u0, v0, u1, v1, false),
                    material, automaticEmission)
                : specular.sample(u0, v0, u1, v1, true);
            out[2] = rgba(inherit(lab.smoothness(), red(specularPixel)), inherit(lab.reflectance(), green(specularPixel)),
                inherit(lab.porosityOrSubsurface(), blue(specularPixel)), inherit(lab.emission(), specularPixel >>> 24));
        }
        private static int inherit(int value, int original) { return value < 0 ? original : value; }
    }
}
