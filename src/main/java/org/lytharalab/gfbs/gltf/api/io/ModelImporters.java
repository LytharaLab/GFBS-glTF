package org.lytharalab.gfbs.gltf.api.io;

import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.core.io.GltfAssetImporter;
import org.lytharalab.gfbs.gltf.core.io.ObjAssetImporter;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Thread-safe registry used by both the asynchronous client manager and direct callers.
 */
public final class ModelImporters {
    private static final Map<String, ModelImporter> IMPORTERS = new LinkedHashMap<>();

    static {
        registerBuiltin(new GltfAssetImporter());
        registerBuiltin(new ObjAssetImporter());
    }

    private ModelImporters() {
    }

    public static synchronized void register(ModelImporter importer) {
        Objects.requireNonNull(importer, "importer");
        Collection<String> extensions = Objects.requireNonNull(importer.extensions(), "extensions");
        if (extensions.isEmpty()) throw new IllegalArgumentException("Importer must declare an extension");
        for (String extension : extensions) {
            String normalized = normalize(extension);
            ModelImporter existing = IMPORTERS.get(normalized);
            if (existing != null && existing != importer) {
                throw new IllegalStateException("An importer is already registered for ." + normalized);
            }
        }
        for (String extension : extensions) IMPORTERS.put(normalize(extension), importer);
    }

    public static synchronized void replace(ModelImporter importer) {
        Objects.requireNonNull(importer, "importer");
        for (String extension : importer.extensions()) IMPORTERS.put(normalize(extension), importer);
    }

    public static synchronized boolean unregister(ModelImporter importer) {
        Objects.requireNonNull(importer, "importer");
        return IMPORTERS.entrySet().removeIf(entry -> entry.getValue() == importer);
    }

    public static synchronized Optional<ModelImporter> find(ResourceLocation location) {
        Objects.requireNonNull(location, "location");
        return Optional.ofNullable(IMPORTERS.get(extensionOf(location.getPath())));
    }

    public static synchronized Set<String> extensions() {
        return Set.copyOf(IMPORTERS.keySet());
    }

    public static GltfAsset load(ResourceLocation location, GltfResolver resolver) throws IOException {
        ModelImporter importer = find(location).orElseThrow(() ->
            new IOException("No model importer is registered for " + location));
        return importer.load(location, resolver);
    }

    public static boolean supports(ResourceLocation location) {
        return find(location).isPresent();
    }

    private static void registerBuiltin(ModelImporter importer) {
        register(importer);
    }

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : normalize(path.substring(dot + 1));
    }

    private static String normalize(String extension) {
        String normalized = Objects.requireNonNull(extension, "extension").trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith(".")) normalized = normalized.substring(1);
        if (normalized.isEmpty() || normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid model extension: " + extension);
        }
        return normalized;
    }
}
