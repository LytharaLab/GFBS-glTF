#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec3 Normal;
in vec4 Color;
in vec4 TexCoords;
in uvec4 Joints;
in vec4 Weights;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform samplerBuffer GltfBones;
uniform int GltfCachedDeformation;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

uniform mat4 GltfModel;
uniform mat3 GltfNormal;
uniform ivec2 GltfLight;
uniform ivec2 GltfOverlay;
uniform int GltfPass;
uniform vec4 GltfMaterialColor;
uniform vec4 GltfTint;
uniform int GltfTexCoord;
uniform vec4 GltfUvScaleOffset;
uniform vec2 GltfUvRotation;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec4 normal;
out vec4 GltfSkinnedPosition;
out vec4 GltfSkinnedNormal;

void skin(out vec3 position, out vec3 localNormal) {
    const float epsilon = 1.0e-8;
    vec4 weights = max(Weights, vec4(0.0));
    float total = weights.x + weights.y + weights.z + weights.w;
    position = Position;
    localNormal = Normal;
    if (total <= epsilon) return;

    vec3 positionSum = vec3(0.0);
    vec3 normalSum = vec3(0.0);
    for (int i = 0; i < 4; ++i) {
        if (weights[i] <= 0.0) continue;
        int base = int(Joints[i]) * 4;
        mat3 basis = mat3(texelFetch(GltfBones, base).xyz,
                          texelFetch(GltfBones, base + 1).xyz,
                          texelFetch(GltfBones, base + 2).xyz);
        vec3 translation = texelFetch(GltfBones, base + 3).xyz;
        positionSum += (basis * Position + translation) * weights[i];
        normalSum += (basis * Normal) * weights[i];
    }
    position = positionSum * (1.0 / total);
    float normalLength = length(normalSum);
    if (normalLength > epsilon) localNormal = normalSum / normalLength;
}

void main() {
    vec3 localPosition, localNormal;
    if (GltfCachedDeformation != 0) {
        localPosition = texelFetch(GltfBones, gl_VertexID * 2).xyz;
        localNormal = texelFetch(GltfBones, gl_VertexID * 2 + 1).xyz;
    } else {
        skin(localPosition, localNormal);
    }
    GltfSkinnedPosition = vec4(localPosition, 1.0);
    GltfSkinnedNormal = vec4(localNormal, 0.0);
    vec3 position = (GltfModel * GltfSkinnedPosition).xyz;
    vec3 n = GltfNormal * localNormal;
    float lengthSquared = dot(n, n);
    if (lengthSquared > 1.0e-12 && abs(lengthSquared - 1.0) > 0.0001) {
        n *= inversesqrt(lengthSquared);
    }
    // Match NEW_ENTITY's signed-byte normals and the streamed path's rounded color channels.
    n = trunc(clamp(n, -1.0, 1.0) * 127.0) / 127.0;
    vec4 color = GltfPass == 2 ? vec4(GltfMaterialColor.rgb * GltfTint.rgb, 1.0)
        : vec4(Color.rgb * GltfMaterialColor.rgb * GltfTint.rgb,
               (GltfMaterialColor.a > 0.0 ? Color.a : 1.0) * GltfTint.a);
    color = floor(clamp(color, 0.0, 1.0) * 255.0 + 0.5) / 255.0;

    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * position, FogShape);
    vertexColor = color;
    lightMapColor = vec4(1.0);
    overlayColor = vec4(1.0);
    if (GltfPass == 0) {
        vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, n, color);
        lightMapColor = texelFetch(Sampler2, GltfLight / 16, 0);
        overlayColor = texelFetch(Sampler1, GltfOverlay, 0);
    }
    vec2 uv = (GltfTexCoord == 1 ? TexCoords.zw : TexCoords.xy) * GltfUvScaleOffset.xy;
    texCoord0 = GltfUvScaleOffset.zw + vec2(GltfUvRotation.x * uv.x - GltfUvRotation.y * uv.y,
                                           GltfUvRotation.y * uv.x + GltfUvRotation.x * uv.y);
    normal = ProjMat * ModelViewMat * vec4(n, 0.0);
}
