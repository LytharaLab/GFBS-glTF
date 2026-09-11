package org.lytharalab.gfbs.gltf.plugin;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.plugin.GltfExtensionPoint;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPlugin;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPluginContext;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPluginInfo;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPluginMetadata;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPluginState;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfPluginManagerTest {
    private static final GltfExtensionPoint<String> TEXT = GltfExtensionPoint.multiple(
        id("test", "text"), String.class
    );

    @Test
    void loadsDependenciesBeforeDependentsAndAllowsMultiplePluginsPerMod() {
        GltfPluginManager manager = manager();
        List<String> lifecycle = new ArrayList<>();
        manager.register("suite", plugin(
            metadata("suite", "dependent").requires(id("suite", "base"), "[1.0,2.0)").build(),
            context -> {
                lifecycle.add("dependent");
                context.register(TEXT, "later", 20);
            }
        ));
        manager.register("suite", plugin(
            metadata("suite", "base").build(),
            context -> {
                lifecycle.add("base");
                context.register(TEXT, "first", -10);
            }
        ));

        manager.initialize();

        assertEquals(List.of("base", "dependent"), lifecycle);
        assertEquals(List.of("first", "later"),
            manager.extensions(TEXT).stream().map(value -> value.extension()).toList());
        assertTrue(manager.plugins().stream().allMatch(value -> value.state() == GltfPluginState.ACTIVE));
    }

    @Test
    void blocksMissingAndCyclicDependenciesWithoutLoadingThem() {
        GltfPluginManager manager = manager();
        manager.register("suite", plugin(
            metadata("suite", "missing").requires(id("other", "absent"), "[1,)").build(),
            context -> { throw new AssertionError("must not load"); }
        ));
        manager.register("suite", plugin(
            metadata("suite", "cycle_a").requires(id("suite", "cycle_b"), "[1,)").build(),
            context -> { throw new AssertionError("must not load"); }
        ));
        manager.register("suite", plugin(
            metadata("suite", "cycle_b").requires(id("suite", "cycle_a"), "[1,)").build(),
            context -> { throw new AssertionError("must not load"); }
        ));

        manager.initialize();

        assertTrue(manager.plugins().stream().allMatch(value -> value.state() == GltfPluginState.BLOCKED));
    }

    @Test
    void blocksRequiredVersionMismatchButIgnoresOptionalMismatch() {
        GltfPluginManager manager = manager();
        List<String> loaded = new ArrayList<>();
        manager.register("provider", plugin(
            GltfPluginMetadata.builder(id("provider", "api"), "2.0.0").build(),
            context -> loaded.add("provider")
        ));
        manager.register("suite", plugin(
            metadata("suite", "required").requires(id("provider", "api"), "[1.0,2.0)").build(),
            context -> loaded.add("required")
        ));
        manager.register("suite", plugin(
            metadata("suite", "optional").optional(id("provider", "api"), "[1.0,2.0)").build(),
            context -> loaded.add("optional")
        ));

        manager.initialize();

        assertEquals(List.of("provider", "optional"), loaded);
        assertEquals(GltfPluginState.BLOCKED, info(manager, "required").state());
        assertEquals(GltfPluginState.ACTIVE, info(manager, "optional").state());
    }

    @Test
    void rollsBackExtensionsAndRequiredDependentsWhenReadyFails() {
        GltfPluginManager manager = manager();
        manager.register("suite", new GltfPlugin() {
            @Override public GltfPluginMetadata metadata() {
                return GltfPluginManagerTest.metadata("suite", "base").build();
            }
            @Override public void onLoad(GltfPluginContext context) {
                context.register(TEXT, "temporary");
            }
            @Override public void onReady(GltfPluginContext context) {
                throw new IllegalStateException("broken");
            }
        });
        manager.register("suite", plugin(
            metadata("suite", "dependent").requires(id("suite", "base"), "[1,)").build(),
            context -> context.register(TEXT, "dependent")
        ));

        manager.initialize();

        assertTrue(manager.extensions(TEXT).isEmpty());
        assertEquals(GltfPluginState.FAILED, info(manager, "base").state());
        assertEquals(GltfPluginState.UNLOADED, info(manager, "dependent").state());
    }

    @Test
    void rejectsDuplicateIdsOwnerSpoofingAndLateRegistration() {
        GltfPluginManager manager = manager();
        GltfPlugin first = plugin(metadata("suite", "main").build(), context -> {});
        manager.register("suite", first);
        assertThrows(IllegalStateException.class, () -> manager.register("suite", first));
        assertThrows(IllegalArgumentException.class, () -> manager.register(
            "suite", plugin(metadata("other", "foreign").build(), context -> {})
        ));
        manager.initialize();
        assertThrows(IllegalStateException.class, () -> manager.register(
            "suite", plugin(metadata("suite", "late").build(), context -> {})
        ));
    }

    private static GltfPluginManager manager() {
        return new GltfPluginManager(Dist.CLIENT, false, LoggerFactory.getLogger("plugin-test"));
    }

    private static GltfPluginInfo info(GltfPluginManager manager, String path) {
        return manager.plugin(id("suite", path)).orElseThrow();
    }

    private static GltfPluginMetadata.Builder metadata(String namespace, String path) {
        return GltfPluginMetadata.builder(id(namespace, path), "1.0.0");
    }

    private static ResourceLocation id(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    private static GltfPlugin plugin(GltfPluginMetadata metadata, Loader loader) {
        return new GltfPlugin() {
            @Override public GltfPluginMetadata metadata() { return metadata; }
            @Override public void onLoad(GltfPluginContext context) throws Exception {
                loader.load(context);
            }
        };
    }

    @FunctionalInterface
    private interface Loader {
        void load(GltfPluginContext context) throws Exception;
    }
}
