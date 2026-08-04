package io.github.greytaiwolf.botplayer.kernel;

import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import io.github.greytaiwolf.botplayer.lifecycle.death.DeathExperienceSnapshot;
import io.github.greytaiwolf.botplayer.lifecycle.death.VanillaDeathPlayerDataContract;
import io.github.greytaiwolf.botplayer.lifecycle.death.VanillaDeathTicket;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.portal.DimensionTransition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A real online ServerPlayer controlled by BotPlayer.
 *
 * <p>This deliberately does not extend NeoForge FakePlayer and does not report {@code isFakePlayer}.
 * Stats, advancements, inventory NBT, scoreboards, sleeping and other player semantics therefore use
 * the normal vanilla paths.
 */
public final class BotServerPlayer extends ServerPlayer {
    private final BotRuntimeHandle runtimeHandle;
    private int lastClientlessConnectionTick = Integer.MIN_VALUE;
    private boolean suppressNextPlayerDataSave;
    private boolean suppressPlayerDataSaveUntilReleased;
    private boolean suppressDisconnectPreSave;
    private boolean suppressDeathRetirementSave;
    private boolean suppressDeathAttemptSave;
    private final DeathDataSavePermit deathDataSavePermit =
            new DeathDataSavePermit();
    @Nullable
    private VanillaDeathTicket deathHandoffTicket;
    private int deathInvocationDepth;
    @Nullable
    private DeathSaveFenceAttempt deathSaveFenceAttempt;

    public BotServerPlayer(
            MinecraftServer server,
            ServerLevel level,
            GameProfile profile,
            ClientInformation clientInformation,
            BotRuntimeHandle runtimeHandle) {
        super(server, level, profile, clientInformation);
        this.runtimeHandle = runtimeHandle;
    }

    public static BotServerPlayer recreateForRespawn(
            MinecraftServer server,
            ServerLevel level,
            GameProfile profile,
            ClientInformation clientInformation,
            BotServerPlayer oldPlayer) {
        BotServerPlayer replacement = new BotServerPlayer(
                server, level, profile, clientInformation, oldPlayer.runtimeHandle);
        if (oldPlayer.suppressPlayerDataSaveUntilReleased) {
            replacement.suppressPlayerDataSaveUntilReleased();
        }
        if (oldPlayer.suppressDisconnectPreSave) {
            replacement.armDisconnectPreSaveFence();
        }
        if (oldPlayer.suppressDeathRetirementSave
                || oldPlayer.suppressDeathAttemptSave) {
            replacement.armDeathRetirementSaveFence();
        }
        replacement.deathHandoffTicket =
                oldPlayer.deathHandoffTicket;
        return replacement;
    }

    public BotRuntimeHandle runtimeHandle() {
        return runtimeHandle;
    }

    /**
     * Arms a one-shot guard used only by lifecycle fail-closed removal when
     * persisting this body could commit an unverified temporary inventory
     * layout.
     */
    public void suppressNextPlayerDataSave() {
        suppressNextPlayerDataSave = true;
    }

    /**
     * 在 no-save 隔离尚未确认移除 exact body 前，拒绝该 body 的每一次保存。
     * 这条 fence 不能被单次 save 消费；只有身份移除确认后才能显式释放。
     */
    public void suppressPlayerDataSaveUntilReleased() {
        suppressPlayerDataSaveUntilReleased = true;
    }

    /**
     * 断线事务在跨 Tick 清理期间独占的保存 fence。它与 no-save 隔离 fence
     * 分账，安全完成时不能顺手释放另一条路径仍持有的保存禁令。
     */
    public void armDisconnectPreSaveFence() {
        suppressDisconnectPreSave = true;
    }

    public boolean hasDisconnectPreSaveFence() {
        return suppressDisconnectPreSave;
    }

    /**
     * 只释放 direct disconnect 的份额；返回 true 表示没有其他保存 fence。
     */
    public boolean releaseDisconnectPreSaveFence() {
        suppressDisconnectPreSave = false;
        return !suppressNextPlayerDataSave
                && !suppressPlayerDataSaveUntilReleased
                && !suppressDeathRetirementSave
                && !suppressDeathAttemptSave;
    }

    /**
     * 死亡退役在旧 body 仍可被 saveAll 看见时独占的保存 fence。
     */
    public void armDeathRetirementSaveFence() {
        suppressDeathRetirementSave = true;
    }

