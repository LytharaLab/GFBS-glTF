package org.lytharalab.gfbs.gltf.api.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lytharalab.gfbs.gltf.client.resource.ClientGltfModels;

@OnlyIn(Dist.CLIENT)
public final class ClientGltfApi {
    private ClientGltfApi() {
    }

    public static GltfModelManager models() { return ClientGltfModels.getInstance(); }
}
