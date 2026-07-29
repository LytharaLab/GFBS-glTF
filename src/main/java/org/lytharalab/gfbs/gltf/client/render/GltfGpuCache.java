package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

public final class GltfGpuCache {
    private static final GltfGpuCache INSTANCE = new GltfGpuCache();
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<GltfAsset, GltfGpuModel> models = new IdentityHashMap<>();

    private GltfGpuCache() {
    }

    public static GltfGpuCache getInstance() { return INSTANCE; }

    GltfGpuModel get(GltfAsset asset) {
        RenderSystem.assertOnRenderThread();
        return models.computeIfAbsent(asset, GltfGpuModel::new);
    }

    public void remove(ResourceLocation id) {
        runOnRenderThread(() -> {
            Iterator<Map.Entry<GltfAsset, GltfGpuModel>> iterator = models.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<GltfAsset, GltfGpuModel> entry = iterator.next();
                if (entry.getKey().id().equals(id)) {
                    GltfGpuModel model = entry.getValue();
                    iterator.remove();
                    deleteSafely(model);
                }
            }
        });
    }

    public void clear() {
        runOnRenderThread(() -> {
            GltfGpuModel[] staleModels = models.values().toArray(GltfGpuModel[]::new);
            models.clear();
            for (GltfGpuModel model : staleModels) deleteSafely(model);
            GltfOcclusionCuller.INSTANCE.dispose();
        });
    }


    private static void deleteSafely(GltfGpuModel model) {
        try {
            model.delete();
        } catch (RuntimeException | Error failure) {
            LOGGER.error("Could not completely release GPU resources for glTF asset {}", model.asset.id(), failure);
        }
    }

    private static void runOnRenderThread(Runnable runnable) {
        if (RenderSystem.isOnRenderThread()) runnable.run(); else RenderSystem.recordRenderCall(runnable::run);
    }
}
