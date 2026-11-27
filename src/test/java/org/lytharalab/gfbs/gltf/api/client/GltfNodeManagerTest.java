package org.lytharalab.gfbs.gltf.api.client;

import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.animation.AnimationController;
import org.lytharalab.gfbs.gltf.api.animation.ModelPose;
import org.lytharalab.gfbs.gltf.api.client.material.GltfMaterialOverride;
import org.lytharalab.gfbs.gltf.api.client.material.GltfMaterialVariant;
import org.lytharalab.gfbs.gltf.api.client.material.GltfShadingMode;
import org.lytharalab.gfbs.gltf.api.client.node.GltfNodeManager;
import org.lytharalab.gfbs.gltf.api.client.node.GltfPrimitiveState;
import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfMesh;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfScene;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;
import org.lytharalab.gfbs.gltf.api.model.PrimitiveMode;
import org.lytharalab.gfbs.gltf.core.animation.PoseTransforms;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfNodeManagerTest {
    @Test
    void resolvesGeneralMaterialSwitchesAndNeonShadingPerInstance() {
        GltfAsset asset = asset();
        GltfNodeManager nodes = new GltfNodeManager(asset);
        GltfPrimitiveState primitive = nodes.primitive(0, 0, 0);
        long collisionRevision = nodes.collisionRevision();

        primitive.material(1);
        assertEquals(1, primitive.effectiveMaterialIndex());
        assertSame(asset.materials().get(1), primitive.effectiveMaterial());

        nodes.defineVariant("neon", new GltfMaterialVariant(
            1,
            GltfMaterialOverride.builder()
                .shadingMode(GltfShadingMode.NEON)
                .neonStrength(6.0f)
                .build()
        ));
        primitive.variant("neon");
        assertEquals(collisionRevision, nodes.collisionRevision());

        GltfMaterial neon = primitive.effectiveMaterial();
        assertTrue(neon.unlit());
        assertArrayEquals(new float[]{0.1f, 0.4f, 0.9f}, neon.emissive());
        assertEquals(6.0f, neon.emissiveStrength());

        primitive.resetMaterial();
        assertSame(asset.materials().get(0), primitive.effectiveMaterial());
        primitive.variant("neon");
        assertSame(neon, primitive.effectiveMaterial());
        assertThrows(IndexOutOfBoundsException.class, () -> primitive.material(2));
    }

    @Test
    void appliesTransformMorphVisibilityAndParameterStateWithoutMutatingAsset() {
        GltfAsset asset = asset();
        GltfNodeManager nodes = new GltfNodeManager(asset);

        nodes.node(0)
            .translation(2.0f, 3.0f, 4.0f)
            .selfVisible(false)
            .subtreeVisible(true)
            .alpha(0.5f)
            .parameter("channel", "warning");

        float[] world = nodes.computeWorldMatrices(new ModelPose(asset));
        assertEquals(2.0f, world[12]);
        assertEquals(3.0f, world[13]);
        assertEquals(4.0f, world[14]);
        assertEquals(3.0f, world[16 + 12]);
        assertFalse(nodes.node(0).selfVisible());
        assertTrue(nodes.node(0).subtreeVisible());
        assertEquals("warning", nodes.node(0).parameter("channel", String.class).orElseThrow());
        assertEquals("/root/panel", nodes.node(1).path());

        GltfNodeManager.Snapshot snapshot = nodes.snapshot();
        nodes.resetStates();
        nodes.restore(snapshot);
        assertFalse(nodes.node(0).selfVisible());
        assertEquals(0.5f, nodes.node(0).alpha());
        assertEquals("warning", nodes.node(0).parameter("channel", String.class).orElseThrow());

        // The immutable asset remains at its authored bind transform.
        assertArrayEquals(new float[]{0, 0, 0}, asset.nodes().get(0).translation());
        nodes.resetStates();
        assertTrue(nodes.node(0).selfVisible());
        assertEquals(1.0f, nodes.node(0).alpha());
    }

    @Test
    void cachedWorldMatricesStillObserveDirectPoseArrayEdits() {
        GltfAsset asset = asset();
        GltfNodeManager nodes = new GltfNodeManager(asset);
        ModelPose pose = new ModelPose(asset);

        float[] first = nodes.computeWorldMatricesView(pose, 0L);
        assertEquals(0.0f, first[12]);

        // NodePose arrays are intentionally mutable public API. The renderer cache must not make
        // this historical usage silently stale.
        pose.node(0).translation()[0] = 7.0f;
        float[] second = nodes.computeWorldMatricesView(pose, 0L);
        assertSame(first, second);
        assertEquals(7.0f, second[12]);
    }

    @Test
    void updatesOnlyJointMatricesAffectedByTheResolvedWorldTransforms() {
        float[] inverse = new float[32];
        new Matrix4f().get(inverse, 0);
        new Matrix4f().translation(-0.25f, 0.1f, 0.0f).get(inverse, 16);
        GltfSkin skin = new GltfSkin("rig", 1, new int[]{1, 2}, inverse);
        int[] empty = new int[0];
        List<GltfNode> definitions = List.of(
            new GltfNode("mesh", -1, empty, empty, -1, null, null, null, null, null),
            new GltfNode("parent", -1, new int[]{2}, empty, -1, null, null, null, null, null),
            new GltfNode("joint", 1, empty, empty, -1, null, null, null, null, null),
            new GltfNode("unrelated", -1, empty, empty, -1, null, null, null, null, null));
        GltfAsset asset = new GltfAsset(ResourceLocation.fromNamespaceAndPath("test", "incremental_skin"),
            List.of(new GltfScene("scene", new int[]{0, 1, 3})), definitions, List.of(),
            List.of(GltfMaterial.defaultMaterial()), List.of(), List.of(skin), List.of(), List.of(), List.of());
        GltfNodeManager nodes = new GltfNodeManager(asset);
        AnimationController animation = new AnimationController(asset);
        nodes.node(2).translation(0.5f, 0.0f, 0.0f);
        checkPalette(nodes, skin, animation, 2);
        nodes.node(3).translation(1.0f, 2.0f, 3.0f);
        checkPalette(nodes, skin, animation, 0);
        nodes.node(2).translation(0.7f, 0.0f, 0.0f);
        checkPalette(nodes, skin, animation, 1);
        nodes.node(1).translation(0.37f, 0.2f, 0.8f);
        checkPalette(nodes, skin, animation, 2);
        nodes.node(0).scale(0.5f, 0.75f, 1.2f);
        checkPalette(nodes, skin, animation, 2);
        nodes.resetStates();
        checkPalette(nodes, skin, animation, 2);

        ModelPose writable = animation.pose();
        writable.node(2).translation()[1] = 0.08f;
        checkPalette(nodes, skin, animation, 2);
        writable.node(2).translation()[1] = 0.52f;
        checkPalette(nodes, skin, animation, 1);
    }

    private static void checkPalette(GltfNodeManager nodes, GltfSkin skin, AnimationController animation,
                                     long expectedUpdates) {
        long before = nodes.skinMatrixComputations();
        float[] world = nodes.computeWorldMatricesView(animation);
        float[] incremental = nodes.computeSkinPaletteView(skin, 0, world);
        assertEquals(expectedUpdates, nodes.skinMatrixComputations() - before);
        assertArrayEquals(PoseTransforms.computeSkinPalette(skin, 0, world), incremental, 1.0e-6f);
    }

    @Test
    void sharesBindMatricesAndUpdatesOnlyTheAffectedPlacement() {
        GltfAsset asset = asset();
        GltfInstance first = new GltfInstance(asset);
        GltfInstance other = new GltfInstance(asset);
        float[] shared = first.nodes().computeWorldMatricesView(first.animations());
        assertSame(shared, other.nodes().computeWorldMatricesView(other.animations()));

        first.setNodeModelMatrix(1, new Matrix4f().translation(8, 0, 0).get(new float[16]));
        assertEquals(8.0f, first.nodeModelMatrix(1)[12]);
        first.nodes().node(0).translation(2, 0, 0);
        assertEquals(10.0f, first.nodeModelMatrix(1)[12]);
        assertEquals(1.0f, other.nodeModelMatrix(1)[12]);

        List<Integer> moved = new ArrayList<>();
        first.nodes().forEachMovedNode(first.animations(), moved::add);
        assertEquals(List.of(0, 1), moved);
        first.nodes().node(0).clearTranslationOverride();
        first.nodes().node(1).clearTransformOverrides();
        assertSame(shared, first.nodes().computeWorldMatricesView(first.animations()));
    }

    @Test
    void legacyEditableControllerPoseAndVisibilityRemainObservable() {
        GltfInstance instance = new GltfInstance(asset());
        instance.nodeModelMatrix(1);
        ModelPose pose = instance.animations().pose();
        pose.node(0).translation()[0] = 6;
        assertEquals(7.0f, instance.nodeModelMatrix(1)[12]);

        instance.nodes().node(0).subtreeVisible(false);
        assertFalse(instance.nodes().isSubtreeVisible(1));
        assertTrue(instance.nodeVisible(1));
        instance.nodes().node(0).subtreeVisible(true);
        assertTrue(instance.nodes().isSubtreeVisible(1));
    }

    private static GltfAsset asset() {
        GltfMaterial normal = GltfMaterial.defaultMaterial();
        GltfMaterial blue = new GltfMaterial(
            "blue",
            new float[]{0.1f, 0.4f, 0.9f, 1.0f},
            -1, 0,
            new float[]{0, 0, 0}, -1, 0,
            AlphaMode.OPAQUE, 0.5f, false
        );
        GltfPrimitive primitive = new GltfPrimitive(
            PrimitiveMode.TRIANGLES,
            0,
            3,
            new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
            null, null, null, null, null, null, null, null,
            List.of()
        );
        GltfMesh mesh = new GltfMesh("panel", List.of(primitive), null);
        GltfNode root = new GltfNode(
            "root", -1, new int[]{1}, new int[]{0}, -1,
            null, null, null, null, null
        );
        GltfNode child = new GltfNode(
            "panel", 0, new int[0], new int[0], -1,
            null, new float[]{1, 0, 0}, null, null, null
        );
        return new GltfAsset(
            ResourceLocation.fromNamespaceAndPath("gfbs_gltf", "node_manager_test"),
            List.of(new GltfScene("scene", new int[]{0})),
            List.of(root, child),
            List.of(mesh),
            List.of(normal, blue),
            List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }
}
