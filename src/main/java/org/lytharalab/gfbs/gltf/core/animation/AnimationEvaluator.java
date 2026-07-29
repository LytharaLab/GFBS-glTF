package org.lytharalab.gfbs.gltf.core.animation;

import org.lytharalab.gfbs.gltf.api.animation.*;

public final class AnimationEvaluator {
    private AnimationEvaluator() {
    }

    public static void apply(AnimationClip clip, float time, ModelPose pose) {
        if (!Float.isFinite(time)) throw new IllegalArgumentException("Animation time must be finite");
        for (AnimationChannel channel : clip.channels()) {
            NodePose node = pose.node(channel.node());
            int components = channel.sampler().components();
            float[] target = switch (channel.path()) {
                case TRANSLATION -> node.translation();
                case ROTATION -> node.rotation();
                case SCALE -> node.scale();
                case WEIGHTS -> { node.ensureWeights(components); yield node.weights(); }
            };
            sample(channel.sampler(), channel.path(), time, target);
        }
    }

    public static void sample(AnimationSampler sampler, AnimationPath path, float time, float[] target) {
        if (!Float.isFinite(time)) throw new IllegalArgumentException("Animation time must be finite");
        int components = sampler.components();
        if (target.length < components) throw new IllegalArgumentException("Animation target is too small");
        int keyframes = sampler.keyframeCount();
        if (time <= sampler.time(0)) {
            copyKey(sampler, 0, target);
            if (path == AnimationPath.ROTATION) normalizeQuaternion(target);
            return;
        }
        int last = keyframes - 1;
        if (time >= sampler.time(last)) {
            copyKey(sampler, last, target);
            if (path == AnimationPath.ROTATION) normalizeQuaternion(target);
            return;
        }
        int low = 0, high = last;
        while (low + 1 < high) {
            int mid = (low + high) >>> 1;
            if (sampler.time(mid) <= time) low = mid; else high = mid;
        }
        float delta = sampler.time(high) - sampler.time(low);
        float alpha = (time - sampler.time(low)) / delta;
        switch (sampler.interpolation()) {
            case STEP -> {
                copyKey(sampler, low, target);
                if (path == AnimationPath.ROTATION) normalizeQuaternion(target);
            }
            case LINEAR -> {
                int a = low * components, b = high * components;
                if (path == AnimationPath.ROTATION) slerp(sampler, a, b, alpha, target);
                else for (int c = 0; c < components; c++) target[c] = lerp(sampler.value(a + c), sampler.value(b + c), alpha);
            }
            case CUBIC_SPLINE -> {
                int stride = components * 3;
                int value0 = low * stride + components;
                int outTangent0 = low * stride + components * 2;
                int inTangent1 = high * stride;
                int value1 = high * stride + components;
                float t2 = alpha * alpha, t3 = t2 * alpha;
                float h00 = 2 * t3 - 3 * t2 + 1;
                float h10 = t3 - 2 * t2 + alpha;
                float h01 = -2 * t3 + 3 * t2;
                float h11 = t3 - t2;
                for (int c = 0; c < components; c++) {
                    target[c] = h00 * sampler.value(value0 + c) + h10 * delta * sampler.value(outTangent0 + c)
                        + h01 * sampler.value(value1 + c) + h11 * delta * sampler.value(inTangent1 + c);
                }
                if (path == AnimationPath.ROTATION) normalizeQuaternion(target);
            }
        }
    }

    private static void copyKey(AnimationSampler sampler, int key, float[] target) {
        int components = sampler.components();
        int offset = sampler.interpolation() == Interpolation.CUBIC_SPLINE
            ? key * components * 3 + components : key * components;
        for (int component = 0; component < components; component++) target[component] = sampler.value(offset + component);
    }

    private static float lerp(float a, float b, float alpha) { return a + (b - a) * alpha; }

