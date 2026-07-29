package org.lytharalab.gfbs.gltf.api.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.UUID;

public record AnimationTargetKey(ResourceLocation dimension, Kind kind, String id) {
    public enum Kind { ENTITY, BLOCK_ENTITY, CUSTOM }

    public AnimationTargetKey {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || id.length() > 512) throw new IllegalArgumentException("Invalid animation target ID");
        for (int i = 0; i < id.length(); i++) {
            if (Character.isISOControl(id.charAt(i))) throw new IllegalArgumentException("Animation target ID contains control characters");
        }
    }

    public static AnimationTargetKey entity(Level level, UUID id) {
        Objects.requireNonNull(level, "level");
        return new AnimationTargetKey(level.dimension().location(), Kind.ENTITY, Objects.requireNonNull(id, "id").toString());
    }

    public static AnimationTargetKey blockEntity(Level level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        return new AnimationTargetKey(level.dimension().location(), Kind.BLOCK_ENTITY,
            Long.toUnsignedString(Objects.requireNonNull(pos, "pos").asLong()));
    }

    public static AnimationTargetKey custom(Level level, ResourceLocation id) {
        Objects.requireNonNull(level, "level");
        return new AnimationTargetKey(level.dimension().location(), Kind.CUSTOM, Objects.requireNonNull(id, "id").toString());
    }
}
