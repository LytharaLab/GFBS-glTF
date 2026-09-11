package org.lytharalab.gfbs.gltf.api.plugin;

import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.io.ModelImporter;
import org.lytharalab.gfbs.gltf.api.plugin.io.GltfAssetProcessor;

/** Side-neutral extension points integrated into the runtime. */
public final class GltfExtensionPoints {
    public static final GltfExtensionPoint<ModelImporter> MODEL_IMPORTERS =
        GltfExtensionPoint.multiple(
            ResourceLocation.fromNamespaceAndPath("gfbs_gltf", "model_importers"), ModelImporter.class
        );
    public static final GltfExtensionPoint<GltfAssetProcessor> ASSET_PROCESSORS =
        GltfExtensionPoint.multiple(
            ResourceLocation.fromNamespaceAndPath("gfbs_gltf", "asset_processors"), GltfAssetProcessor.class
        );

    private GltfExtensionPoints() {
    }
}
