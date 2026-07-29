package org.lytharalab.gfbs.gltf.api.client;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfScene;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfInstanceTest {
    @Test
    void controlsNodeSubtreeVisibilityWithoutMutatingTheAsset() {
        GltfInstance instance = new GltfInstance(asset());

        assertTrue(instance.nodeVisible(0));
        assertTrue(instance.nodeVisible(1));
        assertEquals(1, instance.setNodeVisible("magazine", false));
        assertFalse(instance.nodeVisible(1));
        assertTrue(instance.nodeVisible(0));

        instance.setNodeVisible(0, false);
        assertFalse(instance.nodeVisible(0));
        instance.resetNodeVisibility();
        assertTrue(instance.nodeVisible(0));
        assertTrue(instance.nodeVisible(1));
        assertThrows(IndexOutOfBoundsException.class, () -> instance.nodeVisible(2));
    }

    private static GltfAsset asset() {
        GltfNode root = new GltfNode(
            "root",
            -1,
            new int[]{1},
            new int[0],
            -1,
            null,
            null,
            null,
            null,
            null
        );
        GltfNode magazine = new GltfNode(
            "magazine",
            0,
            new int[0],
            new int[0],
            -1,
            null,
            null,
            null,
            null,
            null
        );
        return new GltfAsset(
            ResourceLocation.fromNamespaceAndPath("gfbs_gltf", "instance_test"),
            List.of(new GltfScene("scene", new int[]{0})),
            List.of(root, magazine),
            List.of(),
            List.of(GltfMaterial.defaultMaterial()),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }
}
