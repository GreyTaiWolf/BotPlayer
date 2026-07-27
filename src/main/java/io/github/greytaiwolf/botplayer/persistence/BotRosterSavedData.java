package io.github.greytaiwolf.botplayer.persistence;

import io.github.greytaiwolf.botplayer.identity.BotIdentityIds;
import io.github.greytaiwolf.botplayer.profile.BotProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Overworld SavedData containing BotPlayer's persistent server identity and bot roster.
 *
 * <p>Secrets and client credential identifiers must never be stored here.
 */
public final class BotRosterSavedData extends SavedData {
    public static final int SCHEMA_VERSION = 1;
    public static final String DATA_NAME = "botplayer_roster";

    private static final String SCHEMA_VERSION_TAG = "SchemaVersion";
    private static final String SERVER_INSTANCE_ID_TAG = "ServerInstanceId";
    private static final String PROFILES_TAG = "Profiles";

    private final UUID serverInstanceId;
    private final Map<UUID, BotProfile> profilesById;
    private final Map<String, UUID> idsByNormalizedName;

    private BotRosterSavedData() {
        this(UUID.randomUUID(), Map.of());
        setDirty();
    }

    private BotRosterSavedData(UUID serverInstanceId, Map<UUID, BotProfile> profiles) {
        this.serverInstanceId = Objects.requireNonNull(serverInstanceId, "serverInstanceId");
        this.profilesById = new LinkedHashMap<>();
        this.idsByNormalizedName = new LinkedHashMap<>();

        for (BotProfile profile : profiles.values()) {
            addLoadedProfile(profile);
        }
    }

    public static SavedData.Factory<BotRosterSavedData> factory() {
        return new SavedData.Factory<>(
                BotRosterSavedData::new,
                BotRosterSavedData::load);
    }

    public static BotRosterSavedData get(MinecraftServer server) {
        return server.overworld()
                .getDataStorage()
                .computeIfAbsent(factory(), DATA_NAME);
    }

    public UUID serverInstanceId() {
        return serverInstanceId;
    }

    public Optional<BotProfile> findById(UUID botId) {
        return Optional.ofNullable(profilesById.get(botId));
    }

    public Optional<BotProfile> findByName(String name) {
        UUID botId = idsByNormalizedName.get(normalizeName(name));
        return botId == null ? Optional.empty() : findById(botId);
    }

    /**
     * Returns the existing canonical profile or creates it once with the proposed owner.
     *
     * <p>If the profile already exists, {@code proposedOwnerId} is deliberately ignored.
     */
    public BotProfile getOrCreate(String requestedName, @Nullable UUID proposedOwnerId) {
        Optional<BotProfile> existingByName = findByName(requestedName);
        if (existingByName.isPresent()) {
            return existingByName.get();
        }

        UUID botId = BotIdentityIds.forImmutableName(requestedName);
        BotProfile existingById = profilesById.get(botId);
        if (existingById != null) {
            if (!existingById.normalizedName().equals(normalizeName(requestedName))) {
                throw new IllegalStateException("Bot identity collision in the persistent roster");
            }
            return existingById;
        }

        BotProfile created = new BotProfile(
                botId,
                requestedName,
                Optional.ofNullable(proposedOwnerId));
        addLoadedProfile(created);
        setDirty();
        return created;
    }

    public List<BotProfile> profiles() {
        return List.copyOf(profilesById.values());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt(SCHEMA_VERSION_TAG, SCHEMA_VERSION);
        tag.put(SERVER_INSTANCE_ID_TAG, NbtUtils.createUUID(serverInstanceId));

        List<BotProfile> sortedProfiles = new ArrayList<>(profilesById.values());
        sortedProfiles.sort(Comparator.comparing(BotProfile::normalizedName)
                .thenComparing(BotProfile::botId));
        ListTag profilesTag = new ListTag();
        for (BotProfile profile : sortedProfiles) {
            profilesTag.add(profile.save());
        }
        tag.put(PROFILES_TAG, profilesTag);
        return tag;
    }

    public static BotRosterSavedData load(
            CompoundTag tag, HolderLookup.Provider provider) {
        int schemaVersion = tag.getInt(SCHEMA_VERSION_TAG);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "Unsupported BotPlayer roster schema version " + schemaVersion);
        }
        if (!tag.contains(SERVER_INSTANCE_ID_TAG, Tag.TAG_INT_ARRAY)) {
            throw new IllegalStateException("BotPlayer roster is missing its server instance id");
        }

        UUID serverInstanceId = NbtUtils.loadUUID(tag.get(SERVER_INSTANCE_ID_TAG));
        Map<UUID, BotProfile> profiles = new LinkedHashMap<>();
        ListTag profilesTag = tag.getList(PROFILES_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < profilesTag.size(); index++) {
            BotProfile profile = BotProfile.load(profilesTag.getCompound(index));
            if (profiles.putIfAbsent(profile.botId(), profile) != null) {
                throw new IllegalStateException("BotPlayer roster contains a duplicate bot id");
            }
        }
        return new BotRosterSavedData(serverInstanceId, profiles);
    }

    private void addLoadedProfile(BotProfile profile) {
        BotProfile previousProfile = profilesById.putIfAbsent(profile.botId(), profile);
        if (previousProfile != null) {
            throw new IllegalStateException("BotPlayer roster contains a duplicate bot id");
        }

        UUID previousId =
                idsByNormalizedName.putIfAbsent(profile.normalizedName(), profile.botId());
        if (previousId != null) {
            profilesById.remove(profile.botId());
            throw new IllegalStateException("BotPlayer roster contains a duplicate bot name");
        }
    }

    private static String normalizeName(String name) {
        return Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
    }
}
