package org.lytharalab.gfbs.gltf.client.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.model.geometry.IGeometryLoader;
import org.joml.Vector3f;
import org.lytharalab.gfbs.gltf.api.io.ModelImporters;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;

import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loader registered as {@code gfbs_gltf:gltf} for static Forge block and item models.
 */
public final class StaticGltfGeometryLoader
    implements IGeometryLoader<StaticGltfGeometry>, ResourceManagerReloadListener {

    public static final StaticGltfGeometryLoader INSTANCE = new StaticGltfGeometryLoader();

    private final ConcurrentHashMap<ResourceLocation, GltfAsset> cache = new ConcurrentHashMap<>();
    private volatile ResourceManager resources;

    private StaticGltfGeometryLoader() {
    }

    public void initialize() {
        resources = Minecraft.getInstance().getResourceManager();
    }

    @Override
    public StaticGltfGeometry read(JsonObject json, JsonDeserializationContext context)
        throws JsonParseException {
        ResourceLocation model = parseLocation(requiredString(json, "model"), "model");
        String path = model.getPath().toLowerCase(java.util.Locale.ROOT);
        if (!path.endsWith(".gltf") && !path.endsWith(".glb")) {
            throw new JsonParseException(
                "gfbs_gltf:gltf requires a .gltf or .glb resource, got " + model
            );
        }
        GltfAsset asset = load(model);
        int scene = optionalInt(json, "scene", asset.defaultScene());
        Vector3f scale = optionalVector(json, "scale", new Vector3f(1.0f));
        Vector3f translation = optionalVector(json, "translation", new Vector3f());
        requireFiniteNonZero(scale, "scale");
        requireFinite(translation, "translation");
        StaticGltfGeometry.Settings settings = new StaticGltfGeometry.Settings(
            scene,
            scale,
            translation,
            optionalBoolean(json, "flip_v", false),
            optionalBoolean(json, "shade", true),
            optionalBoolean(json, "automatic_culling", false),
            optionalStringMap(json, "material_textures")
        );
        try {
            return new StaticGltfGeometry(asset, settings);
        } catch (RuntimeException exception) {
            throw new JsonParseException("Invalid static glTF model " + model + ": "
                + exception.getMessage(), exception);
        }
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        resources = Objects.requireNonNull(manager, "manager");
        cache.clear();
    }

    private GltfAsset load(ResourceLocation location) {
        ResourceManager manager = resources;
        if (manager == null) {
            manager = Minecraft.getInstance().getResourceManager();
            resources = manager;
        }
        ResourceManager currentManager = manager;
        try {
            return cache.computeIfAbsent(location, id -> {
                try {
                    return ModelImporters.load(id, child -> currentManager.getResource(child)
                        .orElseThrow(() -> new FileNotFoundException(child.toString())).open());
                } catch (Exception exception) {
                    throw new LoadFailure(exception);
                }
            });
        } catch (LoadFailure failure) {
            Throwable cause = failure.getCause();
            throw new JsonParseException("Could not load static glTF model " + location + ": "
                + message(cause), cause);
        }
    }

    private static String requiredString(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("gfbs_gltf:gltf requires a string '" + name + "' member");
        }
        String value = element.getAsString();
        if (value.isBlank()) throw new JsonParseException("'" + name + "' cannot be blank");
        return value;
    }

    private static ResourceLocation parseLocation(String value, String label) {
        ResourceLocation location = ResourceLocation.tryParse(value);
        if (location == null) {
            throw new JsonParseException("Invalid " + label + " resource location: " + value);
        }
        return location;
    }

    private static boolean optionalBoolean(JsonObject json, String name, boolean fallback) {
        JsonElement element = json.get(name);
        if (element == null) return fallback;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException("'" + name + "' must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static int optionalInt(JsonObject json, String name, int fallback) {
        JsonElement element = json.get(name);
        if (element == null) return fallback;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("'" + name + "' must be an integer");
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException exception) {
            throw new JsonParseException("'" + name + "' must be an integer", exception);
        }
    }

    private static Vector3f optionalVector(JsonObject json, String name, Vector3f fallback) {
        JsonElement element = json.get(name);
        if (element == null) return new Vector3f(fallback);
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            float value = element.getAsFloat();
            return new Vector3f(value);
        }
        if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            throw new JsonParseException("'" + name + "' must be a number or a three-number array");
        }
        try {
            return new Vector3f(
                element.getAsJsonArray().get(0).getAsFloat(),
                element.getAsJsonArray().get(1).getAsFloat(),
                element.getAsJsonArray().get(2).getAsFloat()
            );
        } catch (RuntimeException exception) {
            throw new JsonParseException("'" + name + "' contains a non-number", exception);
        }
    }

    private static Map<String, String> optionalStringMap(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) return Map.of();
        if (!element.isJsonObject()) {
            throw new JsonParseException("'" + name + "' must be an object");
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
                throw new JsonParseException(
                    "'" + name + "." + entry.getKey() + "' must be a non-blank texture reference"
                );
            }
            result.put(entry.getKey(), value.getAsString());
        }
        return Map.copyOf(result);
    }

    private static void requireFinite(Vector3f vector, String label) {
        if (!Float.isFinite(vector.x) || !Float.isFinite(vector.y) || !Float.isFinite(vector.z)) {
            throw new JsonParseException("'" + label + "' must contain finite values");
        }
    }

    private static void requireFiniteNonZero(Vector3f vector, String label) {
        requireFinite(vector, label);
        if (vector.x == 0.0f || vector.y == 0.0f || vector.z == 0.0f) {
            throw new JsonParseException("'" + label + "' cannot contain zero");
        }
    }

    private static String message(Throwable throwable) {
        if (throwable == null) return "unknown error";
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value;
    }

    private static final class LoadFailure extends RuntimeException {
        private LoadFailure(Throwable cause) {
            super(cause);
        }
    }
}
