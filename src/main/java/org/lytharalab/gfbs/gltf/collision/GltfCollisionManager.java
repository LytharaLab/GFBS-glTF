package org.lytharalab.gfbs.gltf.collision;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lytharalab.gfbs.gltf.api.collision.ClippableSource;
import org.lytharalab.gfbs.gltf.api.collision.CollisionSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class GltfCollisionManager {
    private static final CopyOnWriteArrayList<CollisionSource> SOURCES = new CopyOnWriteArrayList<>();
    private static volatile ExecutorService executor;

    private GltfCollisionManager() {
    }

    public static void register(CollisionSource source) {
        if (source != null) SOURCES.addIfAbsent(source);
    }

    public static void unregister(CollisionSource source) {
        SOURCES.remove(source);
    }

    public static List<CollisionSource> sources() {
        return List.copyOf(SOURCES);
    }

    public static List<VoxelShape> appendShapes(Level level, Entity entity, AABB area,
                                                 List<VoxelShape> base) {
        if (SOURCES.isEmpty()) return base;
        List<VoxelShape> extra = new ArrayList<>();
        for (CollisionSource source : SOURCES) {
            try {
                if (!source.isActive()) continue;
                Level owner = source.level();
                if (owner != null && owner != level) continue;
                source.collect(area, extra);
            } catch (RuntimeException ignored) {
            }
        }
        if (extra.isEmpty()) return base;
        List<VoxelShape> merged = new ArrayList<>(base.size() + extra.size());
        merged.addAll(base);
        merged.addAll(extra);
        return merged;
    }

    public static Vec3 clip(Level level, Vec3 from, Vec3 to) {
        Vec3 closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (CollisionSource source : SOURCES) {
            if (!(source instanceof ClippableSource clippable) || !source.isActive()) continue;
            Level owner = source.level();
            if (owner != null && owner != level) continue;
            Vec3 hit = clippable.clip(from, to);
            if (hit == null) continue;
            double distance = from.distanceToSqr(hit);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = hit;
            }
        }
        return closest;
    }

    static ExecutorService executor() {
        ExecutorService current = executor;
        if (current != null) return current;
        synchronized (GltfCollisionManager.class) {
            if (executor == null) {
                AtomicInteger sequence = new AtomicInteger();
                executor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(
                        runnable, "GFBS-glTF-Collision-" + sequence.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                });
            }
            return executor;
        }
    }

    public static void submit(Runnable task) {
        executor().execute(task);
    }
}
