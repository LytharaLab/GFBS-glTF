package org.lytharalab.gfbs.gltf.core.animation;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.animation.*;
import org.lytharalab.gfbs.gltf.api.model.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AnimationMixerTest {
    @Test void layersMasksFadesAndEventsAreDeterministic() {
        AnimationClip x=clip("x",AnimationPath.TRANSLATION,new float[]{0,0,0,2,0,0});
        AnimationClip y=clip("y",AnimationPath.TRANSLATION,new float[]{0,0,0,0,4,0});
        AnimationController c=new AnimationController(asset(x,y));
        c.play("x",PlaybackOptions.loop());
        c.playLayer("detail","y",PlaybackOptions.loop(),.5f,AnimationBlendMode.ADDITIVE,AnimationMask.ofNodes(1,0));
        AtomicInteger events=new AtomicInteger();c.addEvent("x",new AnimationEvent("clunk",.5f));c.addEventListener((l,a,e)->events.incrementAndGet());
        c.update(1);assertArrayEquals(new float[]{1,1,0},c.pose().node(0).translation(),1e-5f);assertEquals(1,events.get());
        c.fadeLayer("detail",0,1);c.update(.5f);assertEquals(.25f,c.layer("detail").orElseThrow().weight(),1e-5f);
    }
    private static AnimationClip clip(String name,AnimationPath path,float[] values){return new AnimationClip(name,List.of(new AnimationChannel(0,path,new AnimationSampler(new float[]{0,2},values,3,Interpolation.LINEAR))));}
    private static GltfAsset asset(AnimationClip... clips){GltfPrimitive p=new GltfPrimitive(PrimitiveMode.TRIANGLES,0,3,new float[]{0,0,0,1,0,0,0,1,0},null,null,null,null,null,null,null,new int[]{0,1,2},List.of());GltfMesh m=new GltfMesh("mesh",List.of(p),null);GltfNode n=new GltfNode("node",-1,new int[0],new int[]{0},-1,null,null,null,null,null);return new GltfAsset(ResourceLocation.fromNamespaceAndPath("test","mixer"),List.of(new GltfScene("scene",new int[]{0})),List.of(n),List.of(m),List.of(GltfMaterial.defaultMaterial()),List.of(),List.of(),List.of(clips),List.of(),List.of());}
}
