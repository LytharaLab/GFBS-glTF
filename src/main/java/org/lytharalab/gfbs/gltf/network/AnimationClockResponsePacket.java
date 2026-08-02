package org.lytharalab.gfbs.gltf.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server-to-client response containing a logical tick and a server monotonic timestamp. */
public record AnimationClockResponsePacket(long nonce, long clientSendNanos,
                                           long serverGameTick, long serverNanos) {
    static void encode(AnimationClockResponsePacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarLong(packet.nonce);
        buffer.writeLong(packet.clientSendNanos);
        buffer.writeVarLong(packet.serverGameTick);
        buffer.writeLong(packet.serverNanos);
    }

    static AnimationClockResponsePacket decode(FriendlyByteBuf buffer) {
        return new AnimationClockResponsePacket(
            buffer.readVarLong(),
            buffer.readLong(),
            buffer.readVarLong(),
            buffer.readLong()
        );
    }

    static void handle(AnimationClockResponsePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.receive(packet));
        context.setPacketHandled(true);
    }

    private static final class ClientHandler {
        private ClientHandler() {
        }

        private static void receive(AnimationClockResponsePacket packet) {
            org.lytharalab.gfbs.gltf.client.sync.ClientAnimationSync.receiveClockSample(
                packet.nonce,
                packet.clientSendNanos,
                packet.serverGameTick,
                packet.serverNanos
            );
        }
    }
}
