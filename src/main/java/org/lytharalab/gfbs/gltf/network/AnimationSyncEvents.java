package org.lytharalab.gfbs.gltf.network;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lytharalab.gfbs.gltf.GFBSglTF;

@Mod.EventBusSubscriber(modid = GFBSglTF.MODID)
public final class AnimationSyncEvents {
    private AnimationSyncEvents() {
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            ServerAnimationManager.get(player.getServer()).sendSnapshot(player);
        }
    }

    @SubscribeEvent
    public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            ServerAnimationManager.get(player.getServer()).sendSnapshot(player);
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        ServerAnimationManager.remove(event.getServer());
    }
}
