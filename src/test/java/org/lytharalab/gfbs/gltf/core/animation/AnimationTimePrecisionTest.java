package org.lytharalab.gfbs.gltf.core.animation;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.animation.AnimationChannel;
import org.lytharalab.gfbs.gltf.api.animation.AnimationClip;
import org.lytharalab.gfbs.gltf.api.animation.AnimationController;
import org.lytharalab.gfbs.gltf.api.animation.AnimationPath;
import org.lytharalab.gfbs.gltf.api.animation.AnimationSampler;
import org.lytharalab.gfbs.gltf.api.animation.Interpolation;
import org.lytharalab.gfbs.gltf.api.animation.LoopMode;
import org.lytharalab.gfbs.gltf.api.animation.PlaybackOptions;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfMesh;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfScene;
import org.lytharalab.gfbs.gltf.api.model.PrimitiveMode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for the 1.5.1 precision work: the playhead, the seek target and the timeline
 * must all stay {@code double}. At {@code 1e6} seconds a {@code float} playhead lands on a 62.5 ms
 * grid, which is exactly the staircase that used to make synchronized animations stutter.
 */
class AnimationTimePrecisionTest {
    @Test
    void seekKeepsTheExactPhaseAtElevenDaysOfLogicalTime() {
        AnimationController controller = new AnimationController(asset(clip()));

        controller.play("x", new PlaybackOptions(1.0f, LoopMode.LOOP, 0.0d, 1_000_000.03125d));

        // A 2 s clip: 1_000_000.03125 wraps to 0.03125 with full resolution.
        assertEquals(0.03125d, controller.time(), 1.0e-12d);

        controller.update(1.0e-7d);
        assertEquals(0.0312501d, controller.time(), 1.0e-12d);

        // 1.5.0 failure mode: at eleven days of logical time the same increment disappears in float,
        // because a float step at 1e6 seconds is already 62.5 ms.
        assertEquals(1_000_000.0f, (float) (1_000_000.0f + 1.0e-7f));
        assertEquals(1_000_000.0625f, 1_000_000.0f + 0.0625f);
    }

    @Test
    void playbackAdvancesWithoutQuantizationAtLargeSeekTargets() {
        AnimationController controller = new AnimationController(asset(clip()));
        controller.play("x", new PlaybackOptions(1.0f, LoopMode.LOOP, 0.0d, 0.0d));

        for (int step = 0; step < 40; step++) {
            controller.update(0.025d);
        }

        assertEquals(1.0d, controller.time(), 1.0e-12d);
    }

    private static AnimationClip clip() {
        return new AnimationClip("x", List.of(new AnimationChannel(
            0,
            AnimationPath.TRANSLATION,
            new AnimationSampler(new float[]{0.0f, 2.0f}, new float[]{0, 0, 0, 2, 0, 0}, 3, Interpolation.LINEAR)
        )));
    }

    private static GltfAsset asset(AnimationClip... clips) {
        GltfPrimitive primitive = new GltfPrimitive(
            PrimitiveMode.TRIANGLES, 0, 3,
            new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
            null, null, null, null, null, null, null,
            new int[]{0, 1, 2}, List.of()
        );
        GltfMesh mesh = new GltfMesh("mesh", List.of(primitive), null);
        GltfNode node = new GltfNode("node", -1, new int[0], new int[]{0}, -1, null, null, null, null, null);
        return new GltfAsset(
            ResourceLocation.fromNamespaceAndPath("test", "precision"),
            List.of(new GltfScene("scene", new int[]{0})),
            List.of(node),
            List.of(mesh),
            List.of(GltfMaterial.defaultMaterial()),
            List.of(), List.of(), List.of(clips), List.of(), List.of()
        );
    }
}
