package org.lytharalab.gfbs.gltf.api.plugin;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.lytharalab.gfbs.gltf.GFBSglTF;
import org.lytharalab.gfbs.gltf.plugin.GltfPluginManager;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Global registration, discovery, inspection, and extension-query API. */
public final class GltfPlugins {
    /** Forge inter-mod communication method used for plugin registration. */
    public static final String REGISTER_IMC_METHOD = "register_plugin";

    private static final GltfPluginManager MANAGER = new GltfPluginManager(
        currentDist(), production(), GFBSglTF.LOGGER
    );

    private GltfPlugins() {
    }

    private static Dist currentDist() {
        try {
            return FMLEnvironment.dist == null ? Dist.DEDICATED_SERVER : FMLEnvironment.dist;
        } catch (LinkageError ignored) {
            return Dist.DEDICATED_SERVER;
        }
    }

    private static boolean production() {
        try {
            return FMLEnvironment.production;
        } catch (LinkageError ignored) {
            return false;
        }
    }

    /**
     * Registers one plugin owned by a Forge mod. Call this from the owning mod constructor or send
     * the plugin through {@link #REGISTER_IMC_METHOD}. Registration closes at load-complete time.
     */
    public static void register(String ownerModId, GltfPlugin plugin) {
        MANAGER.register(ownerModId, plugin);
    }

    public static void registerAll(String ownerModId, Collection<? extends GltfPlugin> plugins) {
        Objects.requireNonNull(plugins, "plugins");
        for (GltfPlugin plugin : plugins) register(ownerModId, plugin);
    }

    public static List<GltfPluginInfo> plugins() {
        return MANAGER.plugins();
    }

    public static Optional<GltfPluginInfo> plugin(ResourceLocation id) {
        return MANAGER.plugin(id);
    }

    public static <T> List<T> extensions(GltfExtensionPoint<T> point) {
        return MANAGER.extensions(point).stream().map(GltfExtensionEntry::extension).toList();
    }

    public static <T> List<GltfExtensionEntry<T>> extensionEntries(GltfExtensionPoint<T> point) {
        return MANAGER.extensions(point);
    }

    /** Disables a plugin and every active plugin that requires it. */
    public static boolean disable(ResourceLocation id, String reason) {
        return MANAGER.disable(id, reason);
    }

    /** Host lifecycle entry point. Plugin mods should not call this method. */
    public static void initialize() {
        MANAGER.initialize();
    }

    /** Host IMC bridge. Plugin mods should send IMC instead of calling this method. */
    public static void acceptInterModRegistration(String ownerModId, Object payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload instanceof GltfPlugin plugin) {
            register(ownerModId, plugin);
            return;
        }
        if (payload instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (!(value instanceof GltfPlugin plugin)) {
                    throw new IllegalArgumentException("IMC plugin collection contains " + value);
                }
                register(ownerModId, plugin);
            }
            return;
        }
        throw new IllegalArgumentException(
            "Expected GltfPlugin or Iterable<GltfPlugin>, got " + payload.getClass().getName()
        );
    }
}
