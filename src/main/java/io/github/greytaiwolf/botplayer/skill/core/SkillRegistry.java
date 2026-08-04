package io.github.greytaiwolf.botplayer.skill.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 保存有界、显式版本的技能描述；同一版本不能被静默替换。
 */
public final class SkillRegistry {
    public static final int MAX_DESCRIPTORS = 4_096;

    private final int maximumDescriptors;
    private final Map<SkillId, NavigableMap<SkillVersion, SkillDescriptor>>
            descriptors = new LinkedHashMap<>();
    private int size;

    public SkillRegistry() {
        this(MAX_DESCRIPTORS);
    }

    public SkillRegistry(int maximumDescriptors) {
        if (maximumDescriptors < 1
                || maximumDescriptors > MAX_DESCRIPTORS) {
            throw new IllegalArgumentException(
                    "maximumDescriptors must be between 1 and "
                            + MAX_DESCRIPTORS);
        }
        this.maximumDescriptors = maximumDescriptors;
    }

    public synchronized RegisterStatus register(
            SkillDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        NavigableMap<SkillVersion, SkillDescriptor> versions =
                descriptors.get(descriptor.id());
        SkillDescriptor existing = versions == null
                ? null
                : versions.get(descriptor.version());
        if (existing != null) {
            return existing.equals(descriptor)
                    ? RegisterStatus.ALREADY_REGISTERED
                    : RegisterStatus.VERSION_CONFLICT;
        }
        if (size >= maximumDescriptors) {
            return RegisterStatus.CAPACITY_EXCEEDED;
        }
        if (versions == null) {
            versions = new TreeMap<>();
            descriptors.put(descriptor.id(), versions);
        }
        versions.put(descriptor.version(), descriptor);
        size++;
        return RegisterStatus.REGISTERED;
    }

    public synchronized Optional<SkillDescriptor> find(
            SkillId id, SkillVersion version) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        NavigableMap<SkillVersion, SkillDescriptor> versions =
                descriptors.get(id);
        return versions == null
                ? Optional.empty()
                : Optional.ofNullable(versions.get(version));
    }

    public synchronized boolean containsId(SkillId id) {
        return descriptors.containsKey(
                Objects.requireNonNull(id, "id"));
    }

    public synchronized Optional<SkillDescriptor> latest(
            SkillId id) {
        NavigableMap<SkillVersion, SkillDescriptor> versions =
                descriptors.get(Objects.requireNonNull(id, "id"));
        return versions == null || versions.isEmpty()
                ? Optional.empty()
                : Optional.of(versions.lastEntry().getValue());
    }

    public synchronized List<SkillDescriptor> descriptors() {
        List<SkillDescriptor> snapshot = new ArrayList<>(size);
        descriptors.values().forEach(versions ->
                snapshot.addAll(versions.values()));
        snapshot.sort(
                Comparator.comparing(SkillDescriptor::id)
                        .thenComparing(SkillDescriptor::version));
        return List.copyOf(snapshot);
    }

    public synchronized int size() {
        return size;
    }

    public enum RegisterStatus {
        REGISTERED,
        ALREADY_REGISTERED,
        VERSION_CONFLICT,
        CAPACITY_EXCEEDED
    }
}
