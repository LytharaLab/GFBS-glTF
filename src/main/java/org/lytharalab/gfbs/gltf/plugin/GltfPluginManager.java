package org.lytharalab.gfbs.gltf.plugin;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.forgespi.language.MavenVersionAdapter;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.lytharalab.gfbs.gltf.api.io.ModelImporter;
import org.lytharalab.gfbs.gltf.api.io.ModelImporters;
import org.lytharalab.gfbs.gltf.api.plugin.GltfExtensionEntry;
import org.lytharalab.gfbs.gltf.api.plugin.GltfExtensionPoint;
import org.lytharalab.gfbs.gltf.api.plugin.GltfExtensionPoints;
import org.lytharalab.gfbs.gltf.api.plugin.GltfExtensionRegistration;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPlugin;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPluginContext;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPluginDependency;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPluginInfo;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPluginMetadata;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPluginState;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Internal deterministic plugin host. Public integrations use the api.plugin facade. */
public final class GltfPluginManager {
    private final Dist dist;
    private final boolean production;
    private final Logger logger;
    private final Map<ResourceLocation, Container> plugins = new LinkedHashMap<>();
    private final Map<ResourceLocation, ExtensionBucket> extensionBuckets = new ConcurrentHashMap<>();
    private long nextSequence;
    private boolean registrationClosed;