    private static void slerp(AnimationSampler sampler, int a, int b, float alpha, float[] target) {
        float ax = sampler.value(a), ay = sampler.value(a + 1);
        float az = sampler.value(a + 2), aw = sampler.value(a + 3);
        float bx = sampler.value(b), by = sampler.value(b + 1);
        float bz = sampler.value(b + 2), bw = sampler.value(b + 3);

        float inverseA = inverseQuaternionLength(ax, ay, az, aw);
        float inverseB = inverseQuaternionLength(bx, by, bz, bw);
        if (inverseA == 0.0f) { ax = ay = az = 0.0f; aw = 1.0f; }
        else { ax *= inverseA; ay *= inverseA; az *= inverseA; aw *= inverseA; }
        if (inverseB == 0.0f) { bx = by = bz = 0.0f; bw = 1.0f; }
        else { bx *= inverseB; by *= inverseB; bz *= inverseB; bw *= inverseB; }

        float dot = ax * bx + ay * by + az * bz + aw * bw;
        float sign = dot < 0 ? -1 : 1;
        dot = Math.min(1.0f, Math.abs(dot));
        if (dot > 0.9995f) {
            target[0] = lerp(ax, bx * sign, alpha);
            target[1] = lerp(ay, by * sign, alpha);
            target[2] = lerp(az, bz * sign, alpha);
            target[3] = lerp(aw, bw * sign, alpha);
            normalizeQuaternion(target);
            return;
        }
        double angle = Math.acos(dot);
        double sin = Math.sin(angle);
        float wa = (float) (Math.sin((1 - alpha) * angle) / sin);
        float wb = (float) (Math.sin(alpha * angle) / sin) * sign;
        target[0] = ax * wa + bx * wb;
        target[1] = ay * wa + by * wb;
        target[2] = az * wa + bz * wb;
        target[3] = aw * wa + bw * wb;
        normalizeQuaternion(target);
    }

    private static float inverseQuaternionLength(float x, float y, float z, float w) {
        double lengthSquared = (double) x * x + (double) y * y + (double) z * z + (double) w * w;
        if (!Double.isFinite(lengthSquared) || lengthSquared < 1.0e-20) return 0.0f;
        return (float) (1.0 / Math.sqrt(lengthSquared));
    }

    public static void normalizeQuaternion(float[] value) {
        if (value == null || value.length < 4) throw new IllegalArgumentException("Quaternion requires four components");
        double lengthSquared = (double) value[0] * value[0] + (double) value[1] * value[1]
            + (double) value[2] * value[2] + (double) value[3] * value[3];
        if (!Double.isFinite(lengthSquared) || lengthSquared < 1.0e-20) {
            value[0] = value[1] = value[2] = 0; value[3] = 1;
            return;
        }
        double inverseLength = 1.0 / Math.sqrt(lengthSquared);
        for (int i = 0; i < 4; i++) value[i] = (float) (value[i] * inverseLength);
    }

    public static void blendQuaternion(float[] from, float[] to, float alpha, float[] target) {
        if (from == null || from.length < 4 || to == null || to.length < 4
            || target == null || target.length < 4) {
            throw new IllegalArgumentException("Quaternion blend requires four-component arrays");
        }
        if (!Float.isFinite(alpha)) throw new IllegalArgumentException("Blend factor must be finite");
        alpha = Math.max(0.0f, Math.min(1.0f, alpha));
        float ax = from[0], ay = from[1], az = from[2], aw = from[3];
        float bx = to[0], by = to[1], bz = to[2], bw = to[3];
        float inverseA = inverseQuaternionLength(ax, ay, az, aw);
        float inverseB = inverseQuaternionLength(bx, by, bz, bw);
        if (inverseA == 0.0f) { ax = ay = az = 0.0f; aw = 1.0f; }
        else { ax *= inverseA; ay *= inverseA; az *= inverseA; aw *= inverseA; }
        if (inverseB == 0.0f) { bx = by = bz = 0.0f; bw = 1.0f; }
        else { bx *= inverseB; by *= inverseB; bz *= inverseB; bw *= inverseB; }
        float dot = ax * bx + ay * by + az * bz + aw * bw;
        float sign = dot < 0 ? -1 : 1;
        dot = Math.min(1.0f, Math.abs(dot));
        if (dot > 0.9995f) {
            target[0] = lerp(ax, bx * sign, alpha);
            target[1] = lerp(ay, by * sign, alpha);
            target[2] = lerp(az, bz * sign, alpha);
            target[3] = lerp(aw, bw * sign, alpha);
        } else {
            double angle = Math.acos(dot);
            double sin = Math.sin(angle);
            float fromWeight = (float) (Math.sin((1 - alpha) * angle) / sin);
            float toWeight = (float) (Math.sin(alpha * angle) / sin) * sign;
            target[0] = ax * fromWeight + bx * toWeight;
            target[1] = ay * fromWeight + by * toWeight;
            target[2] = az * fromWeight + bz * toWeight;
            target[3] = aw * fromWeight + bw * toWeight;
        }
        normalizeQuaternion(target);
    }
}
