package org.lytharalab.gfbs.gltf;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.lytharalab.gfbs.gltf.network.GltfNetwork;

/** Common-side bootstrap kept separate from the original template entry point. */
@Mod.EventBusSubscriber(modid = GFBSglTF.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GltfCommonBootstrap {
    private GltfCommonBootstrap() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(GltfNetwork::initialize);
    }
}
