package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.TickEvent;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

public final class GltfGpuCache {
    private static final GltfGpuCache INSTANCE = new GltfGpuCache();
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<GltfAsset, GltfGpuModel> models = new IdentityHashMap<>();
    private static long frame;
    private static long geometryBytes;

    private GltfGpuCache() {
    }

    public static GltfGpuCache getInstance() { return INSTANCE; }
    static long frame() { return frame; }
    static boolean hasGeometrySpace(long bytes) { return geometryBytes + bytes <= 512L * 1024 * 1024; }
    static void geometryAllocated(long bytes) { geometryBytes += bytes; }

    public static void prepareScene(GltfAsset asset, int scene) {
        java.util.Objects.checkIndex(scene, asset.scenes().size());
        GltfGpuModel.prepareScene(asset, scene);
    }

    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++frame % 60 != 0) return;
        var entries = models.values().iterator();
        while (entries.hasNext()) {
            GltfGpuModel model = entries.next();
            if (frame - model.lastFrame > 180) {
                entries.remove();
                deleteSafely(model);
            } else {
                model.releaseCold(frame);
            }
        }
    }

    GltfGpuModel get(GltfAsset asset) {
        RenderSystem.assertOnRenderThread();
        GltfGpuModel model = models.computeIfAbsent(asset, GltfGpuModel::new);
        model.lastFrame = frame;
        return model;
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
