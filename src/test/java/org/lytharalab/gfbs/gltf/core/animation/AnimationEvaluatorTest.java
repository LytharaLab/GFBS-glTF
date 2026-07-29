package org.lytharalab.gfbs.gltf.core.animation;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.animation.*;
import org.lytharalab.gfbs.gltf.api.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnimationEvaluatorTest {
    @Test
    void samplesLinearAndStepChannels() {
        AnimationSampler linear = new AnimationSampler(new float[]{0, 2},
            new float[]{0, 0, 0, 2, 4, 6}, 3, Interpolation.LINEAR);
        float[] target = new float[3];
        AnimationEvaluator.sample(linear, AnimationPath.TRANSLATION, 1, target);
        assertArrayEquals(new float[]{1, 2, 3}, target, 1.0e-6f);

        AnimationSampler step = new AnimationSampler(new float[]{0, 2},
            new float[]{2, 4}, 1, Interpolation.STEP);
        AnimationEvaluator.sample(step, AnimationPath.WEIGHTS, 1.99f, target);
        assertEquals(2, target[0], 1.0e-6f);
    }

    @Test
    void samplesCubicSplineWithScaledTangents() {
        AnimationSampler cubic = new AnimationSampler(new float[]{0, 1},
            new float[]{0, 0, 1, 0, 1, 0}, 1, Interpolation.CUBIC_SPLINE);
        float[] target = new float[1];
        AnimationEvaluator.sample(cubic, AnimationPath.WEIGHTS, 0.5f, target);
        assertEquals(0.625f, target[0], 1.0e-6f);
    }

    @Test
    void quaternionInterpolationUsesShortestNormalizedPath() {
        AnimationSampler sampler = new AnimationSampler(new float[]{0, 1},
            new float[]{0, 0, 0, 2, 0, 0, 0, -2}, 4, Interpolation.LINEAR);
        float[] target = new float[4];
        AnimationEvaluator.sample(sampler, AnimationPath.ROTATION, 0.5f, target);
        assertArrayEquals(new float[]{0, 0, 0, 1}, target, 1.0e-6f);
        AnimationEvaluator.sample(sampler, AnimationPath.ROTATION, 0.0f, target);
        assertArrayEquals(new float[]{0, 0, 0, 1}, target, 1.0e-6f);
    }

    @Test
    void rejectsMalformedSamplersAndChannelShapes() {
        assertThrows(IllegalArgumentException.class, () -> new AnimationSampler(
            new float[]{0, 0}, new float[]{0, 1}, 1, Interpolation.LINEAR));
        assertThrows(IllegalArgumentException.class, () -> new AnimationSampler(
            new float[]{0, Float.NaN}, new float[]{0, 1}, 1, Interpolation.LINEAR));
        assertThrows(IllegalArgumentException.class, () -> new AnimationSampler(
            new float[]{-1, 0}, new float[]{0, 1}, 1, Interpolation.LINEAR));
        AnimationSampler scalar = new AnimationSampler(new float[]{0}, new float[]{0}, 1, Interpolation.STEP);
        assertThrows(IllegalArgumentException.class,
            () -> new AnimationChannel(0, AnimationPath.TRANSLATION, scalar));
    }

    @Test
    void loopingSeekWrapsInBothDirections() {
        AnimationSampler sampler = new AnimationSampler(new float[]{0, 2},
            new float[]{0, 0, 0, 2, 0, 0}, 3, Interpolation.LINEAR);
        AnimationClip clip = new AnimationClip("move", List.of(
            new AnimationChannel(0, AnimationPath.TRANSLATION, sampler)));
        AnimationController controller = new AnimationController(asset(clip));
        controller.play("move", PlaybackOptions.loop());
        controller.seek(5.0f);
        assertEquals(1.0f, controller.time(), 1.0e-6f);
        controller.seek(-0.5f);
        assertEquals(1.5f, controller.time(), 1.0e-6f);
    }

    @Test
    void hugeFiniteLoopDeltaDoesNotProduceNan() {
        AnimationSampler sampler = new AnimationSampler(new float[]{0, 2},
            new float[]{0, 0, 0, 2, 0, 0}, 3, Interpolation.LINEAR);
        AnimationClip clip = new AnimationClip("move", List.of(
            new AnimationChannel(0, AnimationPath.TRANSLATION, sampler)));
        AnimationController controller = new AnimationController(asset(clip));
        controller.play("move", PlaybackOptions.loop());
        controller.update(Float.MAX_VALUE);
        assertTrue(Float.isFinite(controller.time()));
        assertTrue(controller.time() >= 0.0f && controller.time() < clip.duration());
    }



    @Test
    void normalizesHugeFiniteQuaternionsWithoutOverflow() {
        float[] quaternion = {Float.MAX_VALUE, Float.MAX_VALUE, 0.0f, 0.0f};
        AnimationEvaluator.normalizeQuaternion(quaternion);
        double length = Math.sqrt((double) quaternion[0] * quaternion[0]
            + (double) quaternion[1] * quaternion[1]
            + (double) quaternion[2] * quaternion[2]
            + (double) quaternion[3] * quaternion[3]);
        assertEquals(1.0, length, 1.0e-6);
        for (float component : quaternion) assertTrue(Float.isFinite(component));
    }

    @Test
    void duplicateAndBlankAnimationNamesReceiveStableAliases() {
        AnimationSampler sampler = new AnimationSampler(new float[]{0, 1},
            new float[]{0, 0, 0, 1, 0, 0}, 3, Interpolation.LINEAR);
        AnimationClip first = new AnimationClip("Walk", List.of(
            new AnimationChannel(0, AnimationPath.TRANSLATION, sampler)));
        AnimationClip second = new AnimationClip("Walk", List.of(
            new AnimationChannel(0, AnimationPath.TRANSLATION, sampler)));
        AnimationClip unnamed = new AnimationClip("", List.of(
            new AnimationChannel(0, AnimationPath.TRANSLATION, sampler)));
        GltfAsset asset = asset(List.of(first, second, unnamed), oneNode());

        assertEquals(List.of("Walk", "Walk#1", "animation_2"), asset.animationNames());
        assertSame(first, asset.animation("Walk").orElseThrow());
        assertSame(second, asset.animation("Walk#1").orElseThrow());
        assertSame(unnamed, asset.animation("animation_2").orElseThrow());
    }

    @Test
    void computesVeryDeepHierarchyWithoutStackOverflow() {
        int count = 10_000;
        java.util.ArrayList<GltfNode> nodes = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int[] children = i + 1 < count ? new int[]{i + 1} : new int[0];
            nodes.add(new GltfNode("node_" + i, i - 1, children, new int[0], -1,
                null, new float[]{1, 0, 0}, null, null, null));
        }
        GltfAsset asset = asset(List.of(), nodes);
        float[] matrices = PoseTransforms.computeWorldMatrices(new ModelPose(asset));
        assertEquals((float) count, matrices[(count - 1) * 16 + 12], 1.0e-3f);
    }

    @Test
    void rejectsInverseMatrixOverflow() {
        float tiny = 1.0e-39f;
        float[] matrix = {tiny, 0, 0, 0, 0, tiny, 0, 0, 0, 0, tiny, 0, 0, 0, 0, 1};
        assertThrows(IllegalArgumentException.class, () -> PoseTransforms.invert(matrix));
    }

    @Test
    void rejectsDuplicateAnimationTargetsAndInvalidPrimitiveTopology() {
        AnimationSampler sampler = new AnimationSampler(new float[]{0, 1},
            new float[]{0, 0, 0, 1, 0, 0}, 3, Interpolation.LINEAR);
        AnimationClip duplicate = new AnimationClip("duplicate", List.of(
            new AnimationChannel(0, AnimationPath.TRANSLATION, sampler),
            new AnimationChannel(0, AnimationPath.TRANSLATION, sampler)));
        assertThrows(IllegalArgumentException.class, () -> asset(duplicate));
        assertThrows(IllegalArgumentException.class, () -> new GltfPrimitive(
            PrimitiveMode.TRIANGLES, 0, 2, new float[]{0, 0, 0, 1, 0, 0},
            null, null, null, null, null, null, null, null, List.of()));
    }

    private static GltfAsset asset(AnimationClip clip) {
        return asset(List.of(clip), oneNode());
    }

    private static List<GltfNode> oneNode() {
        return List.of(new GltfNode("node", -1, new int[0], new int[]{0}, -1,
            null, null, null, null, null));
    }

    private static GltfAsset asset(List<AnimationClip> clips, List<GltfNode> nodes) {
        GltfPrimitive primitive = new GltfPrimitive(PrimitiveMode.TRIANGLES, 0, 3,
            new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, null, null,
            null, null, null, null, null, new int[]{0, 1, 2}, List.of());
        GltfMesh mesh = new GltfMesh("mesh", List.of(primitive), null);
        return new GltfAsset(ResourceLocation.fromNamespaceAndPath("test", "animation"),
            List.of(new GltfScene("scene", new int[]{0})), nodes, List.of(mesh),
            List.of(GltfMaterial.defaultMaterial()), List.of(), List.of(), clips,
            List.of(), List.of());
    }
}