    public boolean hasDeathRetirementSaveFence() {
        return suppressDeathRetirementSave;
    }

    /**
     * 只释放死亡退役自己的份额；返回 true 表示没有其他保存 fence。
     */
    public boolean releaseDeathRetirementSaveFence() {
        suppressDeathRetirementSave = false;
        return !suppressNextPlayerDataSave
                && !suppressPlayerDataSaveUntilReleased
                && !suppressDisconnectPreSave
                && !suppressDeathAttemptSave;
    }

    /**
     * Marks the outermost {@link #die(DamageSource)} invocation as having reached
     * ServerPlayer's normal TAIL. The death mixin calls this before lifecycle code,
     * so an exception in the manager can never make the override mistake a real
     * death for NeoForge's cancellable early return.
     */
    public boolean markDeathCompletionObserved() {
        DeathSaveFenceAttempt attempt = deathSaveFenceAttempt;
        if (deathInvocationDepth <= 0 || attempt == null) {
            armDeathRetirementSaveFence();
            return false;
        }
        attempt.normalCompletionObserved = true;
        return true;
    }

    /**
     * Transfers the current death-attempt fence to the lifecycle retirement owner.
     * Reentrant {@code die} calls share the same outermost owner token.
     */
    public boolean adoptCompletedDeathSaveFence() {
        DeathSaveFenceAttempt attempt = deathSaveFenceAttempt;
        if (attempt == null || !attempt.normalCompletionObserved) {
            return false;
        }
        if (attempt.dispositionCompleted) {
            return attempt.adoptedByRetirement;
        }
        suppressDeathRetirementSave = true;
        suppressDeathAttemptSave = false;
        attempt.adoptedByRetirement = true;
        attempt.dispositionCompleted = true;
        return true;
    }

    /**
     * Releases only the currently observed death invocation when an existing
     * lifecycle state already owns the body. It never releases a retirement,
     * disconnect, or no-save fence.
     */
    public boolean releaseCompletedDeathAttemptSaveFence() {
        DeathSaveFenceAttempt attempt = deathSaveFenceAttempt;
        if (attempt == null
                || !attempt.normalCompletionObserved
                || attempt.adoptedByRetirement) {
            return false;
        }
        suppressDeathAttemptSave = false;
        attempt.dispositionCompleted = true;
        return true;
    }

    public boolean consumePlayerDataSaveSuppression() {
        if (deathDataSavePermit.armedOrSerializing()) {
            return deathDataSavePermit.shouldSuppress(true);
        }
        if (suppressPlayerDataSaveUntilReleased
                || suppressDisconnectPreSave
                || suppressDeathRetirementSave
                || suppressDeathAttemptSave) {
            return true;
        }
        boolean suppressed =
                suppressNextPlayerDataSave;
        suppressNextPlayerDataSave = false;
        return suppressed;
    }

    public void clearPlayerDataSaveSuppression() {
        /* 单次 remove 的门闩可清理；跨调用 fence 只能由确认移除路径释放。 */
        suppressNextPlayerDataSave = false;
    }

    public void releasePlayerDataSaveSuppression() {
        suppressNextPlayerDataSave = false;
        suppressPlayerDataSaveUntilReleased = false;
        suppressDisconnectPreSave = false;
        suppressDeathRetirementSave = false;
        suppressDeathAttemptSave = false;
        deathSaveFenceAttempt = null;
    }

    /**
     * 只释放 authoritative successor 从 predecessor 继承的 persistent no-save
     * fence。旧 body 自己的 fence 必须永久保留，避免迟到的精确保存覆盖 successor。
     * 其他事务各自持有的保存 fence 不受影响。
     */
    public void releaseInheritedPersistentSaveFence() {
        suppressPlayerDataSaveUntilReleased = false;
    }

    /**
     * 已确认从全服身份拓扑移除后，把该对象收口为永久 no-save poison。
     * 一次性、断线和死亡 fence 都是事务期所有权，移除后必须清掉；persistent
     * 位则永不释放，防止旧对象的迟到回调覆盖后继 body 已提交的玩家数据。
     */
    public void retainPersistentSavePoisonAfterRemoval() {
        suppressNextPlayerDataSave = false;
        suppressPlayerDataSaveUntilReleased = true;
        suppressDisconnectPreSave = false;
        suppressDeathRetirementSave = false;
        suppressDeathAttemptSave = false;
        deathSaveFenceAttempt = null;
    }

