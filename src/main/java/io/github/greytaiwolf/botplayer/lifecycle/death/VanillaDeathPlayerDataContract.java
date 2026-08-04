package io.github.greytaiwolf.botplayer.lifecycle.death;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * 原版死亡消费事务写入 playerdata 的 V2 合同。
 *
 * <p>handoff 是 tombstone 清除后仍可重放的一次性交接凭据。恢复加载必须先使用 {@link
 * #canonicalDeadCopy(CompoundTag, VanillaDeathTicket)} 创建隔离副本，再把副本交给原版加载；
 * 不得让旧库存或旧经验短暂实体化到活动玩家对象中。
 */
public final class VanillaDeathPlayerDataContract {
    private static final int HANDOFF_VERSION = 2;

    private static final String PLAYER_UUID_TAG = "UUID";
    private static final String INVENTORY_TAG = "Inventory";
    private static final String HEALTH_TAG = "Health";
    private static final String ABSORPTION_TAG = "AbsorptionAmount";
    private static final String EXPERIENCE_LEVEL_TAG = "XpLevel";
    private static final String EXPERIENCE_TOTAL_TAG = "XpTotal";
    private static final String EXPERIENCE_PROGRESS_TAG = "XpP";

    private static final String HANDOFF_TAG = "BotPlayerDeathHandoffV2";
    private static final String VERSION_TAG = "Version";
    private static final String TRANSACTION_ID_TAG = "TransactionId";
    private static final String BOT_ID_TAG = "BotId";
    private static final String GENERATION_TAG = "Generation";
    private static final String CREATED_TICK_TAG = "CreatedTick";
    private static final String PRESERVE_EXPERIENCE_TAG =
            "PreserveExperience";
    private static final String BASE_EXPERIENCE_REWARD_TAG =
            "BaseExperienceReward";
    private static final String RESPAWN_EXPERIENCE_LEVEL_TAG =
            "RespawnXpLevel";
    private static final String RESPAWN_EXPERIENCE_TOTAL_TAG =
            "RespawnXpTotal";
    private static final String RESPAWN_EXPERIENCE_PROGRESS_BITS_TAG =
            "RespawnXpProgressBits";

    private VanillaDeathPlayerDataContract() {}

    /**
     * 把完整交接票据写入 playerdata。
     *
     * <p>字段缺失或已经是同一票据时允许幂等写入；损坏或属于另一事务的旧字段会被拒绝。同一
     * 票据仍会整体替换，避免字段级合并遗留未定义数据。
     */
    public static void writeHandoff(
            CompoundTag playerData, VanillaDeathTicket ticket) {
        Objects.requireNonNull(playerData, "playerData");
        Objects.requireNonNull(ticket, "ticket");
        Optional<VanillaDeathTicket> existing =
                readHandoff(playerData);
        if (existing.isPresent()
                && !existing.orElseThrow().equals(ticket)) {
            throw new IllegalArgumentException(
                    "playerdata is bound to another death handoff");
        }

        CompoundTag handoff = new CompoundTag();
        handoff.putInt(VERSION_TAG, HANDOFF_VERSION);
        handoff.put(
                TRANSACTION_ID_TAG,
                NbtUtils.createUUID(ticket.transactionId()));
        handoff.put(BOT_ID_TAG, NbtUtils.createUUID(ticket.botId()));
        handoff.putLong(GENERATION_TAG, ticket.generation());
        handoff.putLong(CREATED_TICK_TAG, ticket.createdTick());
        handoff.putBoolean(
                PRESERVE_EXPERIENCE_TAG,
                ticket.preserveExperience());
        handoff.putInt(
                BASE_EXPERIENCE_REWARD_TAG,
                ticket.baseExperienceReward());
        handoff.putInt(
                RESPAWN_EXPERIENCE_LEVEL_TAG,
                ticket.respawnExperience().level());
        handoff.putInt(
                RESPAWN_EXPERIENCE_TOTAL_TAG,
                ticket.respawnExperience().total());
        handoff.putInt(
                RESPAWN_EXPERIENCE_PROGRESS_BITS_TAG,
                Float.floatToRawIntBits(
                        ticket.respawnExperience().progress()));
        playerData.put(HANDOFF_TAG, handoff);
    }

    /**
     * 读取完整交接票据。
     *
     * @return 字段完全缺失时返回空；存在但损坏、类型错误或版本未知时抛出异常
     */
    public static Optional<VanillaDeathTicket> readHandoff(
            CompoundTag playerData) {
        Objects.requireNonNull(playerData, "playerData");
        Tag encoded = playerData.get(HANDOFF_TAG);
        if (encoded == null) {
            return Optional.empty();
        }
        if (!(encoded instanceof CompoundTag handoff)) {
            throw invalidHandoff("root field is not a compound", null);
        }

        requireType(handoff, VERSION_TAG, Tag.TAG_INT);
        if (handoff.getInt(VERSION_TAG) != HANDOFF_VERSION) {
            throw invalidHandoff("unsupported version", null);
        }
        requireType(handoff, TRANSACTION_ID_TAG, Tag.TAG_INT_ARRAY);
        requireType(handoff, BOT_ID_TAG, Tag.TAG_INT_ARRAY);
        requireType(handoff, GENERATION_TAG, Tag.TAG_LONG);
        requireType(handoff, CREATED_TICK_TAG, Tag.TAG_LONG);
        requireType(handoff, PRESERVE_EXPERIENCE_TAG, Tag.TAG_BYTE);
        requireType(handoff, BASE_EXPERIENCE_REWARD_TAG, Tag.TAG_INT);
        requireType(
                handoff,
                RESPAWN_EXPERIENCE_LEVEL_TAG,
                Tag.TAG_INT);
        requireType(
                handoff,
                RESPAWN_EXPERIENCE_TOTAL_TAG,
                Tag.TAG_INT);
        requireType(
                handoff,
                RESPAWN_EXPERIENCE_PROGRESS_BITS_TAG,
                Tag.TAG_INT);

        byte preserveExperience =
                handoff.getByte(PRESERVE_EXPERIENCE_TAG);
        if (preserveExperience != 0 && preserveExperience != 1) {
            throw invalidHandoff(
                    "preserve-experience flag is not boolean", null);
        }
        try {
            UUID transactionId = NbtUtils.loadUUID(
                    handoff.get(TRANSACTION_ID_TAG));
            UUID botId = NbtUtils.loadUUID(handoff.get(BOT_ID_TAG));
            DeathExperienceSnapshot experience =
                    new DeathExperienceSnapshot(
                            handoff.getInt(
                                    RESPAWN_EXPERIENCE_LEVEL_TAG),
                            handoff.getInt(
                                    RESPAWN_EXPERIENCE_TOTAL_TAG),
                            Float.intBitsToFloat(
                                    handoff.getInt(
                                            RESPAWN_EXPERIENCE_PROGRESS_BITS_TAG)));
            return Optional.of(
                    new VanillaDeathTicket(
                            transactionId,
                            botId,
                            handoff.getLong(GENERATION_TAG),
                            handoff.getLong(CREATED_TICK_TAG),
                            preserveExperience == 1,
                            handoff.getInt(
                                    BASE_EXPERIENCE_REWARD_TAG),
                            experience));
        } catch (IllegalArgumentException exception) {
            throw invalidHandoff("ticket fields are invalid", exception);
        }
    }

    /** 移除一次性交接凭据；字段缺失时保持幂等。 */
    public static void removeHandoff(CompoundTag playerData) {
        Objects.requireNonNull(playerData, "playerData");
        playerData.remove(HANDOFF_TAG);
    }

    /**
     * 返回加载前使用的规范死亡副本。
     *
     * <p>输入必须属于票据绑定的 Bot。此方法保留与死亡消费无关的原版字段，并确保调用方传入
     * 的 tag 不被修改。
     */
    public static CompoundTag canonicalDeadCopy(
            CompoundTag source, VanillaDeathTicket ticket) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ticket, "ticket");
        if (!hasExactPlayerIdentity(source, ticket.botId())) {
            throw new IllegalArgumentException(
                    "playerdata identity does not match the death ticket");
        }

        CompoundTag canonical = source.copy();
        canonical.put(INVENTORY_TAG, new ListTag());
        canonical.putFloat(HEALTH_TAG, 0.0F);
        canonical.putFloat(ABSORPTION_TAG, 0.0F);
        writeExperience(canonical, ticket.respawnExperience());
        writeHandoff(canonical, ticket);
        return canonical;
    }

    /**
     * 验证两次保存后的死亡 playerdata 是否仍与同一票据精确绑定。
     */
    public static boolean isCanonicalDead(
            CompoundTag playerData, VanillaDeathTicket ticket) {
        Objects.requireNonNull(playerData, "playerData");
        Objects.requireNonNull(ticket, "ticket");
        return hasExactPlayerIdentity(playerData, ticket.botId())
                && hasEmptyInventory(playerData)
                && hasExactFloat(playerData, HEALTH_TAG, 0.0F)
                && hasExactFloat(playerData, ABSORPTION_TAG, 0.0F)
                && hasExactExperience(
                        playerData, ticket.respawnExperience())
                && hasExactHandoff(playerData, ticket);
    }

    /**
     * 验证重生后的 playerdata 已存活、背包仍为空、经验已交接且 handoff 已清除。
     */
    public static boolean isCanonicalAlive(
            CompoundTag playerData, VanillaDeathTicket ticket) {
        Objects.requireNonNull(playerData, "playerData");
        Objects.requireNonNull(ticket, "ticket");
        return hasExactPlayerIdentity(playerData, ticket.botId())
                && hasEmptyInventory(playerData)
                && hasPositiveFiniteHealth(playerData)
                && hasExactExperience(
                        playerData, ticket.respawnExperience())
                && playerData.get(HANDOFF_TAG) == null;
    }

    private static void writeExperience(
            CompoundTag playerData,
            DeathExperienceSnapshot experience) {
        playerData.putInt(EXPERIENCE_LEVEL_TAG, experience.level());
        playerData.putInt(EXPERIENCE_TOTAL_TAG, experience.total());
        playerData.putFloat(
                EXPERIENCE_PROGRESS_TAG, experience.progress());
    }

    private static boolean hasExactPlayerIdentity(
            CompoundTag playerData, UUID expectedBotId) {
        if (!playerData.contains(
                PLAYER_UUID_TAG, Tag.TAG_INT_ARRAY)) {
            return false;
        }
        try {
            return expectedBotId.equals(
                    NbtUtils.loadUUID(
                            playerData.get(PLAYER_UUID_TAG)));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean hasEmptyInventory(
            CompoundTag playerData) {
        Tag inventory = playerData.get(INVENTORY_TAG);
        return inventory instanceof ListTag list && list.isEmpty();
    }

    private static boolean hasPositiveFiniteHealth(
            CompoundTag playerData) {
        if (!playerData.contains(HEALTH_TAG, Tag.TAG_FLOAT)) {
            return false;
        }
        float health = playerData.getFloat(HEALTH_TAG);
        return Float.isFinite(health) && health > 0.0F;
    }

    private static boolean hasExactExperience(
            CompoundTag playerData,
            DeathExperienceSnapshot expected) {
        return playerData.contains(
                        EXPERIENCE_LEVEL_TAG, Tag.TAG_INT)
                && playerData.contains(
                        EXPERIENCE_TOTAL_TAG, Tag.TAG_INT)
                && playerData.contains(
                        EXPERIENCE_PROGRESS_TAG, Tag.TAG_FLOAT)
                && expected.exactlyMatches(
                        playerData.getInt(EXPERIENCE_LEVEL_TAG),
                        playerData.getInt(EXPERIENCE_TOTAL_TAG),
                        playerData.getFloat(
                                EXPERIENCE_PROGRESS_TAG));
    }

    private static boolean hasExactFloat(
            CompoundTag playerData,
            String field,
            float expected) {
        return playerData.contains(field, Tag.TAG_FLOAT)
                && Float.floatToRawIntBits(
                                playerData.getFloat(field))
                        == Float.floatToRawIntBits(expected);
    }

    private static boolean hasExactHandoff(
            CompoundTag playerData, VanillaDeathTicket expected) {
        try {
            return readHandoff(playerData)
                    .filter(expected::equals)
                    .isPresent();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void requireType(
            CompoundTag handoff, String field, int type) {
        if (!handoff.contains(field, type)) {
            throw invalidHandoff(
                    "missing or mistyped field: " + field, null);
        }
    }

    private static IllegalArgumentException invalidHandoff(
            String detail, Throwable cause) {
        return new IllegalArgumentException(
                "invalid vanilla-death playerdata handoff: " + detail,
                cause);
    }
}
