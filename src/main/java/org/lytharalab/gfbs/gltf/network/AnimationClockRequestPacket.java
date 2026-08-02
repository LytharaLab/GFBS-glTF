package org.lytharalab.gfbs.gltf.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client-to-server probe used to estimate RTT and the authoritative server tick clock. */
public record AnimationClockRequestPacket(long nonce, long clientSendNanos) {
    static void encode(AnimationClockRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarLong(packet.nonce);
        buffer.writeLong(packet.clientSendNanos);
    }

    static AnimationClockRequestPacket decode(FriendlyByteBuf buffer) {
        return new AnimationClockRequestPacket(buffer.readVarLong(), buffer.readLong());
    }

    static void handle(AnimationClockRequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            GltfNetwork.send(sender, new AnimationClockResponsePacket(
                packet.nonce,
                packet.clientSendNanos,
                sender.serverLevel().getGameTime(),
                System.nanoTime()
            ));
        }
        context.setPacketHandled(true);
    }
}
