package org.lytharalab.gfbs.gltf.api.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Original Minecraft entity-shader RenderTypes plus a validated custom builder.
 */
public final class GltfRenderTypes {
    public static final int ORDER_SOLID = 0;
    public static final int ORDER_CUTOUT = 100;
    public static final int ORDER_CUSTOM = 500;
    public static final int ORDER_TRANSLUCENT = 1000;
    public static final VertexFormat REQUIRED_FORMAT = DefaultVertexFormat.NEW_ENTITY;

    private static final int BUFFER_SIZE = 2 * 1024 * 1024;
    private static final RenderStateShard.TransparencyStateShard NO_TRANSPARENCY =
        new RenderStateShard.TransparencyStateShard("gfbs_gltf_none", () -> { }, () -> { });
    private static final RenderStateShard.TransparencyStateShard TRANSLUCENT =
        new RenderStateShard.TransparencyStateShard("gfbs_gltf_translucent", () -> {
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
            );
        }, () -> {
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        });
    private static final RenderStateShard.TransparencyStateShard ADDITIVE =
        new RenderStateShard.TransparencyStateShard("gfbs_gltf_additive", () -> {
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE
            );
        }, () -> {
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        });
    private static final Map<String, RenderType> CACHE = new HashMap<>();
    private static final Map<RenderType, Integer> ORDER = new IdentityHashMap<>();

    private GltfRenderTypes() {
    }

    public static RenderType solid(ResourceLocation texture, boolean cull) {
        return standard("solid", texture, cull, GameRenderer::getRendertypeEntitySolidShader,
            NO_TRANSPARENCY, ORDER_SOLID, true, VertexFormat.Mode.QUADS);
    }

    public static RenderType cutout(ResourceLocation texture, boolean cull) {
        Supplier<ShaderInstance> shader = cull
            ? GameRenderer::getRendertypeEntityCutoutShader
            : GameRenderer::getRendertypeEntityCutoutNoCullShader;
        return standard("cutout", texture, cull, shader, NO_TRANSPARENCY, ORDER_CUTOUT, true,
            VertexFormat.Mode.QUADS);
    }

    public static RenderType translucent(ResourceLocation texture, boolean cull) {
        Supplier<ShaderInstance> shader = cull
            ? GameRenderer::getRendertypeEntityTranslucentCullShader
            : GameRenderer::getRendertypeEntityTranslucentShader;
        return standard("translucent", texture, cull, shader, TRANSLUCENT,
            ORDER_TRANSLUCENT, false, VertexFormat.Mode.QUADS);
    }

    public static RenderType lines(ResourceLocation texture, boolean translucent) {
        return standard(
            translucent ? "translucent_lines" : "lines",
            texture,
            false,
            translucent ? GameRenderer::getRendertypeEntityTranslucentShader
                : GameRenderer::getRendertypeEntitySolidShader,
            translucent ? TRANSLUCENT : NO_TRANSPARENCY,
            translucent ? ORDER_TRANSLUCENT : ORDER_SOLID,
            !translucent,
            VertexFormat.Mode.LINES
        );
    }

    public static boolean isCompatible(RenderType type) {
        return type != null && type.format() == REQUIRED_FORMAT;
    }

    public static int sortOrder(RenderType type) {
        return ORDER.getOrDefault(type, ORDER_CUSTOM);
    }

    public static void registerSortOrder(RenderType type, int order) {
        if (type != null) ORDER.put(type, order);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    private static RenderType standard(String kind, ResourceLocation texture, boolean cull,
                                       Supplier<ShaderInstance> shader,
                                       RenderStateShard.TransparencyStateShard transparency,
                                       int order, boolean writeDepth, VertexFormat.Mode mode) {
        Objects.requireNonNull(texture, "texture");
        String key = kind + ":" + texture + ":" + cull;
        return CACHE.computeIfAbsent(key, ignored -> {
            RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(shader))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(transparency)
                .setCullState(new RenderStateShard.CullStateShard(cull))
                .setLightmapState(new RenderStateShard.LightmapStateShard(true))
                .setOverlayState(new RenderStateShard.OverlayStateShard(true))
                .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, writeDepth))
                .createCompositeState(true);
            RenderType type = RenderType.create(
                "gfbs_gltf_" + kind + "_" + Integer.toUnsignedString(key.hashCode()),
                REQUIRED_FORMAT,
                mode,
                BUFFER_SIZE,
                true,
                kind.equals("translucent"),
                state
            );
            ORDER.put(type, order);
            return type;
        });
    }

    public static final class Builder {
        private final String name;
        private ResourceLocation texture;
        private boolean blur;
        private boolean mipmap;
        private Supplier<ShaderInstance> shader = GameRenderer::getRendertypeEntitySolidShader;
        private RenderStateShard.TransparencyStateShard transparency = NO_TRANSPARENCY;
        private boolean cull = true;
        private boolean lightmap = true;
        private boolean overlay = true;
        private boolean writeColor = true;
        private boolean writeDepth = true;
        private int depthFunction = GL11.GL_LEQUAL;
        private boolean outline = true;
        private boolean sortOnUpload;
        private int bufferSize = BUFFER_SIZE;
        private int sortOrder = ORDER_CUSTOM;
        private VertexFormat.Mode mode = VertexFormat.Mode.QUADS;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
            if (name.isBlank()) throw new IllegalArgumentException("RenderType name is blank");
        }

        public Builder texture(ResourceLocation value) { texture = value; return this; }
        public Builder texture(ResourceLocation value, boolean blur, boolean mipmap) {
            texture = value;
            this.blur = blur;
            this.mipmap = mipmap;
            return this;
        }
        public Builder shader(Supplier<ShaderInstance> value) {
            shader = Objects.requireNonNull(value, "shader");
            return this;
        }
        public Builder noBlend() { transparency = NO_TRANSPARENCY; return this; }
        public Builder translucentBlend() { transparency = TRANSLUCENT; return this; }
        public Builder additiveBlend() { transparency = ADDITIVE; return this; }
        public Builder cull(boolean value) { cull = value; return this; }
        public Builder lightmap(boolean value) { lightmap = value; return this; }
        public Builder overlay(boolean value) { overlay = value; return this; }
        public Builder writeColor(boolean value) { writeColor = value; return this; }
        public Builder writeDepth(boolean value) { writeDepth = value; return this; }
        public Builder depthFunction(int value) { depthFunction = value; return this; }
        public Builder outline(boolean value) { outline = value; return this; }
        public Builder sortOnUpload(boolean value) { sortOnUpload = value; return this; }
        public Builder bufferSize(int value) {
            if (value <= 0) throw new IllegalArgumentException("Buffer size must be positive");
            bufferSize = value;
            return this;
        }
        public Builder sortOrder(int value) { sortOrder = value; return this; }
        public Builder mode(VertexFormat.Mode value) {
            mode = Objects.requireNonNull(value, "mode");
            if (mode != VertexFormat.Mode.QUADS && mode != VertexFormat.Mode.TRIANGLES) {
                throw new IllegalArgumentException("GFBS RenderTypes support QUADS or TRIANGLES");
            }
            return this;
        }

        public RenderType build() {
            if (texture == null) throw new IllegalStateException("A texture is required");
            String key = "custom:" + name;
            return CACHE.computeIfAbsent(key, ignored -> {
                RenderType.CompositeState state = RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(shader))
                    .setTextureState(new RenderStateShard.TextureStateShard(texture, blur, mipmap))
                    .setTransparencyState(transparency)
                    .setCullState(new RenderStateShard.CullStateShard(cull))
                    .setLightmapState(new RenderStateShard.LightmapStateShard(lightmap))
                    .setOverlayState(new RenderStateShard.OverlayStateShard(overlay))
                    .setDepthTestState(new RenderStateShard.DepthTestStateShard(
                        "gfbs_gltf_depth_" + depthFunction, depthFunction
                    ))
                    .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(writeColor, writeDepth))
                    .createCompositeState(outline);
                RenderType type = RenderType.create(
                    "gfbs_gltf_custom_" + name,
                    REQUIRED_FORMAT,
                    mode,
                    bufferSize,
                    true,
                    sortOnUpload,
                    state
                );
                ORDER.put(type, sortOrder);
                return type;
            });
        }
    }
}
