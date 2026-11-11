package org.lytharalab.gfbs.gltf.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.event.AddPackFindersEvent;
import org.lytharalab.gfbs.gltf.GFBSglTF;
import org.lytharalab.gfbs.gltf.api.client.material.GltfPbrBakeOptions;
import org.lytharalab.gfbs.gltf.api.io.ModelImporters;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.client.render.GltfMaterialBaker;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Embedded glTF material sprites exposed through Minecraft's normal atlas resource workflow. */
public final class GltfGeneratedMaterialPack implements PackResources {
    private static final ResourceLocation BLOCK_ATLAS =
        ResourceLocation.fromNamespaceAndPath("minecraft", "atlases/blocks.json");
    private static final AtomicLong GENERATIONS = new AtomicLong();
    private static final String GENERATED_PATH = "textures/generated/";
    private static final PackMetadataSection METADATA =
        new PackMetadataSection(Component.literal("Generated glTF material textures"), 15);
    private final String id;
    private final String generation = Long.toUnsignedString(GENERATIONS.incrementAndGet(), 36);
    private final Map<ResourceLocation, GltfAsset> assets = new HashMap<>();
    private final Map<GltfAsset, GltfMaterialBaker> bakers = new IdentityHashMap<>();
    private final Map<MaterialKey, GltfMaterialBaker.Material> materials = new HashMap<>();
    private final Map<ResourceLocation, byte[]> images = new HashMap<>();
    private ResourceManager sources;
    private byte[] atlasDefinition;
    private boolean closed;

    public GltfGeneratedMaterialPack(String id) { this.id = id; }

