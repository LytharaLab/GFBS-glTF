package org.lytharalab.gfbs.gltf.client.render;

import org.lytharalab.gfbs.gltf.GFBSglTF;

import java.lang.reflect.*;

/** Registers GFBS runtime textures with Oculus' public PBR loader registry without a hard dependency. */
final class OculusPbrTextureBridge {
    private static boolean attempted;
    private static boolean available;

    private OculusPbrTextureBridge() {
    }

    static synchronized boolean install() {
        if (attempted) return available;
        attempted = true;
        if (!OculusCompat.installed()) return false;
        try {
            ClassLoader loader = OculusPbrTextureBridge.class.getClassLoader();
            String prefix = OculusCompat.namespace() + ".texture.pbr.loader.";
            Class<?> registryType = Class.forName(prefix + "PBRTextureLoaderRegistry", false, loader);
            Class<?> pbrLoaderType = Class.forName(prefix + "PBRTextureLoader", false, loader);
            Object registry = registryType.getField("INSTANCE").get(null);
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{pbrLoaderType}, OculusPbrTextureBridge::invokeLoader);
            registryType.getMethod("register", Class.class, pbrLoaderType)
                .invoke(registry, GltfMaterialTexture.class, proxy);
            available = true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            GFBSglTF.LOGGER.warn("Unable to register glTF LabPBR textures with Iris/Oculus; "
                + "shader-pack rendering will retain geometry and light compatibility but use default PBR maps", exception);
        }
        return available;
    }

    private static Object invokeLoader(Object proxy, Method method, Object[] args) throws ReflectiveOperationException {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "GFBS:glTF Oculus PBR loader";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            };
        }
        if (!method.getName().equals("load") || args == null || args.length != 3) return null;
        GltfMaterialTexture texture = (GltfMaterialTexture) args[0];
        Object consumer = args[2];
        Class<?> consumerType = method.getParameterTypes()[2];
        Method normalMethod = consumerType.getMethod("acceptNormalTexture",
            net.minecraft.client.renderer.texture.AbstractTexture.class);
        Method specularMethod = consumerType.getMethod("acceptSpecularTexture",
            net.minecraft.client.renderer.texture.AbstractTexture.class);
        GltfUploadedTexture normal = null;
        GltfUploadedTexture specular = null;
        try {
            normal = texture.loadNormal();
            specular = texture.loadSpecular();
            normalMethod.invoke(consumer, normal);
            normal = null; // Ownership passed to the holder.
            specularMethod.invoke(consumer, specular);
            specular = null;
        } finally {
            if (normal != null) normal.close();
            if (specular != null) specular.close();
        }
        return null;
    }
}
