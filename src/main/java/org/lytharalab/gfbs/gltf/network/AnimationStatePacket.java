package org.lytharalab.gfbs.gltf.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.lytharalab.gfbs.gltf.api.animation.LoopMode;
import org.lytharalab.gfbs.gltf.api.sync.*;

import java.util.function.Supplier;

public record AnimationStatePacket(SyncedAnimationState state) {
    static void encode(AnimationStatePacket packet, FriendlyByteBuf buffer) {
        SyncedAnimationState state = packet.state;
        buffer.writeResourceLocation(state.target().dimension()); buffer.writeEnum(state.target().kind());
        buffer.writeUtf(state.target().id(), 512); buffer.writeUtf(state.animation(), 256);
        buffer.writeVarLong(state.serverStartTick()); buffer.writeFloat(state.initialSeconds());
        buffer.writeFloat(state.speed()); buffer.writeEnum(state.loopMode()); buffer.writeFloat(state.transitionSeconds());
        buffer.writeBoolean(state.playing()); buffer.writeBoolean(state.stopped()); buffer.writeVarLong(state.sequence());
    }

    static AnimationStatePacket decode(FriendlyByteBuf buffer) {
        AnimationTargetKey target = new AnimationTargetKey(buffer.readResourceLocation(),
            buffer.readEnum(AnimationTargetKey.Kind.class), buffer.readUtf(512));
        return new AnimationStatePacket(new SyncedAnimationState(target, buffer.readUtf(256),
            buffer.readVarLong(), buffer.readFloat(), buffer.readFloat(), buffer.readEnum(LoopMode.class),
            buffer.readFloat(), buffer.readBoolean(), buffer.readBoolean(), buffer.readVarLong()));
    }

    static void handle(AnimationStatePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> ClientHandler.receive(packet));
        context.setPacketHandled(true);
    }

    private static final class ClientHandler {
        private ClientHandler() {
        }

        private static void receive(AnimationStatePacket packet) {
            org.lytharalab.gfbs.gltf.client.sync.ClientAnimationSync.receive(packet.state);
        }
    }
}
