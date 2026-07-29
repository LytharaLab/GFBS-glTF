package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** Base texture with runtime-generated LabPBR companions consumed by Oculus. */
final class GltfMaterialTexture extends DynamicTexture {
    private final DynamicTexture normalTexture;
    private final DynamicTexture specularTexture;

    GltfMaterialTexture(NativeImage base, NativeImage normal, NativeImage specular) {
        super(base);
        this.normalTexture = new DynamicTexture(normal);
        this.specularTexture = new DynamicTexture(specular);
    }

    DynamicTexture normalTexture() { return normalTexture; }
    DynamicTexture specularTexture() { return specularTexture; }

    void closeCompanions() {
        normalTexture.close();
        specularTexture.close();
    }
}
