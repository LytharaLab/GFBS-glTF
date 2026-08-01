package org.lytharalab.gfbs.gltf;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(GFBSglTF.MODID)
public final class GFBSglTF {
    public static final String MODID = "gfbs_gltf";
    public static final Logger LOGGER = LogUtils.getLogger();

    public GFBSglTF() {
        LOGGER.info("Initializing GFBS:glTF 1.1.2");
    }
}
