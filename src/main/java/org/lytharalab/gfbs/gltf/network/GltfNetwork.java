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
    private static final String VERSION = "1";
    private static volatile SimpleChannel channel;

    private GltfNetwork() {
    }

    public static synchronized void initialize() {
        if (channel != null) return;
        channel = NetworkRegistry.newSimpleChannel(ResourceLocation.fromNamespaceAndPath(GFBSglTF.MODID, "animation"),
            () -> VERSION, VERSION::equals, VERSION::equals);
        channel.messageBuilder(AnimationStatePacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(AnimationStatePacket::encode).decoder(AnimationStatePacket::decode)
            .consumerMainThread(AnimationStatePacket::handle).add();
    }

    public static void send(ServerPlayer player, AnimationStatePacket packet) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(packet, "packet");
        SimpleChannel current = channel;
        if (current == null) throw new IllegalStateException("GFBS:glTF network channel is not initialized");
        current.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
