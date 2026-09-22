package org.lytharalab.gfbs.gltf.network;

import net.minecraft.server.MinecraftServer;
import org.lytharalab.gfbs.gltf.core.time.SessionClock;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Server-side authoritative monotonic timeline, owned by GFBS : glTF.
 *
 * <p>The clock is a plain {@link SessionClock}: it never reads {@code Level#getGameTime()}, so
 * synchronized animations survive world-time resets, {@code /time set}, TPS drops and tick freezes.
 * One instance exists per {@link MinecraftServer} and is dropped when the server stops.</p>
 */
public final class ServerClock {
    private static final Map<MinecraftServer, ServerClock> INSTANCES =
        Collections.synchronizedMap(new WeakHashMap<>());

    private final WeakReference<MinecraftServer> server;
    private final SessionClock clock = new SessionClock();

    private ServerClock(MinecraftServer server) {
        this.server = new WeakReference<>(server);
    }

    public static ServerClock get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        synchronized (INSTANCES) {
            return INSTANCES.computeIfAbsent(server, ServerClock::new);
        }
    }

    public static void remove(MinecraftServer server) {
        synchronized (INSTANCES) {
            INSTANCES.remove(server);
        }
    }

    /** Authoritative server timeline in monotonic seconds. */
    public double nowSeconds() {
        MinecraftServer current = server.get();
        if (current == null) {
            throw new IllegalStateException("Minecraft server is no longer available");
        }
        return clock.nowSeconds();
    }

    /** Restarts the timeline; used by tests and by the server lifecycle when a session restarts. */
    public void reset() {
        clock.reset();
    }
}
