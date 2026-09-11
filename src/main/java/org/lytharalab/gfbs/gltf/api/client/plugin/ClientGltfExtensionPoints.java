package org.lytharalab.gfbs.gltf.api.client.plugin;

import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.plugin.GltfExtensionPoint;

/** Client-only extension points implemented by the renderer and resource manager. */
public final class ClientGltfExtensionPoints {
    public static final GltfExtensionPoint<GltfRenderExtension> RENDER_EXTENSIONS =
        GltfExtensionPoint.multiple(
            ResourceLocation.fromNamespaceAndPath("gfbs_gltf", "render_extensions"),
            GltfRenderExtension.class
        );
    public static final GltfExtensionPoint<GltfClientResourceReloadListener> RESOURCE_RELOAD_LISTENERS =
        GltfExtensionPoint.multiple(
            ResourceLocation.fromNamespaceAndPath(
                "gfbs_gltf", "client_resource_reload_listeners"
            ),
            GltfClientResourceReloadListener.class
        );

    private ClientGltfExtensionPoints() {
    }
}
