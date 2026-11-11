package org.lytharalab.gfbs.gltf.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lytharalab.gfbs.gltf.GFBSglTF;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderContext;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Optional Iris v0 API bridge, including both Oculus package namespaces. */
final class OculusCompat {
    private static final String[] NAMESPACES = {"net.irisshaders.iris", "net.coderbot.iris"};
    private static final Api API = resolve();

    private OculusCompat() {}

    static String namespace() { return API == null ? NAMESPACES[0] : API.namespace; }
    static boolean installed() { return API != null; }
    static boolean shadersEnabled() { return API != null && invoke(API.shaderPack); }
    static boolean shadowPass() { return API != null && invoke(API.shadowPass); }
    static GltfRenderContext shadowContext() { return ShadowCull.context(); }

    private static boolean invoke(MethodHandle method) {
        try {
            return (boolean) method.invokeExact();
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError error) throw error;
            return false;
        }
    }

    private static Api resolve() {
        if (!ModList.get().isLoaded("oculus") && !ModList.get().isLoaded("iris")) return null;
        Throwable failure = null;
        for (String namespace : NAMESPACES) {
            try {
                Class<?> type = Class.forName(namespace + ".api.v0.IrisApi", false,
                    OculusCompat.class.getClassLoader());
                Object api = type.getMethod("getInstance").invoke(null);
                var lookup = MethodHandles.publicLookup();
                var bool = MethodType.methodType(boolean.class);
                return new Api(namespace,
                    lookup.findVirtual(type, "isShaderPackInUse", bool).bindTo(api),
                    lookup.findVirtual(type, "isRenderingShadowPass", bool).bindTo(api));
            } catch (ReflectiveOperationException | LinkageError exception) {
                failure = exception;
            }
        }
        GFBSglTF.LOGGER.warn("Iris/Oculus v0 API is unavailable; using the vanilla rendering path", failure);
        return null;
    }

    private record Api(String namespace, MethodHandle shaderPack, MethodHandle shadowPass) {}

    /** Uses the pack's own entity frustum (including reversed/voxelization frusta). */
    private static final class ShadowCull {
        private static final Bridge BRIDGE = Bridge.resolve();
        private static Matrix4f lastView;
        private static GltfRenderContext lastContext;

        static GltfRenderContext context() {
            if (BRIDGE == null) return null;
            try {
                Matrix4f view = (Matrix4f) BRIDGE.view.get(null);
                if (view == lastView) return lastContext;
                lastView = view;
                lastContext = null;
                Object pipeline = BRIDGE.activePipeline.invoke(BRIDGE.manager);
                if (view == null || !BRIDGE.renderer.getDeclaringClass().isInstance(pipeline)) return null;
                Object renderer = BRIDGE.renderer.get(pipeline);
                if (renderer == null) return null;
                Object holder = BRIDGE.entityFrustum.get(renderer);
                if (holder == null) return null;
                Frustum frustum = (Frustum) BRIDGE.getFrustum.invoke(holder);
                if (frustum == null) return null;
                var position = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                Matrix4f inverse = new Matrix4f(view).invertAffine();
                return lastContext = new GltfRenderContext(null, frustum,
                    position.x + inverse.m30(), position.y + inverse.m31(), position.z + inverse.m32(),
                    new Matrix3f(inverse));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                return null;
            }
        }

        private record Bridge(Object manager, Method activePipeline, Field renderer, Field entityFrustum,
                              Field view, Method getFrustum) {
            static Bridge resolve() {
                if (API == null) return null;
                try {
                    String namespace = API.namespace;
                    ClassLoader loader = OculusCompat.class.getClassLoader();
                    Class<?> iris = Class.forName(namespace + ".Iris", false, loader);
                    Class<?> pipeline = Class.forName(namespace + ".pipeline.IrisRenderingPipeline", false, loader);
                    Object manager = iris.getMethod("getPipelineManager").invoke(null);
                    Field renderer = pipeline.getDeclaredField("shadowRenderer");
                    Field frustum = renderer.getType().getDeclaredField("entityFrustumHolder");
                    if (!renderer.trySetAccessible() || !frustum.trySetAccessible()) return null;
                    return new Bridge(manager, manager.getClass().getMethod("getPipelineNullable"),
                        renderer, frustum, renderer.getType().getField("MODELVIEW"),
                        frustum.getType().getMethod("getFrustum"));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    return null;
                }
            }
        }
    }
}
