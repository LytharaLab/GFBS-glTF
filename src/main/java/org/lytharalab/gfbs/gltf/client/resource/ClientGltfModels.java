package org.lytharalab.gfbs.gltf.client.resource;

import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.lytharalab.gfbs.gltf.api.client.GltfModelManager;
import org.lytharalab.gfbs.gltf.api.io.ModelImporters;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.client.render.GltfGpuCache;
import org.slf4j.Logger;

import java.io.FileNotFoundException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientGltfModels implements GltfModelManager, ResourceManagerReloadListener {
    private static final ClientGltfModels INSTANCE = new ClientGltfModels();
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ConcurrentHashMap<ResourceLocation, LoadEntry> assets = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private volatile ResourceManager resources;

    private ClientGltfModels() {
    }

    public static ClientGltfModels getInstance() { return INSTANCE; }

    public static void initialize() {
        INSTANCE.resources = Minecraft.getInstance().getResourceManager();
    }

    public void reload(ResourceManager manager) {
        this.resources = Objects.requireNonNull(manager, "manager");
        generation.incrementAndGet();
        assets.values().forEach(entry -> entry.future().cancel(false));
        assets.clear();
        GltfGpuCache.getInstance().clear();
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        reload(manager);
    }

    @Override
    public CompletableFuture<GltfAsset> load(ResourceLocation location) {
        Objects.requireNonNull(location, "location");
        while (true) {
            ResourceManager currentResources = resources;
            if (currentResources == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Client glTF model manager is not initialized"));
            }
            long expectedGeneration = generation.get();
            LoadEntry entry = assets.compute(location, (id, existing) -> {
                if (existing != null && existing.generation() == expectedGeneration) return existing;
                if (existing != null) existing.future().cancel(false);
                return new LoadEntry(expectedGeneration, createLoadFuture(id, currentResources, expectedGeneration));
            });
            if (generation.get() == expectedGeneration) return entry.future();
            if (assets.remove(location, entry)) entry.future().cancel(false);
        }
    }

    private CompletableFuture<GltfAsset> createLoadFuture(ResourceLocation id, ResourceManager manager,
                                                            long expectedGeneration) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                GltfAsset asset = ModelImporters.load(id, child -> manager.getResource(child)
                    .orElseThrow(() -> new FileNotFoundException(child.toString())).open());
                if (generation.get() != expectedGeneration) {
                    throw new CancellationException("Client resources were reloaded while loading " + id);
                }
                return asset;
            } catch (CancellationException exception) {
                throw exception;
            } catch (Exception exception) {
                if (generation.get() != expectedGeneration) {
                    throw new CancellationException("Client resources were reloaded while loading " + id);
                }
                LOGGER.error("Could not load glTF asset {}", id, exception);
                throw new CompletionException(exception);
            }
        }, Util.backgroundExecutor());
    }

    @Override
    public Optional<GltfAsset> getIfLoaded(ResourceLocation location) {
        LoadEntry entry = assets.get(location);
        if (entry == null || entry.generation() != generation.get()) return Optional.empty();
        CompletableFuture<GltfAsset> future = entry.future();
        if (!future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) return Optional.empty();
        return Optional.ofNullable(future.getNow(null));
    }

    @Override
    public void invalidate(ResourceLocation location) {
        LoadEntry removed = assets.remove(location);
        if (removed != null) removed.future().cancel(false);
        GltfGpuCache.getInstance().remove(location);
    }

    private record LoadEntry(long generation, CompletableFuture<GltfAsset> future) {
    }
}
