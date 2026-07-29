package org.lytharalab.gfbs.gltf.core.io;

import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.io.GltfResolver;
import org.lytharalab.gfbs.gltf.api.io.ModelImporter;
import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfMesh;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfScene;
import org.lytharalab.gfbs.gltf.api.model.GltfTexture;
import org.lytharalab.gfbs.gltf.api.model.PrimitiveMode;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * OBJ/MTL importer which converts groups and materials into the normal immutable GFBS asset model.
 */
public final class ObjAssetImporter implements ModelImporter {
    private static final int MAX_TEXT_BYTES = 32 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 64 * 1024 * 1024;

    @Override
    public Collection<String> extensions() {
        return List.of("obj");
    }

    @Override
    public GltfAsset load(ResourceLocation source, GltfResolver resolver) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(resolver, "resolver");
        List<float[]> positions = new ArrayList<>();
        List<float[]> texCoords = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        Map<String, MaterialBuilder> materials = new LinkedHashMap<>();
        Map<GroupKey, GroupBuilder> groups = new LinkedHashMap<>();
        String groupName = "default";
        String materialName = "default";
        materials.put(materialName, new MaterialBuilder(materialName));

        try (InputStream input = resolver.open(source);
             BufferedReader reader = reader(input, MAX_TEXT_BYTES)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int split = firstWhitespace(line);
                String key = split < 0 ? line : line.substring(0, split);
                String rest = split < 0 ? "" : line.substring(split).trim();
                try {
                    switch (key) {
                        case "v" -> positions.add(parseFloats(rest, 3, "position"));
                        case "vt" -> texCoords.add(parseFloats(rest, 2, "texture coordinate"));
                        case "vn" -> normals.add(parseFloats(rest, 3, "normal"));
                        case "o", "g" -> groupName = rest.isBlank() ? "group_" + groups.size() : rest;
                        case "usemtl" -> {
                            materialName = rest.isBlank() ? "default" : rest;
                            materials.computeIfAbsent(materialName, MaterialBuilder::new);
                        }
                        case "mtllib" -> loadMaterialLibrary(
                            resolveSibling(source, stripMapOptions(rest)), resolver, materials
                        );
                        case "f" -> groups.computeIfAbsent(
                            new GroupKey(groupName, materialName), GroupBuilder::new
                        ).addFace(rest, positions, texCoords, normals);
                        default -> {
                        }
                    }
                } catch (RuntimeException exception) {
                    throw new GltfLoadException("Invalid OBJ statement '" + key + "' in " + source, exception);
                }
            }
        }

        if (positions.isEmpty()) throw new GltfLoadException("OBJ contains no positions: " + source);
        if (groups.values().stream().noneMatch(group -> !group.indices.isEmpty())) {
            throw new GltfLoadException("OBJ contains no faces: " + source);
        }

        List<GltfTexture> textures = new ArrayList<>();
        Map<String, Integer> textureIndices = new LinkedHashMap<>();
        List<GltfMaterial> builtMaterials = new ArrayList<>();
        Map<String, Integer> materialIndices = new LinkedHashMap<>();
        for (MaterialBuilder material : materials.values()) {
            int texture = -1;
            if (material.diffuseMap != null) {
                Integer existing = textureIndices.get(material.diffuseMap);
                if (existing == null) {
                    ResourceLocation textureLocation = resolveSibling(source, material.diffuseMap);
                    byte[] encoded;
                    try (InputStream image = resolver.open(textureLocation)) {
                        encoded = readLimited(image, MAX_IMAGE_BYTES, "OBJ texture " + textureLocation);
                    }
                    existing = textures.size();
                    textures.add(new GltfTexture(
                        material.diffuseMap,
                        mimeType(material.diffuseMap),
                        ByteBuffer.wrap(encoded),
                        9729,
                        9987,
                        10497,
                        10497
                    ));
                    textureIndices.put(material.diffuseMap, existing);
                }
                texture = existing;
            }
            materialIndices.put(material.name, builtMaterials.size());
            builtMaterials.add(new GltfMaterial(
                material.name,
                new float[]{material.red, material.green, material.blue, material.alpha},
                texture,
                0,
                null,
                -1,
                0,
                material.alpha < 0.999f ? AlphaMode.BLEND : AlphaMode.OPAQUE,
                0.5f,
                false
            ));
        }

        List<GltfMesh> meshes = new ArrayList<>();
        List<GltfNode> nodes = new ArrayList<>();
        for (GroupBuilder group : groups.values()) {
            if (group.indices.isEmpty()) continue;
            group.finishNormals();
            int vertexCount = group.vertices.size();
            float[] meshPositions = new float[vertexCount * 3];
            float[] meshNormals = new float[vertexCount * 3];
            float[] meshTexCoords = new float[vertexCount * 2];
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                Vertex data = group.vertices.get(vertex);
                System.arraycopy(data.position, 0, meshPositions, vertex * 3, 3);
                System.arraycopy(data.normal, 0, meshNormals, vertex * 3, 3);
                System.arraycopy(data.uv, 0, meshTexCoords, vertex * 2, 2);
            }
            int material = materialIndices.getOrDefault(group.key.material(), 0);
            GltfPrimitive primitive = new GltfPrimitive(
                PrimitiveMode.TRIANGLES,
                material,
                vertexCount,
                meshPositions,
                meshNormals,
                null,
                meshTexCoords,
                null,
                null,
                null,
                null,
                group.indices.stream().mapToInt(Integer::intValue).toArray(),
                List.of()
            );
            int mesh = meshes.size();
            meshes.add(new GltfMesh(group.key.displayName(), List.of(primitive), null));
            nodes.add(new GltfNode(
                group.key.displayName(),
                -1,
                new int[0],
                new int[]{mesh},
                -1,
                null,
                null,
                null,
                null,
                null
            ));
        }

        int[] roots = new int[nodes.size()];
        for (int i = 0; i < roots.length; i++) roots[i] = i;
        return new GltfAsset(
            source,
            List.of(new GltfScene("OBJ", roots)),
            nodes,
            meshes,
            builtMaterials,
            textures,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            0
        );
    }

    private static void loadMaterialLibrary(ResourceLocation location, GltfResolver resolver,
                                            Map<String, MaterialBuilder> materials) throws IOException {
        try (InputStream input = resolver.open(location);
             BufferedReader reader = reader(input, MAX_TEXT_BYTES)) {
            MaterialBuilder current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int split = firstWhitespace(line);
                String key = split < 0 ? line : line.substring(0, split);
                String rest = split < 0 ? "" : line.substring(split).trim();
                switch (key) {
                    case "newmtl" -> current = materials.computeIfAbsent(rest, MaterialBuilder::new);
                    case "Kd" -> {
                        if (current != null) {
                            float[] diffuse = parseFloats(rest, 3, "diffuse color");
                            current.red = unit(diffuse[0]);
                            current.green = unit(diffuse[1]);
                            current.blue = unit(diffuse[2]);
                        }
                    }
                    case "d" -> {
                        if (current != null) current.alpha = unit(firstFloat(rest));
                    }
                    case "Tr" -> {
                        if (current != null) current.alpha = 1.0f - unit(firstFloat(rest));
                    }
                    case "map_Kd" -> {
                        if (current != null && !rest.isBlank()) current.diffuseMap = stripMapOptions(rest);
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private static BufferedReader reader(InputStream input, int limit) throws IOException {
        byte[] bytes = readLimited(input, limit, "text resource");
        return new BufferedReader(new InputStreamReader(
            new ByteArrayInputStream(bytes), StandardCharsets.UTF_8
        ));
    }

    private static byte[] readLimited(InputStream input, int limit, String label) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(8192, limit));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total = Math.addExact(total, read);
            if (total > limit) throw new GltfLoadException(label + " exceeds " + limit + " bytes");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static ResourceLocation resolveSibling(ResourceLocation source, String relative) throws IOException {
        String clean = relative.replace('\\', '/').trim();
        while (clean.startsWith("./")) clean = clean.substring(2);
        if (clean.isEmpty() || clean.startsWith("/") || clean.contains(":")) {
            throw new GltfLoadException("Unsafe OBJ resource reference: " + relative);
        }
        String[] segments = clean.split("/");
        for (String segment : segments) {
            if (segment.equals("..") || segment.isEmpty()) {
                throw new GltfLoadException("Unsafe OBJ resource reference: " + relative);
            }
        }
        String path = source.getPath();
        int slash = path.lastIndexOf('/');
        String base = slash < 0 ? "" : path.substring(0, slash + 1);
        return ResourceLocation.fromNamespaceAndPath(source.getNamespace(), base + clean);
    }

    private static int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return i;
        }
        return -1;
    }

    private static float[] parseFloats(String value, int count, String label) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length < count) throw new IllegalArgumentException("Missing " + label + " components");
        float[] result = new float[count];
        for (int i = 0; i < count; i++) {
            result[i] = Float.parseFloat(parts[i]);
            if (!Float.isFinite(result[i])) throw new IllegalArgumentException("Non-finite " + label);
        }
        return result;
    }

    private static float firstFloat(String value) {
        return Float.parseFloat(value.trim().split("\\s+")[0]);
    }

    private static float unit(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static String stripMapOptions(String value) {
        String[] parts = value.trim().split("\\s+");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private static String mimeType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private record GroupKey(String group, String material) {
        private GroupKey {
            group = group == null || group.isBlank() ? "default" : group;
            material = material == null || material.isBlank() ? "default" : material;
        }

        String displayName() {
            return material.equals("default") ? group : group + "/" + material;
        }
    }

    private static final class MaterialBuilder {
        final String name;
        float red = 1.0f;
        float green = 1.0f;
        float blue = 1.0f;
        float alpha = 1.0f;
        String diffuseMap;

        MaterialBuilder(String name) {
            this.name = name == null || name.isBlank() ? "default" : name;
        }
    }

    private static final class Vertex {
        final float[] position = new float[3];
        final float[] uv = new float[2];
        final float[] normal = new float[3];
        boolean explicitNormal;
    }

    private static final class GroupBuilder {
        final GroupKey key;
        final List<Vertex> vertices = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();
        final Map<String, Integer> vertexCache = new LinkedHashMap<>();

        GroupBuilder(GroupKey key) {
            this.key = key;
        }

        void addFace(String face, List<float[]> positions, List<float[]> texCoords,
                     List<float[]> normals) {
            String[] tokens = face.trim().split("\\s+");
            if (tokens.length < 3) throw new IllegalArgumentException("Face has fewer than three vertices");
            int[] resolved = new int[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                resolved[i] = resolve(tokens[i], positions, texCoords, normals);
            }
            for (int i = 1; i + 1 < resolved.length; i++) {
                indices.add(resolved[0]);
                indices.add(resolved[i]);
                indices.add(resolved[i + 1]);
            }
        }

        int resolve(String token, List<float[]> positions, List<float[]> texCoords,
                    List<float[]> normals) {
            Integer cached = vertexCache.get(token);
            if (cached != null) return cached;
            String[] fields = token.split("/", -1);
            if (fields.length == 0 || fields[0].isEmpty()) {
                throw new IllegalArgumentException("Face vertex has no position index");
            }
            int position = index(fields[0], positions.size());
            int texCoord = fields.length > 1 && !fields[1].isEmpty() ? index(fields[1], texCoords.size()) : -1;
            int normal = fields.length > 2 && !fields[2].isEmpty() ? index(fields[2], normals.size()) : -1;
            Vertex vertex = new Vertex();
            System.arraycopy(positions.get(position), 0, vertex.position, 0, 3);
            if (texCoord >= 0) {
                float[] uv = texCoords.get(texCoord);
                vertex.uv[0] = uv[0];
                vertex.uv[1] = 1.0f - uv[1];
            }
            if (normal >= 0) {
                System.arraycopy(normals.get(normal), 0, vertex.normal, 0, 3);
                normalize(vertex.normal);
                vertex.explicitNormal = true;
            }
            int result = vertices.size();
            vertices.add(vertex);
            vertexCache.put(token, result);
            return result;
        }

        void finishNormals() {
            boolean needsGenerated = vertices.stream().anyMatch(vertex -> !vertex.explicitNormal);
            if (!needsGenerated) return;
            for (int i = 0; i + 2 < indices.size(); i += 3) {
                Vertex a = vertices.get(indices.get(i));
                Vertex b = vertices.get(indices.get(i + 1));
                Vertex c = vertices.get(indices.get(i + 2));
                float abX = b.position[0] - a.position[0];
                float abY = b.position[1] - a.position[1];
                float abZ = b.position[2] - a.position[2];
                float acX = c.position[0] - a.position[0];
                float acY = c.position[1] - a.position[1];
                float acZ = c.position[2] - a.position[2];
                float nx = abY * acZ - abZ * acY;
                float ny = abZ * acX - abX * acZ;
                float nz = abX * acY - abY * acX;
                addNormal(a, nx, ny, nz);
                addNormal(b, nx, ny, nz);
                addNormal(c, nx, ny, nz);
            }
            for (Vertex vertex : vertices) {
                if (!vertex.explicitNormal) normalize(vertex.normal);
            }
        }

        private static void addNormal(Vertex vertex, float x, float y, float z) {
            if (vertex.explicitNormal) return;
            vertex.normal[0] += x;
            vertex.normal[1] += y;
            vertex.normal[2] += z;
        }

        private static int index(String encoded, int size) {
            int index = Integer.parseInt(encoded);
            index = index < 0 ? size + index : index - 1;
            if (index < 0 || index >= size) throw new IllegalArgumentException("OBJ index is out of range");
            return index;
        }
    }

    private static void normalize(float[] vector) {
        double lengthSquared = (double) vector[0] * vector[0]
            + (double) vector[1] * vector[1]
            + (double) vector[2] * vector[2];
        if (!Double.isFinite(lengthSquared) || lengthSquared <= 1.0e-16) {
            vector[0] = 0.0f;
            vector[1] = 1.0f;
            vector[2] = 0.0f;
            return;
        }
        float inverse = (float) (1.0 / Math.sqrt(lengthSquared));
        vector[0] *= inverse;
        vector[1] *= inverse;
        vector[2] *= inverse;
    }
}
