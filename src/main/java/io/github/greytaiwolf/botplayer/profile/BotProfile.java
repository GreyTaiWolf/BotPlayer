package io.github.greytaiwolf.botplayer.profile;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * Persistent, server-authoritative identity and ownership for one bot.
 *
 * <p>The owner is assigned only when the profile is first created. Re-spawning a bot must reuse this
 * profile rather than deriving ownership from the current command source.
 */
public record BotProfile(UUID botId, String name, Optional<UUID> ownerId) {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private static final String BOT_ID_TAG = "BotId";
    private static final String NAME_TAG = "Name";
    private static final String OWNER_ID_TAG = "OwnerId";

    public BotProfile {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(name, "name");
        ownerId = Objects.requireNonNull(ownerId, "ownerId");
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Bot profile name must contain 1-16 ASCII letters, digits, or underscores");
        }
    }

    public String normalizedName() {
        return name.toLowerCase(Locale.ROOT);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put(BOT_ID_TAG, NbtUtils.createUUID(botId));
        tag.putString(NAME_TAG, name);
        ownerId.ifPresent(owner -> tag.put(OWNER_ID_TAG, NbtUtils.createUUID(owner)));
        return tag;
    }

    public static BotProfile load(CompoundTag tag) {
        if (!tag.contains(BOT_ID_TAG, Tag.TAG_INT_ARRAY)
                || !tag.contains(NAME_TAG, Tag.TAG_STRING)) {
            throw new IllegalStateException("BotPlayer roster contains an incomplete bot profile");
        }

        UUID botId = NbtUtils.loadUUID(tag.get(BOT_ID_TAG));
        String name = tag.getString(NAME_TAG);
        Optional<UUID> ownerId = tag.contains(OWNER_ID_TAG, Tag.TAG_INT_ARRAY)
                ? Optional.of(NbtUtils.loadUUID(tag.get(OWNER_ID_TAG)))
                : Optional.empty();
        return new BotProfile(botId, name, ownerId);
    }
}
