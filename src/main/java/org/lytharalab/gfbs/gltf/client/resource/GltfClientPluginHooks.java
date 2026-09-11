package org.lytharalab.gfbs.gltf.client.resource;

import net.minecraft.server.packs.resources.ResourceManager;
import org.lytharalab.gfbs.gltf.GFBSglTF;
import org.lytharalab.gfbs.gltf.api.client.plugin.ClientGltfExtensionPoints;
import org.lytharalab.gfbs.gltf.api.client.plugin.GltfClientResourceReloadListener;
import org.lytharalab.gfbs.gltf.api.plugin.GltfExtensionEntry;
import org.lytharalab.gfbs.gltf.api.plugin.GltfPlugins;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class GltfClientPluginHooks {
    private static final Set<GltfClientResourceReloadListener> QUARANTINED =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    private GltfClientPluginHooks() {
    }

    static void beforeReload(ResourceManager resources) {
        invoke(resources, true);
    }

    static void afterReload(ResourceManager resources) {
        invoke(resources, false);
    }

    private static void invoke(ResourceManager resources, boolean before) {
        for (GltfExtensionEntry<GltfClientResourceReloadListener> entry
            : GltfPlugins.extensionEntries(ClientGltfExtensionPoints.RESOURCE_RELOAD_LISTENERS)) {
            GltfClientResourceReloadListener listener = entry.extension();
            if (QUARANTINED.contains(listener)) continue;
            try {
                if (before) listener.beforeGltfResourcesReload(resources);
                else listener.afterGltfResourcesReload(resources);
            } catch (Throwable failure) {
                QUARANTINED.add(listener);
                GFBSglTF.LOGGER.error(
                    "Quarantined resource reload extension from plugin {} after {} hook failed",
                    entry.pluginId(), before ? "before" : "after", failure
                );
            }
        }
    }
}
