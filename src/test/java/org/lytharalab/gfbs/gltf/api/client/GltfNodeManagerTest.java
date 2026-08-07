package org.lytharalab.gfbs.gltf.api.client;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
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
import org.lytharalab.gfbs.gltf.api.model.PrimitiveMode;

import java.util.List;

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
