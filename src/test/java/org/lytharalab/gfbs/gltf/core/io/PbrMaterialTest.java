package org.lytharalab.gfbs.gltf.core.io;

import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.model.*;
import static org.junit.jupiter.api.Assertions.*;

class PbrMaterialTest {
    @Test void retainsEveryCoreMetallicRoughnessInput() {
        GltfMaterial m=new GltfMaterial("pbr",new float[]{1,.5f,.25f,1},0,0,.7f,.3f,1,1,2,0,.8f,3,1,.6f,new float[]{.1f,.2f,.3f},4,0,AlphaMode.MASK,.4f,true);
        assertEquals(.7f,m.metallicFactor());assertEquals(.3f,m.roughnessFactor());assertEquals(2,m.normalTexture());assertEquals(.6f,m.occlusionStrength());
        assertThrows(IllegalArgumentException.class,()->new GltfMaterial("bad",null,-1,0,2,1,-1,0,-1,0,1,-1,0,1,null,-1,0,AlphaMode.OPAQUE,.5f,false));
        GltfMaterial flippedNormal = new GltfMaterial("flipped", null, -1, 0, 0, 1, -1, 0, -1, 0, -1, -1, 0, 1, null, -1, 0, AlphaMode.OPAQUE, .5f, false);
        assertEquals(-1.0f, flippedNormal.normalScale());
    }
}
