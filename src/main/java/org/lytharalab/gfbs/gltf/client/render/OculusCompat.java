package org.lytharalab.gfbs.gltf.client.render;

import net.minecraftforge.fml.ModList;
import org.lytharalab.gfbs.gltf.GFBSglTF;

import java.lang.reflect.Method;

/** Optional Oculus/Iris API bridge. No Oculus class is linked on servers or when the mod is absent. */
final class OculusCompat {
    private static final String[] API_CLASSES = {
        "net.irisshaders.iris.api.v0.IrisApi",
        "net.coderbot.iris.api.v0.IrisApi"
    };
    private static final boolean INSTALLED = ModList.get().isLoaded("oculus") || ModList.get().isLoaded("iris");
    private static boolean resolved;
    private static Object api;
    private static Method shaderPackInUse;
    private static Method shadowPass;

    private OculusCompat() {
    }

    static boolean installed() { return INSTALLED; }

    static boolean shadersEnabled() {
        resolve();
        return invoke(shaderPackInUse);
    }

    static boolean shadowPass() {
        resolve();
        return invoke(shadowPass);
    }

    private static boolean invoke(Method method) {
        if (api == null || method == null) return false;
        try {
            return Boolean.TRUE.equals(method.invoke(api));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        if (!INSTALLED) return;
        Throwable failure = null;
        for (String className : API_CLASSES) {
            try {
                Class<?> type = Class.forName(className, false, OculusCompat.class.getClassLoader());
                api = type.getMethod("getInstance").invoke(null);
                shaderPackInUse = type.getMethod("isShaderPackInUse");
                shadowPass = type.getMethod("isRenderingShadowPass");
                return;
            } catch (ReflectiveOperationException | LinkageError exception) {
                failure = exception;
            }
        }
        GFBSglTF.LOGGER.warn("Oculus/Iris was detected but its v0 compatibility API is unavailable; "
            + "GFBS:glTF will keep using Minecraft's vanilla entity-shader path without LabPBR companions",
            failure);
        api = null;
    }
}