    public static void addPackFinder(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) return;
        event.addRepositorySource(consumer -> {
            Pack pack = Pack.readMetaAndCreate("gfbs_gltf_generated_materials",
                Component.literal("GFBS glTF materials"), true, GltfGeneratedMaterialPack::new,
                PackType.CLIENT_RESOURCES, Pack.Position.BOTTOM, PackSource.BUILT_IN);
            if (pack != null) consumer.accept(pack);
        });
    }

    @Nullable
    public static GltfGeneratedMaterialPack current() {
        return Minecraft.getInstance().getResourceManager().listPacks()
            .filter(GltfGeneratedMaterialPack.class::isInstance)
            .map(GltfGeneratedMaterialPack.class::cast).findFirst().orElse(null);
    }

    /** A fixed view of this reload's packs. Its packs are owned by Minecraft and stay shared. */
    private ResourceManager sources() {
        if (closed) throw new IllegalStateException("Generated glTF material pack is closed");
        if (sources == null) {
            List<PackResources> packs = Minecraft.getInstance().getResourceManager().listPacks().toList();
            sources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs);
        }
        return sources;
    }

    public synchronized GltfAsset loadAsset(ResourceLocation location) throws IOException {
        GltfAsset existing = assets.get(location);
        if (existing != null) return existing;
        ResourceManager resources = sources();
        GltfAsset loaded = ModelImporters.load(location, child -> resources.getResource(child)
            .orElseThrow(() -> new FileNotFoundException(child.toString())).open());
        assets.put(location, loaded);
        return loaded;
    }

    public synchronized GltfMaterialBaker.Material materialPages(
        GltfAsset asset, int meshIndex, int primitiveIndex, GltfPbrBakeOptions options
    ) {
        return materialPages(asset, meshIndex, primitiveIndex, options, false);
    }

    public synchronized GltfMaterialBaker.Material materialPages(
        GltfAsset asset, int meshIndex, int primitiveIndex, GltfPbrBakeOptions options, boolean flipV
    ) {
        if (closed) throw new IllegalStateException("Generated glTF material pack is closed");
        MaterialKey key = new MaterialKey(asset, meshIndex, primitiveIndex, options, flipV);
        GltfMaterialBaker.Material existing = materials.get(key);
        if (existing != null) return existing;
        GltfPrimitive primitive = asset.meshes().get(meshIndex).primitives().get(primitiveIndex);
        GltfMaterialBaker baker = bakers.computeIfAbsent(asset, model -> new GltfMaterialBaker(model.textures()));
        GltfMaterialBaker.Material material = baker.bake(primitive,
            asset.materials().get(primitive.material()), options, true, true, flipV);
        for (GltfMaterialBaker.Page page : material.pages()) {
            ResourceLocation sprite = sprite(asset, meshIndex, primitiveIndex, options, flipV, page.index);
            for (int kind = 0; kind < 3; kind++) {
                String suffix = kind == 0 ? "" : kind == 1 ? "_n" : "_s";
                images.put(ResourceLocation.fromNamespaceAndPath(sprite.getNamespace(),
                    "textures/" + sprite.getPath() + suffix + ".png"), page.images[kind]);
            }
        }
        materials.put(key, material);
        return material;
    }

    public ResourceLocation sprite(GltfAsset asset, int meshIndex, int primitiveIndex, int pageIndex) {
        return sprite(asset, meshIndex, primitiveIndex, GltfPbrBakeOptions.DEFAULT, false, pageIndex);
    }

    public ResourceLocation sprite(GltfAsset asset, int meshIndex, int primitiveIndex,
                                   GltfPbrBakeOptions options, int pageIndex) {
        return sprite(asset, meshIndex, primitiveIndex, options, false, pageIndex);
    }

    public ResourceLocation sprite(GltfAsset asset, int meshIndex, int primitiveIndex,
                                   GltfPbrBakeOptions options, boolean flipV, int pageIndex) {
        String quality = options.pageSize() + "_" + options.chartSize() + "_" + options.maxPixels();
        return ResourceLocation.fromNamespaceAndPath(GFBSglTF.MODID,
            "generated/" + generation + "/" + asset.id().getNamespace() + "/" + asset.id().getPath()
                + "/" + meshIndex + "/" + primitiveIndex + "/" + quality + "/"
                + (flipV ? "flipped" : "upright") + "/" + pageIndex);
    }

    public static GltfPbrBakeOptions bakeOptions(JsonObject model) {
        JsonElement element = model.get("pbr_bake");
        if (element == null) return GltfPbrBakeOptions.DEFAULT;
        JsonObject options = element.getAsJsonObject();
        GltfPbrBakeOptions defaults = GltfPbrBakeOptions.DEFAULT;
        return new GltfPbrBakeOptions(
            options.has("page_size") ? options.get("page_size").getAsInt() : defaults.pageSize(),
            options.has("chart_size") ? options.get("chart_size").getAsInt() : defaults.chartSize(),
            options.has("max_pixels") ? options.get("max_pixels").getAsLong() : defaults.maxPixels());
    }

    /** Mirrors the loader's {@code flip_v} default; a malformed value fails later in the loader. */
    private static boolean parseFlipV(JsonObject model) {
        JsonElement element = model.get("flip_v");
        return element != null && element.isJsonPrimitive()
            && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
    }

    private synchronized void prepareAtlas() {
        if (atlasDefinition != null || closed) return;
        JsonArray definitions = new JsonArray();
        Set<ResourceLocation> added = new java.util.HashSet<>();
        int models = 0;
        for (var entry : sources().listResources("models", path -> path.getPath().endsWith(".json")).entrySet()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) continue;
                JsonObject model = parsed.getAsJsonObject();
                if (!model.has("loader") || !"gfbs_gltf:gltf".equals(model.get("loader").getAsString())) continue;
                if (!model.has("model")) continue;
                ResourceLocation location = new ResourceLocation(model.get("model").getAsString());
                GltfAsset asset = loadAsset(location);
                GltfPbrBakeOptions options = bakeOptions(model);
                boolean flipV = parseFlipV(model);
                models++;
                for (int mesh = 0; mesh < asset.meshes().size(); mesh++) {
                    for (int primitive = 0; primitive < asset.meshes().get(mesh).primitives().size(); primitive++) {
                        var material = materialPages(asset, mesh, primitive, options, flipV);
                        for (GltfMaterialBaker.Page page : material.pages()) {
                            ResourceLocation sprite = sprite(asset, mesh, primitive, options, flipV, page.index);
                            if (!added.add(sprite)) continue;
                            JsonObject source = new JsonObject();
                            source.addProperty("type", "minecraft:single");
                            source.addProperty("resource", sprite.toString());
                            definitions.add(source);
                        }
                    }
                }
            } catch (IOException | RuntimeException failure) {
                GFBSglTF.LOGGER.error("Could not prepare embedded glTF materials from {}", entry.getKey(), failure);
            }
        }
        if (models > 0) {
            GFBSglTF.LOGGER.info("Prepared {} embedded glTF material sprite(s) from {} static model(s)",
                definitions.size(), models);
        }
        JsonObject atlas = new JsonObject();
        atlas.add("sources", definitions);
        atlasDefinition = atlas.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public synchronized IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES || closed) return null;
        if (location.equals(BLOCK_ATLAS)) {
            prepareAtlas();
            byte[] bytes = atlasDefinition;
            return () -> new ByteArrayInputStream(bytes);
        }
        if (!location.getNamespace().equals(GFBSglTF.MODID)
            || !location.getPath().startsWith(GENERATED_PATH)) return null;
        prepareAtlas();
        byte[] bytes = images.get(location);
        return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
    }

    @Override
    public synchronized void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || closed) return;
        if (namespace.equals("minecraft") && (path.isEmpty() || "atlases/blocks.json".startsWith(path + "/"))) {
            prepareAtlas();
            byte[] bytes = atlasDefinition;
            output.accept(BLOCK_ATLAS, () -> new ByteArrayInputStream(bytes));
        }
        if (!namespace.equals(GFBSglTF.MODID)
            || !(path.isEmpty() || GENERATED_PATH.startsWith(path + "/") || path.startsWith(GENERATED_PATH))) return;
        prepareAtlas();
        String prefix = path.isEmpty() ? "" : path + "/";
        images.forEach((location, bytes) -> {
            if (location.getPath().startsWith(prefix)) output.accept(location, () -> new ByteArrayInputStream(bytes));
        });
    }

    @Override public IoSupplier<InputStream> getRootResource(String... path) { return null; }
    @Override public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? Set.of("minecraft", GFBSglTF.MODID) : Set.of();
    }
    @Override public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        return serializer == PackMetadataSection.TYPE ? (T) METADATA : null;
    }
    @Override public String packId() { return id; }
    @Override public boolean isHidden() { return true; }
    @Override public boolean isBuiltin() { return true; }

    @Override public synchronized void close() {
        closed = true;
        assets.clear();
        bakers.clear();
        materials.clear();
        images.clear();
        sources = null;
        atlasDefinition = null;
    }

    private record MaterialKey(GltfAsset asset, int mesh, int primitive,
                               GltfPbrBakeOptions options, boolean flipV) {}
}
