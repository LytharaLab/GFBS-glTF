package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfTexture;
import org.lytharalab.gfbs.gltf.api.model.GltfTextureInfo;

/** Base image recognized by the Iris PBR registry. Iris owns each pair created by its loader. */
final class GltfMaterialTexture extends GltfUploadedTexture {
    private final GltfGpuTextures textures;
    private final GltfMaterial material;
    private final GltfTextureInfo anchor;
    private final GltfMaterialBaker.Page page;

    GltfMaterialTexture(NativeImage image, GltfTexture sampler, GltfGpuTextures textures,
                        GltfMaterial material, GltfTextureInfo anchor) {
        super(image, sampler, false);
        this.textures = textures;
        this.material = material;
        this.anchor = anchor;
        this.page = null;
    }

    GltfMaterialTexture(NativeImage image, GltfGpuTextures textures, GltfMaterialBaker.Page page) {
        super(image, 9729, 9987, 33071, 33071, false, page.maxMip);
        this.textures = textures;
        this.page = page;
        this.material = null;
        this.anchor = null;
    }

    GltfUploadedTexture loadNormal() {
        return page == null ? textures.companion(material, anchor, false) : textures.companion(page, 1);
    }
    GltfUploadedTexture loadSpecular() {
        return page == null ? textures.companion(material, anchor, true) : textures.companion(page, 2);
    }
}
