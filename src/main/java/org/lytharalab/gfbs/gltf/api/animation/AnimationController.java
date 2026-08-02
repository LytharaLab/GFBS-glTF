package org.lytharalab.gfbs.gltf.api.animation;

import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.core.animation.AnimationEvaluator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AnimationController {
    public static final String BASE_LAYER = "base";

    private final GltfAsset asset;
    private final ModelPose pose;
    private final ModelPose bind;
    private final ModelPose scratch;
    private final LinkedHashMap<String, Track> tracks = new LinkedHashMap<>();
    private final Map<String, List<AnimationEvent>> events = new HashMap<>();
    private final CopyOnWriteArrayList<AnimationEventListener> listeners = new CopyOnWriteArrayList<>();

    public AnimationController(GltfAsset asset) {
        this.asset = Objects.requireNonNull(asset, "asset");
        this.pose = new ModelPose(asset);
        this.bind = new ModelPose(asset);
        this.scratch = new ModelPose(asset);
    }

    public void play(String animation, PlaybackOptions options) {
        playLayer(
            BASE_LAYER,
            animation,
            options,
            1.0f,
            AnimationBlendMode.OVERRIDE,
            AnimationMask.all(asset.nodes().size())
        );
    }

    public void playLayer(String layer, String animation, PlaybackOptions options, float weight,
                          AnimationBlendMode mode, AnimationMask mask) {
        if (layer == null || layer.isBlank()) {
            throw new IllegalArgumentException("Animation layer name is blank");
        }
        AnimationClip clip = asset.animation(Objects.requireNonNull(animation, "animation"))
            .orElseThrow(() -> new IllegalArgumentException("Unknown animation: " + animation));
        options = Objects.requireNonNull(options, "options");
        mode = Objects.requireNonNull(mode, "mode");
        mask = Objects.requireNonNull(mask, "mask");
        checkWeight(weight);
        if (mask.nodeCount() != asset.nodes().size()) {
            throw new IllegalArgumentException("Animation mask node count mismatch");
        }

        ModelPose source = null;
        Track old = tracks.get(layer);
        if (options.transitionSeconds() > 0.0f) {
            if (old != null) {
                source = new ModelPose(asset);
                sample(old, source);
            } else {
                // A late network state may begin after the authoritative clip has already
                // advanced. Blend the new base layer from its bind pose instead of popping
                // directly to the delayed clip position. Other mixer layers remain independent.
                source = bind.copy();
            }
        }

        tracks.put(layer, new Track(
            clip,
            options,
            normalizeTime(clip, options.initialTime(), options.loopMode()),
            weight,
            mode,
            mask,
            source
        ));
        evaluate();
    }

    public void fadeLayer(String layer, float target, float seconds) {
        checkWeight(target);
        if (!Float.isFinite(seconds) || seconds < 0.0f) {
            throw new IllegalArgumentException("Invalid fade duration");
        }
        Track track = requireTrack(layer);
        track.target = target;
        track.remaining = seconds;
        track.rate = seconds == 0.0f ? 0.0f : Math.abs(target - track.weight) / seconds;
        if (seconds == 0.0f) {
            track.weight = target;
        }
    }

    public void stopLayer(String layer, float seconds) {
        if (!Float.isFinite(seconds) || seconds < 0.0f) {
            throw new IllegalArgumentException("Invalid fade duration");
        }
        Track track = tracks.get(layer);
        if (track == null) {
            return;
        }
        if (seconds == 0.0f) {
            tracks.remove(layer);
        } else {
            track.remove = true;
            track.target = 0.0f;
            track.remaining = seconds;
            track.rate = track.weight / seconds;
        }
        evaluate();
    }

    public void setLayerWeight(String layer, float weight) {
        checkWeight(weight);
        Track track = requireTrack(layer);
        track.weight = weight;
        track.target = weight;
        track.remaining = 0.0f;
        evaluate();
    }

    /**
     * Changes the live playback speed without restarting or seeking the clip.
     *
     * <p>This is intentionally separate from {@link PlaybackOptions}: integrations such as the
     * synchronized-animation clock can gently correct drift while preserving the current pose,
     * transition, event cursor, and layer state. A live speed of zero freezes time without
     * destroying the track.</p>
     */
    public void setSpeed(float speed) {
        setLayerSpeed(BASE_LAYER, speed);
    }

    /** Changes the live playback speed of an existing layer without restarting it. */
    public void setLayerSpeed(String layer, float speed) {
        checkSpeed(speed);
        Track track = tracks.get(layer);
        if (track != null) {
            track.speed = speed;
        }
    }

    /** Returns the live speed of the base layer, or zero when no base clip exists. */
    public float speed() {
        Track track = tracks.get(BASE_LAYER);
        return track == null ? 0.0f : track.speed;
    }

    /** Returns the live speed of a named layer. */
    public float layerSpeed(String layer) {
        return requireTrack(layer).speed;
    }

    public Optional<AnimationLayer> layer(String name) {
        Track track = tracks.get(name);
        return track == null ? Optional.empty() : Optional.of(snapshot(name, track));
    }

    public List<AnimationLayer> layers() {
        List<AnimationLayer> result = new ArrayList<>();
        tracks.forEach((name, track) -> result.add(snapshot(name, track)));
        return List.copyOf(result);
    }

    public void addEvent(String animation, AnimationEvent event) {
        AnimationClip clip = asset.animation(animation)
            .orElseThrow(() -> new IllegalArgumentException("Unknown animation: " + animation));
        if (event.time() > clip.duration()) {
            throw new IllegalArgumentException("Event lies after animation end");
        }
        events.computeIfAbsent(animation, ignored -> new ArrayList<>()).add(event);
        events.get(animation).sort(Comparator.comparingDouble(AnimationEvent::time));
    }

    public void addEventListener(AnimationEventListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeEventListener(AnimationEventListener listener) {
        listeners.remove(listener);
    }

    public void update(float deltaSeconds) {
        if (!Float.isFinite(deltaSeconds)) {
            throw new IllegalArgumentException("Delta time must be finite");
        }
        if (deltaSeconds == 0.0f) {
            return;
        }

        Iterator<Map.Entry<String, Track>> iterator = tracks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Track> entry = iterator.next();
            Track track = entry.getValue();
            fade(track, Math.abs(deltaSeconds));
            if (track.remove && track.weight <= 0.0f) {
                iterator.remove();
                continue;
            }
            if (track.playing) {
                advance(entry.getKey(), track, deltaSeconds);
            }
            if (track.source != null) {
                track.transition = Math.min(
                    track.options.transitionSeconds(),
                    track.transition + Math.abs(deltaSeconds)
                );
                if (track.transition >= track.options.transitionSeconds()) {
                    track.source = null;
                }
            }
        }
        evaluate();
    }

    public void seek(float seconds) {
        seekLayer(BASE_LAYER, seconds);
    }

    public void seekLayer(String layer, float seconds) {
        if (!Float.isFinite(seconds)) {
            throw new IllegalArgumentException("Animation time must be finite");
        }
        Track track = tracks.get(layer);
        if (track != null) {
            track.time = normalizeTime(track.clip, seconds, track.options.loopMode());
            track.source = null;
            evaluate();
        }
    }

    public void pause() {
        pauseLayer(BASE_LAYER);
    }

    public void pauseLayer(String layer) {
        Track track = tracks.get(layer);
        if (track != null) {
            track.playing = false;
        }
    }

    public void resume() {
        resumeLayer(BASE_LAYER);
    }

    public void resumeLayer(String layer) {
        Track track = tracks.get(layer);
        if (track != null) {
            track.playing = true;
        }
    }

    public void stop(boolean reset) {
        tracks.remove(BASE_LAYER);
        if (reset) {
            evaluate();
        }
    }

    public void stopAll(boolean reset) {
        tracks.clear();
        if (reset) {
            pose.reset();
        }
    }

    public ModelPose pose() {
        return pose;
    }

    public float time() {
        Track track = tracks.get(BASE_LAYER);
        return track == null ? 0.0f : track.time;
    }

    public boolean isPlaying() {
        Track track = tracks.get(BASE_LAYER);
        return track != null && track.playing;
    }

    public Optional<AnimationClip> currentClip() {
        Track track = tracks.get(BASE_LAYER);
        return track == null ? Optional.empty() : Optional.of(track.clip);
    }

    private void advance(String layer, Track track, float deltaSeconds) {
        if (track.speed == 0.0f) {
            return;
        }
        float before = track.time;
        float duration = track.clip.duration();
        double raw = (double) before + (double) deltaSeconds * track.speed;
        boolean wrapped = false;

        if (track.options.loopMode() == LoopMode.LOOP && duration > 0.0f) {
            track.time = (float) positiveModulo(raw, duration);
            wrapped = track.speed > 0.0f
                ? raw >= duration || raw < 0.0d
                : raw <= 0.0d || raw > duration;
        } else {
            boolean reachedEnd = track.speed > 0.0f ? raw >= duration : raw <= 0.0d;
            track.time = (float) Math.max(0.0d, Math.min(duration, raw));
            if (reachedEnd) {
                track.playing = false;
            }
        }
        fireEvents(layer, track, before, track.time, wrapped);
    }

    private void fireEvents(String layer, Track track, float before, float now, boolean wrapped) {
        List<AnimationEvent> clipEvents = events.get(track.clip.name());
        if (clipEvents == null) {
            return;
        }
        boolean forward = track.speed > 0.0f;
        for (AnimationEvent event : clipEvents) {
            boolean hit = forward
                ? (!wrapped && event.time() > before && event.time() <= now)
                    || (wrapped && (event.time() > before || event.time() <= now))
                : (!wrapped && event.time() < before && event.time() >= now)
                    || (wrapped && (event.time() < before || event.time() >= now));
            if (hit) {
                for (AnimationEventListener listener : listeners) {
                    listener.onAnimationEvent(layer, track.clip, event);
                }
            }
        }
    }

    private static void fade(Track track, float deltaSeconds) {
        if (track.remaining <= 0.0f || track.weight == track.target) {
            return;
        }
        float step = Math.min(deltaSeconds, track.remaining) * track.rate;
        track.weight = track.weight < track.target
            ? Math.min(track.target, track.weight + step)
            : Math.max(track.target, track.weight - step);
        track.remaining = Math.max(0.0f, track.remaining - deltaSeconds);
        if (track.remaining == 0.0f) {
            track.weight = track.target;
        }
    }

    private void evaluate() {
        pose.reset();
        for (Track track : tracks.values()) {
            if (track.weight <= 0.0f) {
                continue;
            }
            sample(track, scratch);
            for (int node = 0; node < pose.nodeCount(); node++) {
                if (!track.mask.includes(node)) {
                    continue;
                }
                if (track.mode == AnimationBlendMode.OVERRIDE) {
                    override(pose.node(node), scratch.node(node), track.weight);
                } else {
                    add(pose.node(node), scratch.node(node), bind.node(node), track.weight);
                }
            }
        }
    }

    private void sample(Track track, ModelPose output) {
        output.reset();
        AnimationEvaluator.apply(track.clip, track.time, output);
        if (track.source != null && track.options.transitionSeconds() > 0.0f) {
            float alpha = Math.min(1.0f, track.transition / track.options.transitionSeconds());
            for (int node = 0; node < output.nodeCount(); node++) {
                if (track.mask.includes(node)) {
                    blend(track.source.node(node), output.node(node), alpha);
                }
            }
        }
    }

    private static void override(NodePose output, NodePose value, float weight) {
        weight = Math.max(0.0f, Math.min(1.0f, weight));
        for (int component = 0; component < 3; component++) {
            output.translation()[component] +=
                (value.translation()[component] - output.translation()[component]) * weight;
            output.scale()[component] +=
                (value.scale()[component] - output.scale()[component]) * weight;
        }
        AnimationEvaluator.blendQuaternion(
            output.rotation(),
            value.rotation(),
            weight,
            output.rotation()
        );
        float[] outputWeights = output.weights();
        float[] valueWeights = value.weights();
        if (outputWeights != null && valueWeights != null && outputWeights.length == valueWeights.length) {
            for (int component = 0; component < outputWeights.length; component++) {
                outputWeights[component] +=
                    (valueWeights[component] - outputWeights[component]) * weight;
            }
        }
    }

    private static void blend(NodePose source, NodePose target, float weight) {
        weight = Math.max(0.0f, Math.min(1.0f, weight));
        for (int component = 0; component < 3; component++) {
            target.translation()[component] = source.translation()[component]
                + (target.translation()[component] - source.translation()[component]) * weight;
            target.scale()[component] = source.scale()[component]
                + (target.scale()[component] - source.scale()[component]) * weight;
        }
        AnimationEvaluator.blendQuaternion(
            source.rotation(),
            target.rotation(),
            weight,
            target.rotation()
        );
        float[] sourceWeights = source.weights();
        float[] targetWeights = target.weights();
        if (sourceWeights != null && targetWeights != null && sourceWeights.length == targetWeights.length) {
            for (int component = 0; component < targetWeights.length; component++) {
                targetWeights[component] = sourceWeights[component]
                    + (targetWeights[component] - sourceWeights[component]) * weight;
            }
        }
    }

    private static void add(NodePose output, NodePose value, NodePose bindPose, float weight) {
        for (int component = 0; component < 3; component++) {
            output.translation()[component] +=
                (value.translation()[component] - bindPose.translation()[component]) * weight;
            float bindScale = Math.abs(bindPose.scale()[component]) < 1.0e-7f
                ? 1.0f
                : bindPose.scale()[component];
            output.scale()[component] *=
                1.0f + (value.scale()[component] / bindScale - 1.0f) * weight;
        }

        float[] rotationDelta = quaternionDelta(value.rotation(), bindPose.rotation());
        float[] weightedDelta = new float[4];
        AnimationEvaluator.blendQuaternion(
            new float[]{0.0f, 0.0f, 0.0f, 1.0f},
            rotationDelta,
            weight,
            weightedDelta
        );
        multiplyQuaternions(output.rotation(), weightedDelta, output.rotation());

        float[] valueWeights = value.weights();
        float[] bindWeights = bindPose.weights();
        float[] outputWeights = output.weights();
        if (valueWeights != null && bindWeights != null && outputWeights != null) {
            int length = Math.min(valueWeights.length, outputWeights.length);
            for (int component = 0; component < length; component++) {
                outputWeights[component] +=
                    (valueWeights[component] - bindWeights[component]) * weight;
            }
        }
    }

    private static float[] quaternionDelta(float[] value, float[] bindPose) {
        float[] result = new float[4];
        multiplyQuaternions(
            value,
            new float[]{-bindPose[0], -bindPose[1], -bindPose[2], bindPose[3]},
            result
        );
        return result;
    }

    private static void multiplyQuaternions(float[] left, float[] right, float[] output) {
        float x = left[3] * right[0] + left[0] * right[3]
            + left[1] * right[2] - left[2] * right[1];
        float y = left[3] * right[1] - left[0] * right[2]
            + left[1] * right[3] + left[2] * right[0];
        float z = left[3] * right[2] + left[0] * right[1]
            - left[1] * right[0] + left[2] * right[3];
        float w = left[3] * right[3] - left[0] * right[0]
            - left[1] * right[1] - left[2] * right[2];
        output[0] = x;
        output[1] = y;
        output[2] = z;
        output[3] = w;
        AnimationEvaluator.normalizeQuaternion(output);
    }

    private Track requireTrack(String name) {
        Track track = tracks.get(name);
        if (track == null) {
            throw new IllegalArgumentException("Unknown animation layer: " + name);
        }
        return track;
    }

    private static void checkWeight(float weight) {
        if (!Float.isFinite(weight) || weight < 0.0f || weight > 1.0f) {
            throw new IllegalArgumentException("Layer weight must be between 0 and 1");
        }
    }

    private static void checkSpeed(float speed) {
        if (!Float.isFinite(speed)) {
            throw new IllegalArgumentException("Speed must be finite");
        }
    }

    private static AnimationLayer snapshot(String name, Track track) {
        return new AnimationLayer(
            name,
            track.clip.name(),
            track.time,
            track.weight,
            track.playing,
            track.mode
        );
    }

    private static float normalizeTime(AnimationClip clip, float time, LoopMode loopMode) {
        return loopMode == LoopMode.LOOP && clip.duration() > 0.0f
            ? (float) positiveModulo(time, clip.duration())
            : Math.max(0.0f, Math.min(clip.duration(), time));
    }

    private static double positiveModulo(double value, double modulus) {
        double result = value % modulus;
        return result < 0.0d ? result + modulus : result;
    }

    private static final class Track {
        private final AnimationClip clip;
        private final PlaybackOptions options;
        private final AnimationBlendMode mode;
        private final AnimationMask mask;
        private float time;
        private float speed;
        private float weight;
        private float target;
        private float remaining;
        private float rate;
        private float transition;
        private boolean playing = true;
        private boolean remove;
        private ModelPose source;

        private Track(AnimationClip clip, PlaybackOptions options, float time, float weight,
                      AnimationBlendMode mode, AnimationMask mask, ModelPose source) {
            this.clip = clip;
            this.options = options;
            this.time = time;
            this.speed = options.speed();
            this.weight = weight;
            this.target = weight;
            this.mode = mode;
            this.mask = mask;
            this.source = source;
        }
    }
}
