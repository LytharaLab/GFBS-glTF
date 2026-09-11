package org.lytharalab.gfbs.gltf;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPlugins;
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

    @SubscribeEvent
    public static void onInterModProcess(InterModProcessEvent event) {
        event.getIMCStream(GltfPlugins.REGISTER_IMC_METHOD::equals).forEach(message -> {
            try {
                GltfPlugins.acceptInterModRegistration(
                    message.senderModId(), message.messageSupplier().get()
                );
            } catch (RuntimeException | Error failure) {
                GFBSglTF.LOGGER.error(
                    "Rejected GFBS:glTF plugin registration from mod {}",
                    message.senderModId(), failure
                );
            }
        });
    }

    @SubscribeEvent
    public static void onLoadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(GltfPlugins::initialize);
    }
}