    /**
     * 在现有保存 fence 内放行一次精确的外层保存。同步递归保存仍会在
     * PlayerList.save HEAD 被拒绝。
     */
    public void armDeathDataSave(boolean omitHandoff) {
        deathDataSavePermit.arm(
                omitHandoff
                        ? DeathDataSavePermit.HandoffMode.OMIT
                        : DeathDataSavePermit.HandoffMode.INCLUDE);
    }

    /**
     * 结束精确保存许可，并返回外层 PlayerList.save 是否真正到达 HEAD。
     */
    public boolean finishDeathDataSave() {
        return deathDataSavePermit.finish();
    }

    public boolean omitDeathHandoffWhileSaving() {
        return deathDataSavePermit
                .omitHandoffWhileSerializing();
    }

    public Optional<VanillaDeathTicket> deathHandoffTicket() {
        return Optional.ofNullable(deathHandoffTicket);
    }

    public void installDeathHandoffTicket(
            VanillaDeathTicket ticket) {
        if (!getUUID().equals(ticket.botId())) {
            throw new IllegalArgumentException(
                    "death handoff identity mismatch");
        }
        if (deathHandoffTicket != null
                && !deathHandoffTicket.equals(ticket)) {
            throw new IllegalStateException(
                    "another death handoff is already installed");
        }
        deathHandoffTicket = ticket;
    }

    public void clearExactDeathHandoffTicket(
            VanillaDeathTicket ticket) {
        if (deathHandoffTicket == null
                || !deathHandoffTicket.equals(ticket)) {
            throw new IllegalStateException(
                    "death handoff authority changed");
        }
        deathHandoffTicket = null;
    }

    /** 把已被原版消费的死亡 body 规范化为可验证的持久状态。 */
    public void normalizeConsumedDeathBody(
            VanillaDeathTicket ticket) {
        installDeathHandoffTicket(ticket);
        getInventory().clearContent();
        containerMenu.setCarried(ItemStack.EMPTY);
        inventoryMenu.setCarried(ItemStack.EMPTY);
        setHealth(0.0F);
        setAbsorptionAmount(0.0F);
        applyExactExperience(ticket.respawnExperience());
    }

    /** 把一次性交接精确应用到新的存活 body。 */
    public void applyDeathHandoffToSuccessor(
            VanillaDeathTicket ticket) {
        installDeathHandoffTicket(ticket);
        if (!getInventory().isEmpty()
                || !containerMenu.getCarried().isEmpty()
                || !inventoryMenu.getCarried().isEmpty()) {
            throw new IllegalStateException(
                    "respawn successor received an unverified inventory layout");
        }
        applyExactExperience(ticket.respawnExperience());
    }

    public boolean hasExactConsumedDeathRuntimeState(
            VanillaDeathTicket ticket) {
        return deathHandoffTicket != null
                && deathHandoffTicket.equals(ticket)
                && getInventory().isEmpty()
                && containerMenu.getCarried().isEmpty()
                && inventoryMenu.getCarried().isEmpty()
                && Float.floatToRawIntBits(getHealth())
                        == Float.floatToRawIntBits(0.0F)
                && Float.floatToRawIntBits(
                                getAbsorptionAmount())
                        == Float.floatToRawIntBits(0.0F)
                && ticket.respawnExperience().exactlyMatches(
                        experienceLevel,
                        totalExperience,
                        experienceProgress);
    }

    public boolean hasExactSuccessorRuntimeState(
            VanillaDeathTicket ticket) {
        return deathHandoffTicket != null
                && deathHandoffTicket.equals(ticket)
                && isAlive()
                && !isDeadOrDying()
                && getInventory().isEmpty()
                && containerMenu.getCarried().isEmpty()
                && inventoryMenu.getCarried().isEmpty()
                && ticket.respawnExperience().exactlyMatches(
                        experienceLevel,
                        totalExperience,
                        experienceProgress);
    }

    private void applyExactExperience(
            DeathExperienceSnapshot experience) {
        experienceLevel = experience.level();
        totalExperience = experience.total();
        experienceProgress = experience.progress();
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        VanillaDeathTicket ticket = deathHandoffTicket;
        if (ticket == null
                || omitDeathHandoffWhileSaving()) {
            VanillaDeathPlayerDataContract.removeHandoff(tag);
            return;
        }
        VanillaDeathPlayerDataContract.writeHandoff(tag, ticket);
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        VanillaDeathTicket ticket = deathHandoffTicket;
        if (ticket == null) {
            ticket = VanillaDeathPlayerDataContract
                    .readHandoff(tag)
                    .orElse(null);
        }
        if (ticket == null) {
            super.readAdditionalSaveData(tag);
            return;
        }
        installDeathHandoffTicket(ticket);
        suppressPlayerDataSaveUntilReleased();
        armDeathRetirementSaveFence();
        super.readAdditionalSaveData(
                VanillaDeathPlayerDataContract
                        .canonicalDeadCopy(tag, ticket));
    }

