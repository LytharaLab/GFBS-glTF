package org.lytharalab.gfbs.gltf.client.sync;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lytharalab.gfbs.gltf.GFBSglTF;

/**
 * Client lifecycle glue for the mod-owned synchronization heartbeat.
 *
 * <p>The heartbeat is driven by {@code RenderLevelStageEvent.Stage.AFTER_ENTITIES} — one call per
 * rendered frame — instead of {@code TickEvent.ClientTickEvent}. Frame-driven execution is what the
 * controller actually wants: it runs at display rate, it stops when nothing is rendered, and it is
 * immune to tick counter resets.</p>
 */
@Mod.EventBusSubscriber(modid = GFBSglTF.MODID, value = Dist.CLIENT)
public final class ClientAnimationSyncEvents {
    private ClientAnimationSyncEvents() {
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            ClientHeartbeat.beginFrame();
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel level) {
            ClientAnimationSync.clearDimension(level.dimension().location());
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientAnimationSync.clear();
    }
}