    public GltfPluginManager(Dist dist, boolean production, Logger logger) {
        this.dist = Objects.requireNonNull(dist, "dist");
        this.production = production;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public synchronized void register(String ownerModId, GltfPlugin plugin) {
        if (registrationClosed) {
            throw new IllegalStateException("GFBS:glTF plugin registration is already closed");
        }
        ownerModId = requireOwner(ownerModId);
        Objects.requireNonNull(plugin, "plugin");
        GltfPluginMetadata metadata = Objects.requireNonNull(plugin.metadata(), "plugin.metadata()");
        if (!metadata.id().getNamespace().equals(ownerModId)) {
            throw new IllegalArgumentException(
                "Plugin " + metadata.id() + " must use its owner mod id '" + ownerModId + "' as namespace"
            );
        }
        if (plugins.containsKey(metadata.id())) {
            throw new IllegalStateException("Duplicate GFBS:glTF plugin id " + metadata.id());
        }
        validateDependencies(metadata);
        plugins.put(metadata.id(), new Container(ownerModId, plugin, metadata, nextSequence++));
    }

    public synchronized void initialize() {
        if (registrationClosed) return;
        registrationClosed = true;

        List<Container> ordered = resolveOrder();
        int loadOrder = 0;
        for (Container container : ordered) {
            container.loadOrder = loadOrder++;
            if (hasUnavailableRequiredDependency(container)) {
                block(container, "A required plugin failed or was blocked during startup");
                continue;
            }
            load(container);
        }

        for (Container container : ordered) {
            if (container.state != GltfPluginState.ACTIVE) continue;
            try {
                container.plugin.onReady(container.context);
            } catch (Throwable failure) {
                fail(container, "onReady failed", failure);
                cascadeRequiredDependents(container.metadata.id(), "Required plugin failed during onReady");
            }
        }

        long active = plugins.values().stream()
            .filter(plugin -> plugin.state == GltfPluginState.ACTIVE).count();
        long unavailable = plugins.size() - active;
        logger.info("GFBS:glTF plugin host initialized {} plugin(s); {} unavailable", active, unavailable);
    }

    public synchronized List<GltfPluginInfo> plugins() {
        return plugins.values().stream().map(Container::info).toList();
    }

    public synchronized Optional<GltfPluginInfo> plugin(ResourceLocation id) {
        Container container = plugins.get(Objects.requireNonNull(id, "id"));
        return container == null ? Optional.empty() : Optional.of(container.info());
    }

    @SuppressWarnings("unchecked")
    public <T> List<GltfExtensionEntry<T>> extensions(GltfExtensionPoint<T> point) {
        Objects.requireNonNull(point, "point");
        ExtensionBucket bucket = extensionBuckets.get(point.id());
        if (bucket == null) return List.of();
        bucket.validate(point);
        return (List<GltfExtensionEntry<T>>) (List<?>) bucket.snapshot;
    }

    public synchronized boolean disable(ResourceLocation id, String reason) {
        Objects.requireNonNull(id, "id");
        Container target = plugins.get(id);
        if (target == null || target.state != GltfPluginState.ACTIVE) return false;
        cascadeRequiredDependents(id, "Required plugin " + id + " was disabled");
        unload(target, reason == null || reason.isBlank() ? "Disabled through API" : reason);
        return true;
    }

    public synchronized void shutdown() {
        List<Container> ordered = new ArrayList<>(plugins.values());
        ordered.sort(Comparator.comparingInt((Container value) -> value.loadOrder).reversed());
        for (Container container : ordered) {
            if (container.state == GltfPluginState.ACTIVE) unload(container, "Plugin host shutdown");
        }
    }

    private List<Container> resolveOrder() {
        for (Container container : plugins.values()) {
            for (GltfPluginDependency dependency : container.metadata.dependencies()) {
                Container target = plugins.get(dependency.id());
                if (!dependency.required()) continue;
                if (target == null) {
                    block(container, "Missing required plugin " + dependency.id()
                        + " " + dependency.versionRange());
                    break;
                }
                if (!matches(dependency, target)) {
                    block(container, "Plugin " + dependency.id() + " " + target.metadata.version()
                        + " does not satisfy " + dependency.versionRange());
                    break;
                }
            }
        }

        boolean changed;
        do {
            changed = false;
            for (Container container : plugins.values()) {
                if (container.state != GltfPluginState.REGISTERED) continue;
                for (GltfPluginDependency dependency : container.metadata.dependencies()) {
                    if (!dependency.required()) continue;
                    Container target = plugins.get(dependency.id());
                    if (target != null && target.state == GltfPluginState.BLOCKED) {
                        block(container, "Required plugin " + dependency.id() + " is blocked");
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);

        Map<Container, Integer> indegree = new HashMap<>();
        Map<Container, List<Container>> outgoing = new HashMap<>();
        for (Container container : plugins.values()) {
            if (container.state == GltfPluginState.REGISTERED) indegree.put(container, 0);
        }
        for (Container container : indegree.keySet()) {
            for (GltfPluginDependency dependency : container.metadata.dependencies()) {
                Container target = plugins.get(dependency.id());
                if (target == null || !indegree.containsKey(target) || !matches(dependency, target)) continue;
                outgoing.computeIfAbsent(target, ignored -> new ArrayList<>()).add(container);
                indegree.put(container, indegree.get(container) + 1);
            }
        }

        PriorityQueue<Container> ready = new PriorityQueue<>(Comparator.comparingLong(value -> value.sequence));
        indegree.forEach((container, degree) -> {
            if (degree == 0) ready.add(container);
        });
        List<Container> result = new ArrayList<>();
        while (!ready.isEmpty()) {
            Container next = ready.remove();
            result.add(next);
            for (Container dependent : outgoing.getOrDefault(next, List.of())) {
                int degree = indegree.compute(dependent, (ignored, old) -> old - 1);
                if (degree == 0) ready.add(dependent);
            }
        }

        if (result.size() != indegree.size()) {
            Set<Container> resolved = Set.copyOf(result);
            for (Container container : indegree.keySet()) {
                if (!resolved.contains(container)) {
                    block(container, "Plugin dependency cycle or dependency on a cycle");
                }
            }
        }
        return result;
    }

    private void load(Container container) {
        container.context = new Context(container);
        container.state = GltfPluginState.LOADING;
        try {
            container.plugin.onLoad(container.context);
            container.state = GltfPluginState.ACTIVE;
            logger.info("Loaded GFBS:glTF plugin {} {} from mod {}",
                container.metadata.id(), container.metadata.version(), container.ownerModId);
        } catch (Throwable failure) {
            fail(container, "onLoad failed", failure);
        }
    }

    private boolean hasUnavailableRequiredDependency(Container container) {
        for (GltfPluginDependency dependency : container.metadata.dependencies()) {
            if (!dependency.required()) continue;
            Container target = plugins.get(dependency.id());
            if (target == null || target.state != GltfPluginState.ACTIVE) return true;
        }
        return false;
    }

    private void cascadeRequiredDependents(ResourceLocation dependency, String reason) {
        ArrayDeque<ResourceLocation> queue = new ArrayDeque<>();
        Set<ResourceLocation> visited = new HashSet<>();
        queue.add(dependency);
        while (!queue.isEmpty()) {
            ResourceLocation current = queue.removeFirst();
            if (!visited.add(current)) continue;
            List<Container> reverse = new ArrayList<>(plugins.values());
            reverse.sort(Comparator.comparingInt((Container value) -> value.loadOrder).reversed());
            for (Container candidate : reverse) {
                if (candidate.state != GltfPluginState.ACTIVE) continue;
                boolean requires = candidate.metadata.dependencies().stream()
                    .anyMatch(value -> value.required() && value.id().equals(current));
                if (requires) {
                    queue.addLast(candidate.metadata.id());
                    unload(candidate, reason);
                }
            }
        }
    }

    private void fail(Container container, String stage, Throwable failure) {
        container.state = GltfPluginState.FAILED;
        cleanup(container, true);
        container.detail = stage + ": " + message(failure);
        logger.error("GFBS:glTF plugin {} {}", container.metadata.id(), stage, failure);
    }

    private void unload(Container container, String reason) {
        container.state = GltfPluginState.UNLOADED;
        cleanup(container, true);
        container.detail = reason;
        logger.info("Unloaded GFBS:glTF plugin {}: {}", container.metadata.id(), reason);
    }

    private void cleanup(Container container, boolean invokePlugin) {
        if (invokePlugin && container.context != null) {
            try {
                container.plugin.onUnload(container.context);
            } catch (Throwable failure) {
                logger.error("GFBS:glTF plugin {} failed during onUnload",
                    container.metadata.id(), failure);
            }
        }
        for (int i = container.registrations.size() - 1; i >= 0; i--) {
            container.registrations.get(i).close();
        }
        container.registrations.clear();
    }

    private void block(Container container, String detail) {
        container.state = GltfPluginState.BLOCKED;
        container.detail = detail;
        logger.error("Blocked GFBS:glTF plugin {}: {}", container.metadata.id(), detail);
    }

    private boolean matches(GltfPluginDependency dependency, Container target) {
        try {
            VersionRange range = MavenVersionAdapter.createFromVersionSpec(dependency.versionRange());
            return range.containsVersion(new DefaultArtifactVersion(target.metadata.version()));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                "Invalid version range '" + dependency.versionRange() + "' in dependency on "
                    + dependency.id(), failure
            );
        }
    }

    private static void validateDependencies(GltfPluginMetadata metadata) {
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (GltfPluginDependency dependency : metadata.dependencies()) {
            if (!ids.add(dependency.id())) {
                throw new IllegalArgumentException(
                    "Plugin " + metadata.id() + " declares dependency " + dependency.id() + " more than once"
                );
            }
            try {
                MavenVersionAdapter.createFromVersionSpec(dependency.versionRange());
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException(
                    "Invalid version range '" + dependency.versionRange() + "' for " + dependency.id(), failure
                );
            }
        }
    }

    private static String requireOwner(String ownerModId) {
        Objects.requireNonNull(ownerModId, "ownerModId");
        try {
            if (!ResourceLocation.fromNamespaceAndPath(ownerModId, "plugin")
                .getNamespace().equals(ownerModId)) {
                throw new IllegalArgumentException("Invalid owner mod id: " + ownerModId);
            }
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid owner mod id: " + ownerModId);
        }
        return ownerModId;
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }

    private synchronized <T> ManagedRegistration<T> registerExtension(
        Container owner, GltfExtensionPoint<T> point, T extension, int order, Runnable closeAction
    ) {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(extension, "extension");
        if (!point.type().isInstance(extension)) {
            throw new IllegalArgumentException(
                extension.getClass().getName() + " is not a " + point.type().getName()
            );
        }
        if (owner.state != GltfPluginState.LOADING && owner.state != GltfPluginState.ACTIVE) {
            throw new IllegalStateException("Plugin " + owner.metadata.id() + " is not active");
        }
        ExtensionBucket bucket = extensionBuckets.computeIfAbsent(
            point.id(), ignored -> new ExtensionBucket(point)
        );
        bucket.validate(point);
        if (!point.multiple() && bucket.entries.stream().anyMatch(value -> value.active)) {
            throw new IllegalStateException("Extension point " + point.id() + " accepts one provider");
        }
        ManagedRegistration<T> registration = new ManagedRegistration<>(
            point, new GltfExtensionEntry<>(owner.metadata.id(), extension, order),
            owner, bucket, nextSequence++, closeAction
        );
        bucket.entries.add(registration);
        bucket.entries.sort(Comparator
            .comparingInt((ManagedRegistration<?> value) -> value.entry.order())
            .thenComparingInt(value -> value.owner.loadOrder)
            .thenComparingLong(value -> value.sequence));
        bucket.rebuildSnapshot();
        owner.registrations.add(registration);
        return registration;
    }

    private final class Context implements GltfPluginContext {
        private final Container owner;

        private Context(Container owner) {
            this.owner = owner;
        }

        @Override public String ownerModId() { return owner.ownerModId; }
        @Override public GltfPluginMetadata metadata() { return owner.metadata; }
        @Override public Dist dist() { return dist; }
        @Override public boolean production() { return production; }
        @Override public Logger logger() { return logger; }

        @Override
        public <T> GltfExtensionRegistration<T> register(
            GltfExtensionPoint<T> point, T extension, int order
        ) {
            return registerExtension(owner, point, extension, order, () -> {});
        }

        @Override
        public GltfExtensionRegistration<ModelImporter> registerImporter(ModelImporter importer) {
            Objects.requireNonNull(importer, "importer");
            ModelImporters.register(importer);
            try {
                return registerExtension(
                    owner, GltfExtensionPoints.MODEL_IMPORTERS, importer, 0,
                    () -> ModelImporters.unregister(importer)
                );
            } catch (RuntimeException | Error failure) {
                ModelImporters.unregister(importer);
                throw failure;
            }
        }

        @Override
        public <T> List<GltfExtensionEntry<T>> extensions(GltfExtensionPoint<T> point) {
            return GltfPluginManager.this.extensions(point);
        }
    }

    private final class ManagedRegistration<T> implements GltfExtensionRegistration<T> {
        private final GltfExtensionPoint<T> point;
        private final GltfExtensionEntry<T> entry;
        private final Container owner;
        private final ExtensionBucket bucket;
        private final long sequence;
        private final Runnable closeAction;
        private boolean active = true;

        private ManagedRegistration(GltfExtensionPoint<T> point, GltfExtensionEntry<T> entry,
                                    Container owner, ExtensionBucket bucket, long sequence,
                                    Runnable closeAction) {
            this.point = point;
            this.entry = entry;
            this.owner = owner;
            this.bucket = bucket;
            this.sequence = sequence;
            this.closeAction = closeAction;
        }

        @Override public GltfExtensionPoint<T> point() { return point; }
        @Override public GltfExtensionEntry<T> entry() { return entry; }
        @Override public boolean active() { return active; }

        @Override
        public void close() {
            synchronized (GltfPluginManager.this) {
                if (!active) return;
                active = false;
                bucket.entries.remove(this);
                if (bucket.entries.isEmpty()) {
                    extensionBuckets.remove(point.id(), bucket);
                } else {
                    bucket.rebuildSnapshot();
                }
                owner.registrations.remove(this);
                try {
                    closeAction.run();
                } catch (RuntimeException failure) {
                    logger.error("Could not remove extension {} owned by {}",
                        point.id(), owner.metadata.id(), failure);
                }
            }
        }

    }

    private final class ExtensionBucket {
        final Class<?> type;
        final boolean multiple;
        final List<ManagedRegistration<?>> entries = new ArrayList<>();
        volatile List<GltfExtensionEntry<?>> snapshot = List.of();

        ExtensionBucket(GltfExtensionPoint<?> point) {
            type = point.type();
            multiple = point.multiple();
        }

        void validate(GltfExtensionPoint<?> point) {
            if (type != point.type() || multiple != point.multiple()) {
                throw new IllegalArgumentException(
                    "Extension point " + point.id() + " was already defined as " + type.getName()
                );
            }
        }

        void rebuildSnapshot() {
            List<GltfExtensionEntry<?>> values = new ArrayList<>(entries.size());
            for (ManagedRegistration<?> registration : entries) {
                if (registration.active) values.add(registration.entry);
            }
            snapshot = List.copyOf(values);
        }
    }

    private final class Container {
        final String ownerModId;
        final GltfPlugin plugin;
        final GltfPluginMetadata metadata;
        final long sequence;
        final List<ManagedRegistration<?>> registrations = new ArrayList<>();
        GltfPluginState state = GltfPluginState.REGISTERED;
        Context context;
        String detail;
        int loadOrder = Integer.MAX_VALUE;

        Container(String ownerModId, GltfPlugin plugin, GltfPluginMetadata metadata, long sequence) {
            this.ownerModId = ownerModId;
            this.plugin = plugin;
            this.metadata = metadata;
            this.sequence = sequence;
        }

        GltfPluginInfo info() {
            return new GltfPluginInfo(ownerModId, metadata, state, detail, loadOrder);
        }
    }
}
