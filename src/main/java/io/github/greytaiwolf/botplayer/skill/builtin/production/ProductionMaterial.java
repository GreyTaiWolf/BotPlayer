package io.github.greytaiwolf.botplayer.skill.builtin.production;

import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.Objects;

/**
 * 生产规划中一个可计数的原版资源身份。
 *
 * <p>它只保存稳定资源标识，不持有 {@code ItemStack}、物品注册表或世界引用；Minecraft
 * 适配层必须先把真实观察归一化成这份身份和数量，规划层才会接受它。
 */
public record ProductionMaterial(ResourceId id)
        implements Comparable<ProductionMaterial> {
    public ProductionMaterial {
        Objects.requireNonNull(id, "id");
    }

    public static ProductionMaterial of(String id) {
        return new ProductionMaterial(new ResourceId(id));
    }

    public static ProductionMaterial minecraft(String path) {
        Objects.requireNonNull(path, "path");
        return of("minecraft:" + path);
    }

    @Override
    public int compareTo(ProductionMaterial other) {
        Objects.requireNonNull(other, "other");
        return id.value().compareTo(other.id.value());
    }
}
