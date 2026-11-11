package org.lytharalab.gfbs.gltf.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lytharalab.gfbs.gltf.GFBSglTF;
import org.lytharalab.gfbs.gltf.client.model.StaticGltfGeometryLoader;
import org.lytharalab.gfbs.gltf.client.model.GltfGeneratedMaterialPack;
import org.lytharalab.gfbs.gltf.client.render.GltfGpuCache;
import org.lytharalab.gfbs.gltf.client.resource.ClientGltfModels;

/** Keeps every Minecraft client class out of dedicated-server class loading. */
@Mod.EventBusSubscriber(modid = GFBSglTF.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GltfClientBootstrap {
    private GltfClientBootstrap() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientGltfModels.initialize();
            StaticGltfGeometryLoader.INSTANCE.initialize();
            MinecraftForge.EVENT_BUS.addListener(GltfGpuCache.getInstance()::onRenderTick);
        });
    }

    @SubscribeEvent
    public static void registerGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register("gltf", StaticGltfGeometryLoader.INSTANCE);
    }

    @SubscribeEvent
    public static void addGeneratedMaterialPack(net.minecraftforge.event.AddPackFindersEvent event) {
        GltfGeneratedMaterialPack.addPackFinder(event);
    }

    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(ClientGltfModels.getInstance());
        event.registerReloadListener(StaticGltfGeometryLoader.INSTANCE);
    }
}
