package org.lytharalab.gfbs.gltf.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.lytharalab.gfbs.gltf.GFBSglTF;

import java.util.Objects;

public final class GltfNetwork {
    /** 1.2.0 adds bidirectional clock synchronization and a dispatch-tick field. */
    private static final String VERSION = "2";
    private static volatile SimpleChannel channel;

    private GltfNetwork() {
    }

    public static synchronized void initialize() {
        if (channel != null) {
            return;
        }
        channel = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(GFBSglTF.MODID, "animation"),
            () -> VERSION,
            VERSION::equals,
            VERSION::equals
        );
        channel.messageBuilder(AnimationStatePacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(AnimationStatePacket::encode)
            .decoder(AnimationStatePacket::decode)
            .consumerMainThread(AnimationStatePacket::handle)
            .add();
        channel.messageBuilder(AnimationClockRequestPacket.class, 1, NetworkDirection.PLAY_TO_SERVER)
            .encoder(AnimationClockRequestPacket::encode)
            .decoder(AnimationClockRequestPacket::decode)
            .consumerMainThread(AnimationClockRequestPacket::handle)
            .add();
        channel.messageBuilder(AnimationClockResponsePacket.class, 2, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(AnimationClockResponsePacket::encode)
            .decoder(AnimationClockResponsePacket::decode)
            .consumerMainThread(AnimationClockResponsePacket::handle)
            .add();
    }

    public static void send(ServerPlayer player, AnimationStatePacket packet) {
        sendPacket(player, packet);
    }

    public static void send(ServerPlayer player, AnimationClockResponsePacket packet) {
        sendPacket(player, packet);
    }

    public static void sendToServer(AnimationClockRequestPacket packet) {
        Objects.requireNonNull(packet, "packet");
        currentChannel().sendToServer(packet);
    }

    private static void sendPacket(ServerPlayer player, Object packet) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(packet, "packet");
        currentChannel().send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private static SimpleChannel currentChannel() {
        SimpleChannel current = channel;
        if (current == null) {
            throw new IllegalStateException("GFBS:glTF network channel is not initialized");
        }
        return current;
    }
}
