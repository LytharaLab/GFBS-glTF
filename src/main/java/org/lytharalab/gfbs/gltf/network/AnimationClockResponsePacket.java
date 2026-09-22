package org.lytharalab.gfbs.gltf.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server-to-client response carrying the server's monotonic timeline position in seconds.
 *
 * <p>Because both sides derive time from {@link System#nanoTime()}, the estimator only has to solve
 * for an offset; the previous design additionally had to infer a tick rate from game ticks.</p>
 */
public record AnimationClockResponsePacket(long nonce, long clientSendNanos, double serverSeconds) {
    static void encode(AnimationClockResponsePacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarLong(packet.nonce);
        buffer.writeLong(packet.clientSendNanos);
        buffer.writeDouble(packet.serverSeconds);
    }

    static AnimationClockResponsePacket decode(FriendlyByteBuf buffer) {
        return new AnimationClockResponsePacket(
            buffer.readVarLong(),
            buffer.readLong(),
            buffer.readDouble()
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
                packet.serverSeconds
            );
        }
    }
}