    /**
     * 返回本次正常完成死亡中实际执行过原版背包消费的精确票据。
     * 正常到达 TAIL 但没有该回执时，生命周期必须按 PRESERVED 收口。
     */
    public Optional<VanillaDeathTicket>
            completedVanillaDeathTicket() {
        DeathSaveFenceAttempt attempt =
                deathSaveFenceAttempt;
        if (attempt == null
                || !attempt.normalCompletionObserved
                || !attempt.inventoryConsumptionCompleted) {
            return Optional.empty();
        }
        return Optional.of(Objects.requireNonNull(
                attempt.ticket,
                "completed inventory consumption lost its death ticket"));
    }

    @Override
    protected void dropAllDeathLoot(
            ServerLevel level, DamageSource source) {
        DeathSaveFenceAttempt attempt =
                deathSaveFenceAttempt;
        if (deathInvocationDepth <= 0 || attempt == null) {
            throw new IllegalStateException(
                    "BotPlayer death loot lost its outer death owner");
        }
        boolean suppressStaleLoot = BotPlayerManagers.find(server)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "BotPlayer death manager is unavailable"))
                .shouldSuppressStaleVanillaDeathLoot(this);
        if (suppressStaleLoot) {
            attempt.physicalDeathSuppressed = true;
            return;
        }
        attempt.authoritativeLootStarted = true;
        super.dropAllDeathLoot(level, source);
    }

    @Override
    protected void dropEquipment() {
        boolean inventoryWillBeConsumed = !serverLevel()
                .getGameRules()
                .getBoolean(GameRules.RULE_KEEPINVENTORY);
        DeathSaveFenceAttempt attempt =
                deathSaveFenceAttempt;
        if (inventoryWillBeConsumed) {
            if (deathInvocationDepth <= 0 || attempt == null) {
                throw new IllegalStateException(
                        "BotPlayer inventory death consumption lost its outer death owner");
            }
            if (attempt.ticket == null) {
                boolean preserveExperience = isSpectator();
                int baseExperienceReward = preserveExperience
                        ? 0
                        : super.getBaseExperienceReward();
                DeathExperienceSnapshot experience =
                        new DeathExperienceSnapshot(
                                experienceLevel,
                                totalExperience,
                                experienceProgress);
                attempt.ticket = BotPlayerManagers.find(server)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "BotPlayer death manager is unavailable"))
                        .beforeVanillaDeathInventoryDrop(
                                this,
                                experience,
                                preserveExperience,
                                baseExperienceReward);
                installDeathHandoffTicket(attempt.ticket);
            }
        }
        super.dropEquipment();
        if (inventoryWillBeConsumed) {
            attempt.inventoryConsumptionCompleted = true;
        }
    }

    @Override
    protected void dropExperience(@Nullable Entity attacker) {
        DeathSaveFenceAttempt attempt =
                deathSaveFenceAttempt;
        if (attempt != null
                && attempt.physicalDeathSuppressed) {
            return;
        }
        VanillaDeathTicket ticket = activeDeathTicket();
        if (ticket != null && ticket.preserveExperience()) {
            return;
        }
        super.dropExperience(attacker);
    }

    @Override
    protected int getBaseExperienceReward() {
        VanillaDeathTicket ticket = activeDeathTicket();
        return ticket == null
                ? super.getBaseExperienceReward()
                : ticket.baseExperienceReward();
    }

    @Nullable
    private VanillaDeathTicket activeDeathTicket() {
        DeathSaveFenceAttempt attempt =
                deathSaveFenceAttempt;
        return deathInvocationDepth > 0 && attempt != null
                ? attempt.ticket
                : null;
    }

    @Override
    public void die(@NotNull DamageSource source) {
        if (deathInvocationDepth > 0) {
            if (deathSaveFenceAttempt == null) {
                /* A corrupted reentrant owner must fail closed before callbacks. */
                suppressDeathAttemptSave = true;
                throw new IllegalStateException(
                        "Nested BotPlayer death lost its save-fence owner");
            }
            /* The outer invocation exclusively owns every physical death phase. */
            return;
        }
        DeathSaveFenceAttempt attempt =
                new DeathSaveFenceAttempt();
        deathSaveFenceAttempt = attempt;
        suppressDeathAttemptSave = true;

        deathInvocationDepth++;
        boolean returnedNormally = false;
        try {
            super.die(source);
            returnedNormally = true;
        } catch (RuntimeException | Error failure) {
            /*
             * dropEquipment may already have durably armed a tombstone and
             * consumed part of the inventory. A later callback failure must
             * never leave that body in an ACTIVE runtime.
             */
            suppressPlayerDataSaveUntilReleased();
            try {
                BotPlayerManagers.find(server)
                        .ifPresent(manager ->
                                manager.onDeathInvocationFailed(
                                        this,
                                        attempt.authoritativeLootStarted,
                                        failure));
            } catch (RuntimeException isolationFailure) {
                failure.addSuppressed(isolationFailure);
            }
            throw failure;
        } finally {
            deathInvocationDepth--;
            if (returnedNormally
                    && !attempt.normalCompletionObserved) {
                /* NeoForge canceled death through its early return. */
                suppressDeathAttemptSave = false;
                deathSaveFenceAttempt = null;
            } else if (attempt.dispositionCompleted) {
                /* A lifecycle owner adopted or explicitly dismissed this attempt. */
                suppressDeathAttemptSave = false;
                deathSaveFenceAttempt = null;
            }
            /* Throws and an observed-but-unadopted TAIL deliberately retain the fence. */
        }
    }

    @Override
    public void tick() {
        /*
         * ServerLevel owns the ServerPlayer housekeeping phase, EntityTickEvent, freeze and
         * ticking-range semantics. The lifecycle manager separately supplies the connection-owned
         * Player#doTick phase that advances LivingEntity physics and tickCount; BotConnection
         * never receives that phase from the physical connection list.
         */
        super.tick();
    }

    /**
     * Runs the connection-owned half of a real player's tick once per absolute server tick.
     *
     * <p>This method does not increment {@link #tickCount} directly. Its guarded {@link #doTick()}
     * call advances that counter exactly once and still emits NeoForge PlayerTickEvent.Pre/Post, so
     * other mods observe the normal event path.
     */
    public void tickClientlessConnectionPhase() {
        MinecraftServer server = getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException(
                    "BotPlayer connection phase must run on the server thread");
        }
        int currentTick = server.getTickCount();
        if (lastClientlessConnectionTick == currentTick) {
            return;
        }
        lastClientlessConnectionTick = currentTick;

        BotPlayerManagers.find(server)
                .ifPresent(manager -> manager.applyPlayerInput(this));
        doTick();
        BotPlayerManagers.find(server)
                .ifPresent(manager -> manager.syncPlayerInputAfterPhysics(this));
        if (connection instanceof BotGamePacketListener botListener) {
            botListener.tickVirtualProtocol();
        }
    }

    @Override
    public @Nullable Entity changeDimension(@NotNull DimensionTransition transition) {
        Entity result = super.changeDimension(transition);
        if (result == null) {
            return null;
        }

        if (wonGame && connection != null) {
            connection.handleClientCommand(new ServerboundClientCommandPacket(
                    ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        }

        if (connection != null) {
            ServerPlayer currentPlayer = connection.player;
            if (currentPlayer.isChangingDimension()) {
                currentPlayer.hasChangedDimension();
            }
            if (currentPlayer instanceof BotServerPlayer currentBot
                    && currentBot != this) {
                BotPlayerManagers.find(server)
                        .ifPresent(manager ->
                                manager.onConnectionPlayerReplaced(
                                        this, currentBot));
            }
            return currentPlayer;
        }
        return result;
    }

    @Override
    public @NotNull String getIpAddress() {
        return "127.0.0.1";
    }

    @Override
    public boolean allowsListing() {
        return BotPlayerConfig.SHOW_IN_PLAYER_LIST.get();
    }

    private static final class DeathSaveFenceAttempt {
        private boolean normalCompletionObserved;
        private boolean adoptedByRetirement;
        private boolean dispositionCompleted;
        private boolean inventoryConsumptionCompleted;
        private boolean authoritativeLootStarted;
        private boolean physicalDeathSuppressed;
        @Nullable
        private VanillaDeathTicket ticket;
    }
}
