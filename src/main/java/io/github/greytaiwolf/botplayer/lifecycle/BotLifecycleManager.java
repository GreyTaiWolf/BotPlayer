package io.github.greytaiwolf.botplayer.lifecycle;

import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionRequest;
import io.github.greytaiwolf.botplayer.action.StrictNaturalUseCancellation;
import io.github.greytaiwolf.botplayer.action.ActionTransition;
import io.github.greytaiwolf.botplayer.action.BotActionRuntime;
import io.github.greytaiwolf.botplayer.action.ControllerKind;
import io.github.greytaiwolf.botplayer.action.GenerationDrainStatus;
import io.github.greytaiwolf.botplayer.action.MoveInputAction;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.input.PlayerInputController;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupLease;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupRequest;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupResult;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.PlayerInventoryMenuLayout;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionBackend;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftPlayerInputAdapter;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptCloseResult;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptCloseStatus;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptIdentity;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptOffer;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptPrepareResult;
import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyContract;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyDispatchReceipt;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyDispatchStatus;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyProposalSummary;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyPhysicalAttemptOwner;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyReviewReceipt;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyReviewStatus;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlySnapshotProjection;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyTicket;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyTicketBook;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiProposalAuthority;
import io.github.greytaiwolf.botplayer.ai.transport.AiProposalRequestEnvelope;
import io.github.greytaiwolf.botplayer.ai.transport.AiProposalReviewReceipt;
import io.github.greytaiwolf.botplayer.ai.transport.AiProposalReviewStatus;
import io.github.greytaiwolf.botplayer.ai.transport.AiProposalSessionGate;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.inventory.BotInventoryMenu;
import io.github.greytaiwolf.botplayer.inventory.BotInventorySession;
import io.github.greytaiwolf.botplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.botplayer.inventory.InventoryCloseReason;
import io.github.greytaiwolf.botplayer.inventory.InventoryDistanceValidator;
import io.github.greytaiwolf.botplayer.inventory.InventoryLifecycleValidator;
import io.github.greytaiwolf.botplayer.inventory.InventorySessionToken;
import io.github.greytaiwolf.botplayer.inventory.InventorySessionState;
import io.github.greytaiwolf.botplayer.kernel.BotConnection;
import io.github.greytaiwolf.botplayer.kernel.BotGamePacketListener;
import io.github.greytaiwolf.botplayer.kernel.BotRuntimeHandle;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.death.DeathExperienceSnapshot;
import io.github.greytaiwolf.botplayer.lifecycle.death.DeathPersistenceRetry;
import io.github.greytaiwolf.botplayer.lifecycle.death.VanillaDeathPlayerDataCommitter;
import io.github.greytaiwolf.botplayer.lifecycle.death.VanillaDeathTicket;
import io.github.greytaiwolf.botplayer.lifecycle.death.VanillaDeathTombstoneStore;
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementContinuation;
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementFailure;
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementKey;
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementReceipt;
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementSession;
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementStatus;
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementTicket;
import io.github.greytaiwolf.botplayer.network.payload.AgentBindingStatus;
import io.github.greytaiwolf.botplayer.network.payload.AiPhysicalAttemptOfferPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiPhysicalAttemptPrepareAckPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiPhysicalAttemptStartGrantPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiRequestCancellationPayload;
import io.github.greytaiwolf.botplayer.network.payload.OpenCredentialScreenPayload;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationGoal;
import io.github.greytaiwolf.botplayer.navigation.NavigationPolicy;
import io.github.greytaiwolf.botplayer.navigation.NavigationRequest;
import io.github.greytaiwolf.botplayer.navigation.NavigationService;
import io.github.greytaiwolf.botplayer.navigation.NavigationSessionView;
import io.github.greytaiwolf.botplayer.navigation.NavigationSettings;
import io.github.greytaiwolf.botplayer.navigation.NavigationSubmission;
import io.github.greytaiwolf.botplayer.navigation.TerrainAssistSettings;
import io.github.greytaiwolf.botplayer.persistence.BotRosterSavedData;
import io.github.greytaiwolf.botplayer.skill.builtin.P5ABuiltinSkillIds;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionSkillPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.ArmorUpgradeSelection;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.MinecraftBasicArmorPlanner;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.MinecraftBasicEquipmentPlanner;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.MinecraftBasicEquipmentPlanner.ExactMainHandItem;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.ToolKind;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpoint;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointBridge;
import io.github.greytaiwolf.botplayer.skill.checkpoint.MinecraftSavedDataCheckpointDurability;
import io.github.greytaiwolf.botplayer.skill.checkpoint.MinecraftSkillCheckpointScopeObserver;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointDurability;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointDurableCommitter;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointLoadStatus;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointPlan;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointRecoveryCoordination;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointRecoveryCoordinator;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointRecoveryCoordinationRequest;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointRecoveryRejection;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointRecoverySafety;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointRecoverySource;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointReobservation;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointRestartPlan;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointSavedData;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointScope;
import io.github.greytaiwolf.botplayer.perception.AuthorityEventCollector;
import io.github.greytaiwolf.botplayer.perception.ObservationSnapshot;
import io.github.greytaiwolf.botplayer.perception.PerceptionService;
import io.github.greytaiwolf.botplayer.perception.PerceptionSettings;
import io.github.greytaiwolf.botplayer.perception.SoundObservationCandidate;
import io.github.greytaiwolf.botplayer.profile.BotProfile;
import io.github.greytaiwolf.botplayer.safety.DamageCandidate;
import io.github.greytaiwolf.botplayer.safety.SafetyFrame;
import io.github.greytaiwolf.botplayer.safety.SafetyHandoff;
import io.github.greytaiwolf.botplayer.safety.SafetyHandoffDecision;
import io.github.greytaiwolf.botplayer.safety.SafetyHandoffRequest;
import io.github.greytaiwolf.botplayer.safety.SafetyIncidentView;
import io.github.greytaiwolf.botplayer.safety.SafetyService;
import io.github.greytaiwolf.botplayer.safety.SafetySettings;
import io.github.greytaiwolf.botplayer.safety.ThreatSummary;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterRule;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterSchema;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.builtin.breeding.VanillaCowBreeding;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseActionKind;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseActionRequest;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseObservation;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseTarget;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseTargetClass;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.SugarCaneFarmingPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.SugarCaneFarmingSkillIds;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.WheatFarmingPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.WheatFarmingSkillIds;
import io.github.greytaiwolf.botplayer.skill.builtin.recovery.VanillaMilkBucketRecovery;
import io.github.greytaiwolf.botplayer.skill.builtin.trading.VanillaVillagerTrade;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.menu.MenuSnapshot;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionTemplate;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionTemplateBuilder;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackApprovalService;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackApprovalLedgerSavedData;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackDescriptorReference;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackFileLoader;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackId;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackJsonParser;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackLimits;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackManager;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackPolicy;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackRecord;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackRevision;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackState;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackTransition;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackValidator;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanLimits;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidator;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillRunView;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillService;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillSubmission;
import io.github.greytaiwolf.botplayer.skill.runtime.SelfDefenseSkillService;
import io.github.greytaiwolf.botplayer.skill.runtime.SelfDefenseSkillService.AuthorizedActionDispatch;
import io.github.greytaiwolf.botplayer.skill.runtime.SelfDefenseSkillService.AuthorizationRevocation;
import io.github.greytaiwolf.botplayer.skill.runtime.SelfDefenseSkillService.ClaimedActionDispatch;
import io.github.greytaiwolf.botplayer.technique.bridge.SelfDefenseTechniqueBridge;
import io.github.greytaiwolf.botplayer.technique.bridge.TechniqueLifecycleCoordinator;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftEquipmentSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftCowBreedingSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionNavigationSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillPorts;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftMilkBucketRecoverySkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftSingleChestTransferSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftSugarCaneFarmingSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftVillagerTradeSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftWheatFarmingSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunRequest;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunSubmission;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunView;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntimeCheckpoint;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntime;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntimeBudget;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntimeDispatchFence;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.task.MinecraftTaskSensorAdapter;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorLimits;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQuery;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorResponse;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorRunIdentity;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorService;
import io.github.greytaiwolf.botplayer.worldmodel.WorldFact;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.jetbrains.annotations.Nullable;

/**
 * Owns online bot lifecycle. Every mutation is required to run on the Minecraft server thread.
 */
public final class BotLifecycleManager {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final int LIFECYCLE_HISTORY_CAPACITY = 256;
    private static final int RESPAWN_FINALIZE_TIMEOUT_TICKS = 2;
    private static final int RESPAWN_RETRY_DELAY_TICKS = 1;
    private static final int MAX_RESPAWN_ATTEMPTS = 3;

    public enum ListenerDisconnectDecision {
        PROCEED,
        RETRY,
        ABORTED
    }

    private final MinecraftServer server;
    private final BotRosterSavedData roster;
    private final SkillCheckpointSavedData skillCheckpoints;
    /**
     * {@code SavedData#setDirty()} 不是恢复授权。所有 P5A checkpoint 改动必须经此同步
     * commit 边界；一旦该边界失败，本进程宁可放弃恢复，也不会把旧内存记录当作已落盘。
     */
    private final SkillCheckpointDurableCommitter skillCheckpointCommitter;
    private final MinecraftSkillCheckpointScopeObserver
            skillCheckpointScopeObserver;
    private final SkillPackApprovalLedgerSavedData skillPackApprovalLedger;
    private final BotInventorySessionManager inventorySessions;
    private final PlayerInputController inputController;
    /** Native-use fence for strict consumables, reached from a narrow mixin hook. */
    private final MinecraftActionBackend minecraftActionBackend;
    private final PerceptionService perceptionService;
    private final BotActionRuntime actionRuntime;
    /** Shared physical-cancellation port for the P5C bridge and direct RETREAT. */
    private final SelfDefenseActionGateway selfDefenseActionGateway;
    private final NavigationService navigationService;
    private final SkillRegistry skillRegistry;
    private final ResourceReservationService skillReservations;
    private final SkillRuntime skillRuntime;
    private final TaskSensorService taskSensorService;
    private final MinecraftTaskSensorAdapter taskSensorAdapter;
    /** 单一 production 端口同时承载观察、采集与菜单动作，避免三套 binding 分裂。 */
    private final MinecraftProductionSkillPorts productionSkillPorts;
    private final SkillPackManager skillPackManager;
    private final SurvivalSkillService survivalSkillService;
    /** Single owner-thread Technique lifecycle; it owns no generic world route. */
    private final TechniqueLifecycleCoordinator techniqueLifecycleCoordinator;
    /** Narrow P5C route; it owns no TechniqueRuntime or generic AI action route. */
    private final SelfDefenseTechniqueBridge selfDefenseTechniqueBridge;
    private final SelfDefenseSkillService selfDefenseSkillService;
    private final SafetyService safetyService;
    private final VanillaDeathTombstoneStore deathTombstones;
    private final VanillaDeathPlayerDataCommitter
            deathPlayerDataCommitter;
    private final Map<UUID, RuntimeEntry> runtimes = new LinkedHashMap<>();
    private final Map<UUID, BotRuntimeHandle> handlesByBot = new LinkedHashMap<>();
    private final Map<UUID, UUID> activeAgentByBot = new LinkedHashMap<>();
    private final Map<UUID, UUID> botByActiveAgent = new LinkedHashMap<>();
    /** P6 request/response correlation only; it has no direct world-action path. */
    private final AiProposalSessionGate aiProposalSessionGate =
            new AiProposalSessionGate();
    /** Exact P6-R1 snapshot correlation; it never stores model prose or a proposed SkillPlan. */
    private final AiReviewOnlyTicketBook aiReviewOnlyTickets =
            new AiReviewOnlyTicketBook();
    /** Server-thread owner of the bounded B1 accounting state for each real player. */
    private final Map<UUID, AiReviewOnlyPhysicalAttemptOwner>
            aiReviewOnlyPhysicalAttemptOwners = new LinkedHashMap<>();
    /** One exact, currently live physical-attempt identity per active review bot. */
    private final Map<UUID, AiPhysicalAttemptIdentity> aiReviewOnlyPhysicalAttemptsByBot =
            new LinkedHashMap<>();
    private final Map<UUID, Long> skillCheckpointRevisions =
            new LinkedHashMap<>();
    /**
     * 仅记录由 L0 hostile handoff 暂停的通用 P5A run。普通业务节点自行请求的暂停
     * 不进入这里，因而不会因为某次 Safety incident 清除而被生命周期擅自恢复。
     */
    private final Map<UUID, SafetyPausedSkillRun> safetyPausedSkillRuns =
            new LinkedHashMap<>();
    /** 新 generation 的后缀 run 仍须把安全点写回其完整批准计划谱系。 */
    private final Map<UUID, RecoveredSkillCheckpointLineage>
            recoveredSkillCheckpointLineagesByRun = new LinkedHashMap<>();
    private final Deque<BotLifecycleTransition> lifecycleHistory =
            new ArrayDeque<>(LIFECYCLE_HISTORY_CAPACITY);
    private long serverTickStartedNanos = -1L;
    /**
     * 内建 bootstrap 计划不来自外部 Pack，但仍必须拥有正、单调的计划 revision，
     * 这样 checkpoint 恢复可精确重建同一已审核 DAG，而不会把一次新提交误认成旧运行。
     */
    private long nextBuiltinSkillPlanRevision = 1L;
    private boolean stopping;

    BotLifecycleManager(MinecraftServer server) {
        this.server = server;
        this.deathTombstones = new VanillaDeathTombstoneStore(
                server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
                        .resolve("botplayer-death-tombstones"));
        this.deathPlayerDataCommitter =
                new VanillaDeathPlayerDataCommitter(server);
        this.roster = BotRosterSavedData.get(server);
        this.skillCheckpoints = SkillCheckpointSavedData.get(
                server, roster.serverInstanceId());
        this.skillCheckpointCommitter = createSkillCheckpointCommitter();
        this.skillCheckpointScopeObserver =
                new MinecraftSkillCheckpointScopeObserver();
        this.skillPackApprovalLedger = SkillPackApprovalLedgerSavedData.get(
                server, roster.serverInstanceId());
        this.inventorySessions = new BotInventorySessionManager(
                this::canWriteBotInventory,
                this::validateInventoryDistance,
                this::validateInventoryLifecycle);
        this.inputController =
                new PlayerInputController(BotPlayerConfig.MAX_BOTS.get());
        this.perceptionService = new PerceptionService(
                server,
                PerceptionSettings.fromConfig(),
                this::resolveActive);
        this.minecraftActionBackend =
                new MinecraftActionBackend(
                        this, inputController);
        this.actionRuntime = new BotActionRuntime(
                this.minecraftActionBackend,
                BotPlayerConfig.ACTION_MAILBOX_CAPACITY.get(),
                BotPlayerConfig.ACTION_LEDGER_CAPACITY.get(),
                BotPlayerConfig.ACTION_COMMANDS_PER_TICK.get(),
                BotPlayerConfig.ACTION_ACTIVE_CAPACITY.get(),
                BotPlayerConfig.ACTION_COMPLETION_CAPACITY.get(),
                perceptionService.actionOutcomeSink());
        this.selfDefenseActionGateway = new SelfDefenseActionGateway(
                this.actionRuntime);
        this.techniqueLifecycleCoordinator = new TechniqueLifecycleCoordinator();
        this.selfDefenseTechniqueBridge = new SelfDefenseTechniqueBridge(
                techniqueLifecycleCoordinator, selfDefenseActionGateway,
                server::getTickCount);
        this.navigationService = new NavigationService(
                NavigationSettings.fromConfig(),
                TerrainAssistSettings::fromConfig,
                this::resolveActive,
                this::submitAction,
                this::cancelAction);
        this.skillRegistry = new SkillRegistry();
        this.survivalSkillService = new SurvivalSkillService(
                skillRegistry,
                this::resolveActive,
                this::submitAction,
                (botId, actionId) ->
                        cancelAction(
                                botId,
                                actionId,
                                ActionCancellationReason
                                        .REQUESTED),
                new SurvivalSkillService
                        .GenerationLayoutCompensator() {
                    @Override
                    public boolean open(
                            UUID botId,
                            long generation,
                            InventoryLayoutCleanupLease
                                    layoutLease) {
                        return minecraftActionBackend
                                .openSkillInventoryLayout(
                                        botId,
                                        generation,
                                        layoutLease);
                    }

                    @Override
                    public InventoryLayoutCleanupResult cleanup(
                            UUID botId,
                            long generation,
                            InventoryLayoutCleanupRequest
                                    request) {
                        return minecraftActionBackend
                                .cleanupSkillInventoryLayout(
                                        botId,
                                        generation,
                                        request);
                    }

                    @Override
                    public InventoryLayoutCleanupResult
                            consumeVanillaDeath(
                                    UUID botId,
                                    long generation,
                                    InventoryLayoutCleanupLease
                                            layoutLease) {
                        return minecraftActionBackend
                                .consumeVanillaDeathSkillInventoryLayout(
                                        botId,
                                        generation,
                                        layoutLease);
                    }

                    @Override
                    public void release(
                            UUID botId,
                            long generation,
                            UUID runId) {
                        minecraftActionBackend
                                .releaseSkillInventoryLayout(
                                        botId,
                                        generation,
                                        runId);
                    }

                    @Override
                    public void closeGeneration(
                            UUID botId,
                            long generation) {
                        minecraftActionBackend
                                .closeSkillInventoryGeneration(
                                        botId,
                                        generation);
                    }

                    @Override
                    public void closeAll() {
                        minecraftActionBackend
                                .closeSkillInventoryFences();
                    }
                },
                (botId, generation, currentTick) ->
                        actionRuntime
                                .quarantineBotGenerationNow(
                                        botId,
                                        generation,
                                        currentTick)
                                .containmentConfirmed());
        this.skillReservations = new ResourceReservationService(
                2_048, 1_200);
        this.skillRuntime = new SkillRuntime(
                skillRegistry,
                new SkillPlanValidator(
                        skillRegistry, SkillPlanLimits.defaults()),
                skillReservations,
                SkillRuntimeBudget.defaults(),
                this::fenceSkillNodeDispatch);
        this.taskSensorAdapter = new MinecraftTaskSensorAdapter(
                this::resolveActive,
                this::latestSafetyFrameForTaskSensor);
        this.taskSensorService = new TaskSensorService(
                (botId, runId) -> skillRuntime.inspectRun(runId)
                        .filter(view -> view.botId().equals(botId)
                                && !view.state().isTerminal())
                        .map(view -> new TaskSensorRunIdentity(
                                view.botId(),
                                view.botGeneration(),
                                view.runId(),
                        view.stateRevision())),
                TaskSensorLimits.defaults());
        this.productionSkillPorts = new MinecraftProductionSkillPorts(
                this::resolveActive,
                taskSensorService,
                taskSensorAdapter);
        registerP5ABuiltinSkillDescriptors();
        registerP5ANodeHandlers();
        this.skillPackManager = createSkillPackManager();
        // 重启后先以严格 parse/stage + 精确 revision 审批账本恢复可用 pack；不执行任何计划。
        reloadSkillPacks();
        this.selfDefenseSkillService = new SelfDefenseSkillService(
                new SelfDefenseSkillService.TargetResolver() {
                    @Override
                    public Optional<DefenseTarget> resolveTarget(
                            SafetyHandoffRequest request) {
                        return resolveSelfDefenseTarget(request);
                    }

                    @Override
                    public Optional<DefenseObservation> observe(
                            UUID botId,
                            long generation,
                            DefenseTarget target) {
                        return observeSelfDefenseTarget(
                                botId, generation, target);
                    }
                },
                this::createSelfDefenseAction,
                this::submitSelfDefenseAction,
                this::cancelSelfDefenseAction);
        this.safetyService = new SafetyService(
                SafetySettings.fromConfig(),
                navigationService,
                this::submitAction,
                (botId, generation) ->
                        forceCloseInventory(
                                botId,
                                generation,
                                InventoryCloseReason.DANGER),
                new SafetyHandoff() {
                    @Override
                    public SafetyHandoffDecision request(
                            SafetyHandoffRequest request) {
                        return handoffSafetySkill(request);
                    }

                    @Override
                    public boolean preempt(SafetyHandoffRequest request) {
                        return preemptSelfDefenseForSafety(request);
                    }
                });
    }

    /**
     * P5A checkpoint 不能因为一套映射/API 在某个 NeoForge 版本不可用就拖垮整个
     * BotPlayer 生命周期；但该能力不可用时必须从一开始关闭恢复和 checkpoint 保留。
     */
    private SkillCheckpointDurableCommitter createSkillCheckpointCommitter() {
        SkillCheckpointDurability durability;
        try {
            durability = new MinecraftSavedDataCheckpointDurability(
                    server.overworld().getDataStorage());
        } catch (RuntimeException exception) {
            BotPlayer.LOGGER.error(
                    "P5A checkpoint synchronous durability is unavailable; "
                            + "checkpoint recovery is disabled for this server",
                    exception);
            durability = SkillCheckpointDurability.unavailable();
        }
        return new SkillCheckpointDurableCommitter(
                skillCheckpoints, durability);
    }

    /**
     * P5A 外部 Pack 只能引用真正已有的、同样已经绑定 node handler 的 descriptor。
     * 启动阶段的冲突表示内建契约被不兼容代码替换，不能带着混合版本继续运行。
     */
    private void registerP5ABuiltinSkillDescriptors() {
        registerBuiltinDescriptor(new SkillDescriptor(
                P5ABuiltinSkillIds.EQUIP_BASIC_TOOL,
                P5ABuiltinSkillIds.VERSION,
                SkillCategory.SURVIVAL,
                new SkillParameterSchema(Map.of(
                        "tool.kind", new SkillParameterRule.StringRule(
                                true,
                                3,
                                7,
                                Set.of(
                                        "axe",
                                        "hoe",
                                        "pickaxe",
                                        "shovel",
                                        "weapon")))),
                SkillRiskLevel.LOW,
                Set.of(),
                240,
                0,
                false));
        /*
         * 这个节点只接受 P5A 生产编译器已知的五项精确原版物品；handler 还会把标量
         * 重新映射到相同的封闭 enum，并冻结完整 ItemStackFingerprint。它不是任意
         * item-id 的主手选择器。checkpoint 只在原版菜单关闭且动作排空时保存，所以它可
         * 从未开始的精确交换节点安全重放。
         */
        registerBuiltinDescriptor(new SkillDescriptor(
                P5ABuiltinSkillIds.EQUIP_EXACT_MAIN_HAND,
                P5ABuiltinSkillIds.VERSION,
                SkillCategory.SURVIVAL,
                new SkillParameterSchema(Map.of(
                        P5ABuiltinSkillIds
                                .EXACT_MAIN_HAND_ITEM_ID_PARAMETER,
                        new SkillParameterRule.StringRule(
                                true,
                                17,
                                24,
                                Set.of(
                                        ExactMainHandItem.WOODEN_PICKAXE
                                                .itemId().value(),
                                        ExactMainHandItem.STONE_PICKAXE
                                                .itemId().value(),
                                        ExactMainHandItem.IRON_PICKAXE
                                                .itemId().value(),
                                        ExactMainHandItem.CRAFTING_TABLE
                                                .itemId().value(),
                                        ExactMainHandItem.FURNACE
                                                .itemId().value())))),
                SkillRiskLevel.LOW,
                Set.of(),
                240,
                0,
                true));
        registerBuiltinDescriptor(new SkillDescriptor(
                P5ABuiltinSkillIds.EQUIP_REQUESTED_OFFHAND,
                P5ABuiltinSkillIds.VERSION,
                SkillCategory.SURVIVAL,
                new SkillParameterSchema(Map.of(
                        "source.slot", new SkillParameterRule.IntegerRule(
                                true, 0, 35))),
                SkillRiskLevel.LOW,
                Set.of(),
                240,
                0,
                false));
        /*
         * 原版容器坐标只是一份受 schema 限定的标量请求；真正的 block/menu identity
         * 必须由 handler 在服务器线程重新观察并冻结，不能由 Pack 伪造。
         */
        registerBuiltinDescriptor(new SkillDescriptor(
                P5ABuiltinSkillIds.STORE_ITEMS,
                P5ABuiltinSkillIds.VERSION,
                SkillCategory.RESOURCE,
                new SkillParameterSchema(Map.of(
                        MinecraftSingleChestTransferSkillNodeHandler
                                .TARGET_X_PARAMETER,
                        new SkillParameterRule.IntegerRule(
                                true, -30_000_000, 30_000_000),
                        MinecraftSingleChestTransferSkillNodeHandler
                                .TARGET_Y_PARAMETER,
                        new SkillParameterRule.IntegerRule(
                                true, Integer.MIN_VALUE,
                                Integer.MAX_VALUE),
                        MinecraftSingleChestTransferSkillNodeHandler
                                .TARGET_Z_PARAMETER,
                        new SkillParameterRule.IntegerRule(
                                true, -30_000_000, 30_000_000),
                        MinecraftSingleChestTransferSkillNodeHandler
                                .SOURCE_SLOT_PARAMETER,
                        new SkillParameterRule.IntegerRule(true, 0, 89),
                        MinecraftSingleChestTransferSkillNodeHandler
                                .TARGET_SLOT_PARAMETER,
                        new SkillParameterRule.IntegerRule(true, 0, 89),
                        MinecraftSingleChestTransferSkillNodeHandler
                                .AMOUNT_PARAMETER,
                        new SkillParameterRule.IntegerRule(
                                true,
                                0,
                                WorldInteractionActionSpec
                                        .WorldMenuTransfer
                                        .MAXIMUM_EXACT_TRANSFER_AMOUNT),
                        MinecraftSingleChestTransferSkillNodeHandler
                                .DIRECTION_PARAMETER,
                        new SkillParameterRule.StringRule(
                                true,
                                15,
                                15,
                                Set.of("chest_to_player",
                                        "player_to_chest")))),
                SkillRiskLevel.MODERATE,
                Set.of(),
                240,
                0,
                false));
        /*
         * 生产 descriptor 的 operation.id 白名单、最大点击/重试与 resumable 合同都由
         * compiler 固定导出。这里只注册同一实例，不能手写一个更宽 schema 让外部参数
         * 绕过 canonical 生产 fragment lowering。
         */
        registerBuiltinDescriptor(ProductionSkillPlanCompiler
                .handlerDescriptor());
        registerBuiltinDescriptor(ProductionSkillPlanCompiler
                .resourceNavigationHandlerDescriptor());
        /*
         * P5B 的农业/畜牧/交易/恢复 descriptor 只登记到固定 server runtime。它们刻意不进入
         * 外部 skill-pack 白名单；坐标、实体和库存都必须由随后受审的 server-side
         * 调用路径重新观察，不能让磁盘 Pack 自行扩大世界写入能力。
         */
        for (SkillDescriptor descriptor : WheatFarmingPlanCompiler
                .descriptors()) {
            registerBuiltinDescriptor(descriptor);
        }
        for (SkillDescriptor descriptor : SugarCaneFarmingPlanCompiler
                .descriptors()) {
            registerBuiltinDescriptor(descriptor);
        }
        registerBuiltinDescriptor(VanillaCowBreeding.descriptor());
        registerBuiltinDescriptor(VanillaVillagerTrade.descriptor());
        registerBuiltinDescriptor(VanillaMilkBucketRecovery.descriptor());
    }

    private void registerBuiltinDescriptor(SkillDescriptor descriptor) {
        SkillRegistry.RegisterStatus status = skillRegistry.register(
                Objects.requireNonNull(descriptor, "descriptor"));
        if (status != SkillRegistry.RegisterStatus.REGISTERED
                && status != SkillRegistry.RegisterStatus.ALREADY_REGISTERED) {
            throw new IllegalStateException(
                    "Cannot register P5A built-in descriptor "
                            + descriptor.id() + ": " + status);
        }
    }

    /**
     * 运行时 handler 是 server-start 固定代码。pack 的 JSON 只能引用 descriptor，
     * 不会携带 Class、动作或回调对象。
     */
    private void registerP5ANodeHandlers() {
        ActionBackedSkillNodeHandler.ActionGateway actions =
                new ActionBackedSkillNodeHandler.ActionGateway() {
                    @Override
                    public ActionMailbox.Submission submit(
                            ActionEnvelope envelope,
                            ActionPriority priority) {
                        return submitAction(envelope, priority);
                    }

                    @Override
                    public void cancel(
                            UUID botId,
                            UUID actionId,
                            ActionCancellationReason reason) {
                        cancelAction(botId, actionId, reason);
                    }

                    @Override
                    public void cancelStrictNaturalUse(
                            ActionEnvelope envelope,
                            ActionCancellationReason reason) {
                        BotLifecycleManager.this.cancelStrictNaturalUse(
                                envelope, reason);
                    }

                    @Override
                    public StrictNaturalUseCancellation
                            requestStrictNaturalUseCancellation(
                                    ActionEnvelope envelope,
                                    ActionCancellationReason reason) {
                        return BotLifecycleManager.this
                                .cancelStrictNaturalUse(envelope, reason);
                    }
                };
        registerP5ANodeHandler(
                SurvivalSkillService.EQUIP_BASIC_ARMOR,
                P5ABuiltinSkillIds.VERSION,
                new MinecraftEquipmentSkillNodeHandler(
                        MinecraftEquipmentSkillNodeHandler.Kind.BASIC_ARMOR,
                        this::resolveActive,
                        actions,
                        skillRuntime::offerSignal));
        registerP5ANodeHandler(
                P5ABuiltinSkillIds.EQUIP_BASIC_TOOL,
                P5ABuiltinSkillIds.VERSION,
                new MinecraftEquipmentSkillNodeHandler(
                        MinecraftEquipmentSkillNodeHandler.Kind.REQUESTED_TOOL,
                        this::resolveActive,
                        actions,
                        skillRuntime::offerSignal));
        registerP5ANodeHandler(
                P5ABuiltinSkillIds.EQUIP_EXACT_MAIN_HAND,
                P5ABuiltinSkillIds.VERSION,
                new MinecraftEquipmentSkillNodeHandler(
                        MinecraftEquipmentSkillNodeHandler.Kind
                                .REQUESTED_EXACT_MAIN_HAND,
                        this::resolveActive,
                        actions,
                        skillRuntime::offerSignal));
        registerP5ANodeHandler(
                P5ABuiltinSkillIds.EQUIP_REQUESTED_OFFHAND,
                P5ABuiltinSkillIds.VERSION,
                new MinecraftEquipmentSkillNodeHandler(
                        MinecraftEquipmentSkillNodeHandler.Kind
                                .REQUESTED_OFFHAND,
                        this::resolveActive,
                        actions,
                        skillRuntime::offerSignal));
        registerP5ANodeHandler(
                P5ABuiltinSkillIds.STORE_ITEMS,
                P5ABuiltinSkillIds.VERSION,
                new MinecraftSingleChestTransferSkillNodeHandler(
                        this::resolveActive,
                        actions,
                        skillRuntime::offerSignal));
        registerP5ANodeHandler(
                P5ABuiltinSkillIds.NAVIGATE_TO_RESOURCE,
                P5ABuiltinSkillIds.VERSION,
                new MinecraftProductionNavigationSkillNodeHandler(
                        this::resolveActive,
                        navigationService,
                        taskSensorService,
                        taskSensorAdapter,
                        skillRuntime::offerSignal));
        registerP5ANodeHandler(
                P5ABuiltinSkillIds.BOOTSTRAP_IRON,
                P5ABuiltinSkillIds.VERSION,
                new MinecraftProductionSkillNodeHandler(
                        (botId, generation) -> resolveActive(
                                botId, generation).map(ignored ->
                                new MinecraftProductionSkillNodeHandler
                                        .ActiveBot(botId, generation)),
                        productionSkillPorts,
                        taskSensorService,
                        productionSkillPorts,
                        productionSkillPorts,
                        navigationService,
                        actions,
                        skillRuntime::offerSignal));
        registerP5ANodeHandler(
                WheatFarmingSkillIds.HARVEST_MATURE_WHEAT,
                WheatFarmingSkillIds.VERSION,
                new MinecraftWheatFarmingSkillNodeHandler(
                        MinecraftWheatFarmingSkillNodeHandler.Mode.HARVEST,
                        this::resolveActive,
                        actions,
                        skillRuntime::offerSignal));
        registerP5ANodeHandler(
                WheatFarmingSkillIds.PLANT_WHEAT,
                WheatFarmingSkillIds.VERSION,
                new MinecraftWheatFarmingSkillNodeHandler(
                        MinecraftWheatFarmingSkillNodeHandler.Mode.PLANT,
                        this::resolveActive,
                        actions,
                        skillRuntime::offerSignal));
        registerP5ANodeHandler(
                SugarCaneFarmingSkillIds.HARVEST_UPPER_SUGAR_CANE,
                SugarCaneFarmingSkillIds.VERSION,
                new MinecraftSugarCaneFarmingSkillNodeHandler(
                        this::resolveActive,
                        actions,
                        skillRuntime::offerSignal));
        registerP5ANodeHandler(
                VanillaCowBreeding.ID,
                VanillaCowBreeding.VERSION,
                new MinecraftCowBreedingSkillNodeHandler(
                        this::resolveActive,
                        actions,
                        skillRuntime::offerSignal));
        registerP5ANodeHandler(
                VanillaVillagerTrade.ID,
                VanillaVillagerTrade.VERSION,
                new MinecraftVillagerTradeSkillNodeHandler(
                        this::resolveActive,
                        actions,
                        skillRuntime::offerSignal));
        registerP5ANodeHandler(
                VanillaMilkBucketRecovery.ID,
                VanillaMilkBucketRecovery.VERSION,
                new MinecraftMilkBucketRecoverySkillNodeHandler(
                        this::resolveActive,
                        actions,
                        skillRuntime::offerSignal));
    }

    private void registerP5ANodeHandler(
            io.github.greytaiwolf.botplayer.skill.core.SkillId id,
            SkillVersion version,
            io.github.greytaiwolf.botplayer.skill.runtime.core
                    .SkillNodeHandler handler) {
        SkillRuntime.HandlerRegistrationStatus status =
                skillRuntime.registerHandler(id, version, handler);
        if (status != SkillRuntime.HandlerRegistrationStatus.REGISTERED
                && status
                        != SkillRuntime.HandlerRegistrationStatus
                                .ALREADY_REGISTERED) {
            throw new IllegalStateException(
                    "Cannot register P5A node handler " + id + ": " + status);
        }
    }

    private SkillPackManager createSkillPackManager() {
        Path playerData = server.getWorldPath(
                LevelResource.PLAYER_DATA_DIR);
        Path worldRoot = playerData.getParent();
        if (worldRoot == null) {
            throw new IllegalStateException(
                    "Minecraft world root is unavailable for skill packs");
        }
        SkillPackLimits limits = SkillPackLimits.defaults();
        SkillPackPolicy policy = new SkillPackPolicy(
                limits,
                Set.of(1),
                Set.of(
                        new SkillPackDescriptorReference(
                                SurvivalSkillService.EQUIP_BASIC_ARMOR,
                                P5ABuiltinSkillIds.VERSION),
                        new SkillPackDescriptorReference(
                                P5ABuiltinSkillIds.EQUIP_BASIC_TOOL,
                                P5ABuiltinSkillIds.VERSION),
                        new SkillPackDescriptorReference(
                                P5ABuiltinSkillIds.EQUIP_EXACT_MAIN_HAND,
                                P5ABuiltinSkillIds.VERSION),
                        new SkillPackDescriptorReference(
                                P5ABuiltinSkillIds
                                        .EQUIP_REQUESTED_OFFHAND,
                                P5ABuiltinSkillIds.VERSION),
                        new SkillPackDescriptorReference(
                                P5ABuiltinSkillIds.STORE_ITEMS,
                                P5ABuiltinSkillIds.VERSION)));
        return new SkillPackManager(
                new SkillPackFileLoader(),
                new SkillPackJsonParser(),
                new SkillPackApprovalService(
                        new SkillPackValidator(skillRegistry, policy),
                        limits.maximumTrackedPacks()),
                worldRoot);
    }

    private Optional<SafetyFrame> latestSafetyFrameForTaskSensor(
            UUID botId) {
        return safetyService.latestFrame(
                Objects.requireNonNull(botId, "botId"));
    }

    /**
     * L0 在 hostile 交接前暂停普通 DAG 的等待/菜单所有权，避免自卫与低优先级计划
     * 共同占用动作通道。暂停 run 保留 run identity，但必须在危险稳定解除、原版控制面
     * 静止且生产 checkpoint 已耐久化后重新观察同一节点；非 hostile 的恢复仍交给既有
     * 进食服务。
     */
    private SafetyHandoffDecision handoffSafetySkill(
            SafetyHandoffRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.hazard().type()
                == io.github.greytaiwolf.botplayer.safety
                        .HazardType.HOSTILE_TARGETING) {
            pauseGenericSkillForSafety(request);
            return selfDefenseSkillService.request(request);
        }
        return survivalSkillService.request(request);
    }

    private void pauseGenericSkillForSafety(SafetyHandoffRequest request) {
        SafetyPausedSkillRun existing = safetyPausedSkillRuns.get(
                request.botId());
        SkillRunView view = skillRuntime.inspect(request.botId())
                .filter(candidate -> candidate.botGeneration()
                        == request.botGeneration()
                        && !candidate.state().isTerminal())
                .orElse(null);
        if (view == null) {
            if (existing != null
                    && existing.generation() == request.botGeneration()) {
                safetyPausedSkillRuns.remove(request.botId(), existing);
            }
            return;
        }
        SkillRuntime.PauseStatus status = skillRuntime.pauseForSafety(
                view.runId(),
                request.currentTick(),
                "L0 hostile safety handoff paused the plan");
        if (status == SkillRuntime.PauseStatus.PAUSED
                || status == SkillRuntime.PauseStatus
                        .CANCELLATION_PENDING) {
            /*
             * A strict native use may have crossed its physical completion
             * boundary.  The SkillRuntime will become PAUSED only after its
             * exact action receipt settles; retain the L0 ownership now so
             * the later PAUSED run can still be resumed when the incident
             * clears.
             */
            safetyPausedSkillRuns.put(
                    request.botId(),
                    new SafetyPausedSkillRun(
                            view.runId(),
                            request.botGeneration(),
                            request.incidentId()));
        } else if (status == SkillRuntime.PauseStatus.ALREADY_PAUSED
                && existing != null
                && existing.runId().equals(view.runId())
                && existing.generation() == request.botGeneration()) {
            /* 同一 L0 暂停的重复 handoff 只能刷新 incident 归属，不能唤醒业务暂停。 */
            safetyPausedSkillRuns.put(
                    request.botId(),
                    new SafetyPausedSkillRun(
                            view.runId(),
                            request.botGeneration(),
                            request.incidentId()));
        }
    }

    /**
     * 只恢复明确由 L0 暂停的 run。SafetyService 只有在本 Tick 已完成 clear-stability
     * 判定后才移除 incident；随后还需确认自卫、原版 menu/cursor、动作运行时以及生产
     * checkpoint 都已到静止点，才能把 runtime 推入 RESUMING。runtime 的下一步会重新
     * 执行当前节点 begin()，重新取得 lease 并以新的 state revision 观察世界。
     */
    private void resumeSafetyPausedSkillRuns(long currentTick) {
        for (Map.Entry<UUID, SafetyPausedSkillRun> entry :
                List.copyOf(safetyPausedSkillRuns.entrySet())) {
            UUID botId = entry.getKey();
            SafetyPausedSkillRun paused = entry.getValue();
            RuntimeEntry runtime = runtimes.get(botId);
            if (runtime == null
                    || runtime.state != BotLifecycleState.ACTIVE
                    || runtime.handle.generation() != paused.generation()) {
                safetyPausedSkillRuns.remove(botId, paused);
                continue;
            }
            SkillRunView view = skillRuntime.inspectRun(paused.runId())
                    .orElse(null);
            if (view == null
                    || !view.botId().equals(botId)
                    || view.botGeneration() != paused.generation()
                    || view.state() != SkillRunState.PAUSED) {
                safetyPausedSkillRuns.remove(botId, paused);
                continue;
            }
            if (safetyService.inspect(botId).isPresent()
                    || selfDefenseSkillService.latestView(botId)
                            .filter(defense -> defense.generation()
                                    == paused.generation()
                                    && !defense.status().terminal())
                            .isPresent()) {
                continue;
            }
            BotServerPlayer player = runtime.handle.player().orElse(null);
            if (player == null
                    || !isListenerAuthority(player)
                    || !hasDurableCheckpointQuiescence(
                            player, botId, paused.generation())) {
                continue;
            }
            SkillRuntimeCheckpoint checkpoint = skillRuntime.checkpoint(botId)
                    .orElse(null);
            if (checkpoint == null
                    || !checkpoint.view().runId().equals(paused.runId())
                    || checkpoint.view().stateRevision()
                            != view.stateRevision()) {
                safetyPausedSkillRuns.remove(botId, paused);
                continue;
            }
            if (requiresProductionCheckpointScope(checkpoint.plan())
                    && skillCheckpointRevisions.getOrDefault(botId, -1L)
                            != view.stateRevision()) {
                /* persistSafeSkillCheckpoints() 会在本 Tick 稍后写出同一 PAUSED revision。 */
                continue;
            }
            SkillRuntime.ResumeStatus status = skillRuntime.resume(
                    paused.runId(), currentTick);
            if (status == SkillRuntime.ResumeStatus.RESUMING
                    || status == SkillRuntime.ResumeStatus.NOT_ACTIVE
                    || status == SkillRuntime.ResumeStatus.NOT_PAUSED) {
                safetyPausedSkillRuns.remove(botId, paused);
            }
        }
    }

    private Optional<DefenseTarget> resolveSelfDefenseTarget(
            SafetyHandoffRequest request) {
        BotServerPlayer player = resolveActive(
                request.botId(), request.botGeneration()).orElse(null);
        UUID source = request.hazard().sourceEntityId().orElse(null);
        if (player == null || source == null
                || !frameMarksExplicitHostile(request, source)) {
            return Optional.empty();
        }
        Entity entity = player.serverLevel().getEntity(source);
        if (!(entity instanceof Mob mob)
                || !(entity instanceof Enemy)
                || entity.isRemoved()
                || !entity.isAlive()
                || mob.getTarget() != player) {
            return Optional.empty();
        }
        return Optional.of(new DefenseTarget(
                source,
                DefenseTargetClass.EXPLICIT_HOSTILE,
                true,
                player.distanceToSqr(entity)));
    }

    private Optional<DefenseObservation> observeSelfDefenseTarget(
            UUID botId, long generation, DefenseTarget target) {
        BotServerPlayer player = resolveActive(botId, generation)
                .orElse(null);
        SafetyFrame frame = safetyService.latestFrame(botId)
                .filter(candidate -> candidate.botGeneration() == generation)
                .orElse(null);
        if (player == null || frame == null) {
            return Optional.empty();
        }
        Entity entity = player.serverLevel().getEntity(target.entityId());
        if (entity == null || entity.isRemoved()) {
            return Optional.of(observationFromSafetyFrame(
                    frame,
                    new DefenseTarget(
                            target.entityId(),
                            target.targetClass(),
                            false,
                            target.distanceSquared())));
        }
        DefenseTargetClass classification = entity instanceof Player
                ? DefenseTargetClass.PLAYER
                : entity instanceof Enemy && entity instanceof Mob mob
                        && mob.getTarget() == player
                        ? DefenseTargetClass.EXPLICIT_HOSTILE
                        : DefenseTargetClass.UNKNOWN;
        return Optional.of(observationFromSafetyFrame(
                frame,
                new DefenseTarget(
                        target.entityId(),
                        classification,
                        entity.isAlive(),
                        player.distanceToSqr(entity))));
    }

    /** 把同一份 L0 帧的健康、覆盖和已验证撤退候选原样交给纯自卫状态机。 */
    private static DefenseObservation observationFromSafetyFrame(
            SafetyFrame frame,
            DefenseTarget target) {
        double health = Math.max(0.0D, frame.health());
        double maximum = Math.max(1.0D, frame.maximumHealth());
        int hostileThreatCount = (int) frame.threats().stream()
                .filter(threat -> threat.kind() == ThreatSummary.Kind.HOSTILE)
                .count();
        return new DefenseObservation(
                Math.min(health, maximum),
                maximum,
                target,
                frame.threatCoverageIncomplete(),
                hostileThreatCount,
                frame.safeRetreat());
    }

    private Optional<ActionRequest> createSelfDefenseAction(
            DefenseActionRequest instruction,
            DefenseObservation observation) {
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(observation, "observation");
        if (!instruction.targetId().equals(
                observation.target().entityId())) {
            return Optional.empty();
        }
        return switch (instruction.kind()) {
            case RETREAT -> instruction.safeRetreat()
                    .filter(retreat -> observation.safeRetreat()
                            .filter(retreat::equals)
                            .isPresent())
                    .map(retreat -> (ActionRequest) new MoveInputAction(
                            retreat.forwardInput(),
                            retreat.strafeInput(),
                            false,
                            true,
                            false,
                            retreat.inputTicks(),
                            retreat.inputTicks()));
            case MELEE_ATTACK -> uniqueDefensePlayer(
                    instruction.targetId()).map(player -> {
                        Entity target = player.serverLevel().getEntity(
                                instruction.targetId());
                        if (!(target instanceof Enemy)
                                || target instanceof Player
                                || target.isRemoved()
                                || !target.isAlive()) {
                            return null;
                        }
                        try {
                            return (ActionRequest) new WorldInteractionAction(
                                    new WorldInteractionActionSpec.AttackEntity(
                                            MinecraftActionSnapshot.entity(
                                                    player, target)));
                        } catch (RuntimeException exception) {
                            return null;
                        }
                    }).filter(Objects::nonNull);
        };
    }

    private CompletionStage<ActionOutcome> submitSelfDefenseAction(
            AuthorizedActionDispatch authorization) {
        AuthorizedActionDispatch required = Objects.requireNonNull(
                authorization, "authorization");
        if (required.kind() == DefenseActionKind.MELEE_ATTACK) {
            return selfDefenseTechniqueBridge.submit(required);
        }
        if (required.kind() != DefenseActionKind.RETREAT) {
            throw new IllegalArgumentException(
                    "unsupported limited self-defense authorization kind");
        }
        ClaimedActionDispatch dispatch = required.claim().orElseThrow(() ->
                new IllegalStateException(
                        "self-defense retreat authorization was not current"));
        ActionMailbox.Submission submission = selfDefenseActionGateway.submit(
                new ActionEnvelope(
                        dispatch.actionId(),
                        dispatch.botId(),
                        dispatch.botGeneration(),
                        dispatch.idempotencyKey(),
                        dispatch.deadlineTick(),
                        dispatch.maximumTicks(),
                        dispatch.action(),
                        ActionOrigin.fromController(
                                ControllerKind.SAFETY,
                                dispatch.selfDefenseRunId())),
                ActionPriority.EMERGENCY);
        CompletionStage<ActionOutcome> completion = submission.completion().orElseThrow(() ->
                new IllegalStateException(
                        "self-defense action was rejected: "
                                + submission.status()));
        /*
         * A synchronous lifecycle/L0 callback may have revoked the exact
         * one-shot authority inside Action ingress. The action runtime has
         * already received the envelope, so retract its exact identity before
         * exposing a completion to the self-defense service.
         */
        if (!dispatch.isAuthorityCurrent()) {
            AuthorizationRevocation revocation = dispatch.revocation().orElse(
                    AuthorizationRevocation.TERMINAL);
            ActionCancellationReceipt receipt =
                    selfDefenseActionGateway.cancelOrContain(
                            dispatch.botId(), dispatch.botGeneration(),
                            dispatch.actionId(), cancellationReasonFor(revocation),
                            server.getTickCount());
            if (!receipt.safelyRetracted()) {
                BotPlayer.LOGGER.error(
                        "Self-defense RETREAT cancellation is unsafe for bot {} generation {} action {}: {}",
                        dispatch.botId(), dispatch.botGeneration(),
                        dispatch.actionId(), receipt.disposition());
            }
            throw new IllegalStateException(
                    "self-defense retreat authorization was revoked during submission");
        }
        return completion;
    }

    /**
     * Only the narrow bridge may cancel a melee action it previously bound to
     * its exact technique ticket. Retreat remains the pre-existing direct
     * limited-self-defense action path.
     */
    private ActionCancellationReceipt cancelSelfDefenseAction(
            AuthorizedActionDispatch authorization) {
        AuthorizedActionDispatch required = Objects.requireNonNull(
                authorization, "authorization");
        if (required.kind() == DefenseActionKind.MELEE_ATTACK) {
            return selfDefenseTechniqueBridge.cancelDispatch(required);
        }
        if (required.kind() != DefenseActionKind.RETREAT) {
            throw new IllegalArgumentException(
                    "unsupported limited self-defense authorization kind");
        }
        AuthorizationRevocation revocation = required.revocation().orElse(
                AuthorizationRevocation.CANCELLED);
        return selfDefenseActionGateway.cancelOrContain(required.botId(),
                required.botGeneration(), required.actionId(),
                cancellationReasonFor(revocation), server.getTickCount());
    }

    private static ActionCancellationReason cancellationReasonFor(
            AuthorizationRevocation revocation) {
        return switch (Objects.requireNonNull(revocation, "revocation")) {
            case SERVER_STOP -> ActionCancellationReason.RUNTIME_SHUTDOWN;
            case GENERATION_CLOSED, TERMINAL ->
                    ActionCancellationReason.LIFECYCLE;
            case COMPLETED, CANCELLED, SUBMISSION_REJECTED,
                    SAFETY_PREEMPTION -> ActionCancellationReason.REQUESTED;
        };
    }

    /**
     * A technique gets an L0 preemption only if the limited self-defense
     * session itself actually accepted that preemption. A routine safety frame
     * must never terminate a bridge run by implication.
     */
    private boolean preemptSelfDefenseForSafety(
            SafetyHandoffRequest request) {
        Objects.requireNonNull(request, "request");
        boolean preempted = selfDefenseSkillService.preempt(request);
        if (preempted) {
            selfDefenseTechniqueBridge.preemptForSafety(request.botId(),
                    request.botGeneration(), request.currentTick());
        }
        return preempted;
    }

    private Optional<BotServerPlayer> uniqueDefensePlayer(UUID targetId) {
        BotServerPlayer candidate = null;
        for (RuntimeEntry runtime : runtimes.values()) {
            if (runtime.state != BotLifecycleState.ACTIVE) {
                continue;
            }
            BotServerPlayer player = runtime.handle.player().orElse(null);
            if (player == null) {
                continue;
            }
            Entity target = player.serverLevel().getEntity(targetId);
            if (!(target instanceof Mob mob)
                    || !(target instanceof Enemy)
                    || target.isRemoved()
                    || !target.isAlive()
                    || mob.getTarget() != player) {
                continue;
            }
            if (candidate != null) {
                return Optional.empty();
            }
            candidate = player;
        }
        return Optional.ofNullable(candidate);
    }

    private static boolean frameMarksExplicitHostile(
            SafetyHandoffRequest request, UUID source) {
        return request.frame().threats().stream().anyMatch(threat ->
                threat.entityId().equals(source)
                        && threat.kind()
                                == io.github.greytaiwolf.botplayer.safety
                                        .ThreatSummary.Kind.HOSTILE
                        && threat.targetingBot());
    }

    /**
     * 原版即将实际消费背包时发布耐久票据。该方法只能从 exact body 的
     * dropEquipment 调用；任何 I/O 或权威漂移都在掉落实体产生前失败关闭。
     */
    public VanillaDeathTicket beforeVanillaDeathInventoryDrop(
            BotServerPlayer player,
            DeathExperienceSnapshot experience,
            boolean preserveExperience,
            int baseExperienceReward) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(experience, "experience");
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE
                || runtime.handle.player().orElse(null) != player
                || runtime.deathRetirement != null
                || runtime.completedDeathRetirement != null
                || runtime.generationRetirementInProgress
                || runtime.replacementHandoffInProgress
                || !isAuthoritativeInstance(runtime, player, false)) {
            player.suppressPlayerDataSaveUntilReleased();
            throw new IllegalStateException(
                    "Vanilla death inventory consumption lost exact BotPlayer authority");
        }
        long generation = runtime.handle.generation();
        VanillaDeathTicket ticket = VanillaDeathTicket.create(
                UUID.randomUUID(),
                player.getUUID(),
                generation,
                server.getTickCount(),
                experience,
                preserveExperience,
                baseExperienceReward);
        try {
            deathTombstones.arm(ticket);
        } catch (IOException exception) {
            player.suppressPlayerDataSaveUntilReleased();
            throw new IllegalStateException(
                    "Could not durably arm vanilla-death tombstone",
                    exception);
        }
        if (runtimes.get(player.getUUID()) != runtime
                || runtime.state != BotLifecycleState.ACTIVE
                || runtime.handle.generation() != generation
                || runtime.handle.player().orElse(null) != player
                || !isAuthoritativeInstance(runtime, player, false)) {
            player.suppressPlayerDataSaveUntilReleased();
            throw new IllegalStateException(
                    "Vanilla death authority changed after tombstone publication");
        }
        return ticket;
    }

    /**
     * 在任何死亡 loot callback 展开前冻结 exact body。已成功交接后的旧 body
     * 只能跳过整段物理死亡；其他权威冲突则在生成掉落前失败关闭。
     */
    public boolean shouldSuppressStaleVanillaDeathLoot(
            BotServerPlayer player) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null) {
            player.suppressPlayerDataSaveUntilReleased();
            return true;
        }
        BotServerPlayer authoritative =
                runtime.handle.player().orElse(null);
        if (runtime.state == BotLifecycleState.ACTIVE
                && authoritative == player
                && isAuthoritativeInstance(
                        runtime, player, false)) {
            return false;
        }
        if (runtime.state == BotLifecycleState.ACTIVE
                && authoritative != null
                && authoritative != player
                && player.runtimeHandle() == runtime.handle
                && isAuthoritativeInstance(
                        runtime, authoritative, true)) {
            player.suppressPlayerDataSaveUntilReleased();
            return true;
        }
        if (runtime.state != BotLifecycleState.ACTIVE
                && authoritative == player
                && player.runtimeHandle() == runtime.handle) {
            player.suppressPlayerDataSaveUntilReleased();
            return true;
        }
        player.suppressPlayerDataSaveUntilReleased();
        throw new IllegalStateException(
                "Vanilla death loot found an unsafe BotPlayer authority topology");
    }

    public void beginServerTick() {
        requireServerThread();
        serverTickStartedNanos = System.nanoTime();
    }

    public AuthorityEventCollector authorityEventCollector() {
        requireServerThread();
        return perceptionService.authorityCollector();
    }

    public void offerSoundObservation(
            SoundObservationCandidate candidate) {
        perceptionService.offerSound(candidate);
    }

    public ActionMailbox.Submission submitAction(
            ActionEnvelope envelope, ActionPriority priority) {
        return actionRuntime.submit(envelope, priority);
    }

    public ActionMailbox.Cancellation cancelAction(
            UUID botId, UUID actionId, ActionCancellationReason reason) {
        return actionRuntime.cancel(botId, actionId, reason);
    }

    /**
     * Cancels one strict natural {@code UseItem} action through its full
     * immutable envelope and exposes it to the pre-consumption mixin.
     *
     * <p>The ordinary action mailbox drains after bot {@code doTick()}, so an
     * accepted cancellation alone is too late for the final use tick. This is
     * intentionally not a general action cancellation API: the full envelope
     * is required to reject idempotent aliases, and only a strict natural
     * {@code UseItem} may arm the native-use fence. If fence matching or
     * cancellation ingress is unsafe, the whole generation is synchronously
     * quarantined before this method returns.
     */
    public StrictNaturalUseCancellation cancelStrictNaturalUse(
            ActionEnvelope expected,
            ActionCancellationReason reason) {
        requireServerThread();
        ActionEnvelope required = Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(reason, "reason");
        requireStrictNaturalUseEnvelope(required);

        MinecraftActionBackend.StrictUseCancellationFenceStatus fenceStatus;
        try {
            fenceStatus = minecraftActionBackend
                    .fenceStrictNativeItemUseCancellation(required);
        } catch (RuntimeException exception) {
            quarantineRejectedSkillCancellation(required,
                    "strict native-use fence threw", exception);
            return StrictNaturalUseCancellation.REJECTED_CONTAINED;
        }
        if (fenceStatus == MinecraftActionBackend
                .StrictUseCancellationFenceStatus
                        .NATIVE_COMPLETION_ENTERED) {
            return StrictNaturalUseCancellation.COMPLETION_ENTERED;
        }
        if (fenceStatus == MinecraftActionBackend
                .StrictUseCancellationFenceStatus
                        .ACTIVE_STRICT_USE_MISMATCH) {
            quarantineRejectedSkillCancellation(required,
                    "active strict native-use envelope mismatched");
            return StrictNaturalUseCancellation.REJECTED_CONTAINED;
        }
        if (fenceStatus == MinecraftActionBackend
                .StrictUseCancellationFenceStatus
                        .NO_ACTIVE_STRICT_USE
                && actionRuntime.completedOutcomeExact(required).isPresent()) {
            /*
             * The backend has already cleaned up its short-lived state, but
             * the exact immutable action outcome remains authoritative. Do
             * not put a late cancellation into the cancellation-first mailbox:
             * ALREADY_TERMINAL would be contained as an unsafe ingress and
             * would hide the real physical result.
             */
            return StrictNaturalUseCancellation.ALREADY_TERMINAL;
        }

        ActionMailbox.Cancellation cancellation;
        try {
            cancellation = actionRuntime.cancelExact(required, reason);
        } catch (RuntimeException exception) {
            quarantineRejectedSkillCancellation(required,
                    "exact cancellation threw", exception);
            return StrictNaturalUseCancellation.REJECTED_CONTAINED;
        }
        if (cancellation.status()
                != ActionMailbox.CancellationStatus.ENQUEUED) {
            quarantineRejectedSkillCancellation(required,
                    "exact cancellation was rejected: "
                            + cancellation.status());
            return StrictNaturalUseCancellation.REJECTED_CONTAINED;
        }
        return StrictNaturalUseCancellation.FENCED;
    }

    private static void requireStrictNaturalUseEnvelope(
            ActionEnvelope envelope) {
        if (!(envelope.action() instanceof WorldInteractionAction action)
                || !(action.spec() instanceof WorldInteractionActionSpec
                        .UseItem useItem)
                || useItem.mode()
                        != WorldInteractionActionSpec.ItemUseMode
                                .FINISH_NATURALLY
                || useItem.strictPreconditions().isEmpty()) {
            throw new IllegalArgumentException(
                    "Strict native-use cancellation requires a strict natural UseItem envelope");
        }
    }

    private void quarantineRejectedSkillCancellation(
            ActionEnvelope envelope,
            String reason) {
        quarantineRejectedSkillCancellation(envelope, reason, null);
    }

    private void quarantineRejectedSkillCancellation(
            ActionEnvelope envelope,
            String reason,
            RuntimeException cause) {
        ActionEnvelope required = Objects.requireNonNull(envelope, "envelope");
        quarantineRejectedSkillCancellation(required.botId(),
                required.botGeneration(), required.actionId(), reason, cause);
    }

    private void quarantineRejectedSkillCancellation(
            UUID botId,
            long botGeneration,
            UUID actionId,
            String reason) {
        quarantineRejectedSkillCancellation(botId, botGeneration, actionId,
                reason, null);
    }

    private void quarantineRejectedSkillCancellation(
            UUID botId,
            long botGeneration,
            UUID actionId,
            String reason,
            RuntimeException cause) {
        try {
            BotActionRuntime.GenerationQuarantineResult result = actionRuntime
                    .quarantineBotGenerationNow(botId, botGeneration,
                            server.getTickCount());
            if (!result.containmentConfirmed()) {
                BotPlayer.LOGGER.error(
                        "Skill cancellation containment is unsafe for bot {} generation {} action {}: {}",
                        botId, botGeneration, actionId, reason, cause);
            }
        } catch (RuntimeException quarantineFailure) {
            BotPlayer.LOGGER.error(
                    "Skill cancellation quarantine threw for bot {} generation {} action {}: {}",
                    botId, botGeneration, actionId, reason, quarantineFailure);
        }
    }

    public void recordSafetyDamage(
            BotServerPlayer player,
            DamageSource source,
            float finalDamage,
            long currentTick) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(source, "source");
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE
                || runtime.handle.player().orElse(null) != player) {
            return;
        }
        String damageTypeId = source.typeHolder()
                .unwrapKey()
                .map(key -> key.location().toString())
                .orElse(source.getMsgId());
        safetyService.recordDamage(new DamageCandidate(
                player.getUUID(),
                runtime.handle.generation(),
                currentTick,
                damageTypeId,
                Optional.ofNullable(source.getDirectEntity())
                        .map(Entity::getUUID),
                Optional.ofNullable(source.getEntity())
                        .map(Entity::getUUID),
                finalDamage));
    }

    /**
     * The inventory session integration may strengthen this gate with a viewer lock. Lifecycle
     * authority is always the minimum requirement.
     */
    public boolean mayActionMutateInventory(
            UUID botId, long expectedGeneration) {
        requireServerThread();
        return inventorySessions
                .mutationGate()
                .mayMutate(botId, expectedGeneration);
    }

    /**
     * Cleanup may target an exact dead body, but it must never race a remote
     * inventory viewer.
     */
    public boolean mayCleanupMutateInventory(
            UUID botId, long expectedGeneration) {
        requireServerThread();
        if (resolveCleanupTarget(
                        botId, expectedGeneration)
                .isEmpty()) {
            return false;
        }
        return inventorySessions
                .sessionForBot(botId)
                .isEmpty();
    }

    public BotInventorySessionManager.ForceCloseStatus
            forceCloseInventory(
                    UUID botId,
                    long expectedGeneration,
                    InventoryCloseReason reason) {
        requireServerThread();
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(reason, "reason");
        BotInventorySessionManager.ForceCloseStatus status =
                inventorySessions.forceCloseBot(
                        botId, expectedGeneration, reason);
        if (status
                        == BotInventorySessionManager.ForceCloseStatus
                                .CLOSE_REQUESTED
                || status
                        == BotInventorySessionManager.ForceCloseStatus
                                .ALREADY_CLOSING) {
            inventorySessions
                    .sessionForBot(botId)
                    .filter(session ->
                            session.token().botGeneration()
                                    == expectedGeneration)
                    .ifPresent(session ->
                            closeInventorySession(
                                    session.token(), reason));
        }
        return status;
    }

    /**
     * Opens the bot's real inventory through a generation-bound single-viewer session.
     */
    public BotInventorySessionManager.OpenStatus openInventory(
            ServerPlayer viewer, BotServerPlayer bot) {
        requireServerThread();
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(bot, "bot");
        if (viewer instanceof BotServerPlayer) {
            return BotInventorySessionManager.OpenStatus.PERMISSION_DENIED;
        }

        long generation = bot.runtimeHandle().generation();
        BotActionTarget target =
                inspectActionTarget(bot.getUUID(), generation);
        if (target.status() != BotActionTargetStatus.ACTIVE
                || target.player().orElse(null) != bot) {
            return BotInventorySessionManager.OpenStatus.BOT_NOT_ACTIVE;
        }

        BotInventorySessionManager.OpenStatus probe =
                inventorySessions.probeOpen(
                        bot.getUUID(),
                        generation,
                        viewer.getUUID());
        if (probe != BotInventorySessionManager.OpenStatus.OPENING) {
            if (probe
                    != BotInventorySessionManager
                            .OpenStatus.EXISTING_SESSION) {
                return probe;
            }
            BotInventorySession existingSession =
                    inventorySessions
                            .sessionForBot(bot.getUUID())
                            .orElse(null);
            return existingSession == null
                    ? BotInventorySessionManager.OpenStatus
                            .SESSION_CLOSING
                    : finishExistingInventoryOpen(
                            viewer, existingSession);
        }

        /*
         * 先让动作运行时把菜单事务收口，再创建查看者写锁。反过来会让
         * cleanup 自己被 mayCleanupMutateInventory 拒绝，留下不安全 prefix。
         */
        BotActionRuntime.GenerationCancellationResult cancellation;
        try {
            long currentTick = server.getTickCount();
            closeSelfDefenseGeneration(bot.getUUID(), generation, currentTick);
            cancellation = actionRuntime.cancelBotGenerationNow(
                    bot.getUUID(),
                    generation,
                    ActionCancellationReason.LIFECYCLE,
                    currentTick);
        } catch (RuntimeException exception) {
            BotPlayer.LOGGER.error(
                    "Refused BotPlayer inventory menu for bot {} generation {} because "
                            + "the exclusive action drain failed",
                    bot.getUUID(),
                    generation,
                    exception);
            return BotInventorySessionManager.OpenStatus.BOT_LOCKED;
        }
        if (!cancellation.safeForExclusiveMutation()) {
            BotPlayer.LOGGER.error(
                    "Refused BotPlayer inventory menu for bot {} generation {} after unsafe "
                            + "action drain: cleanupFailures={}, quarantined={}, "
                            + "ticketRemaining={}, leaseRemaining={}",
                    bot.getUUID(),
                    generation,
                    cancellation.cleanupFailures(),
                    cancellation.quarantined(),
                    cancellation.ticketRemaining(),
                    cancellation.leaseRemaining());
            return BotInventorySessionManager.OpenStatus.BOT_LOCKED;
        }

        BotActionTarget refreshedTarget =
                inspectActionTarget(bot.getUUID(), generation);
        if (refreshedTarget.status()
                        != BotActionTargetStatus.ACTIVE
                || refreshedTarget.player().orElse(null) != bot) {
            return BotInventorySessionManager.OpenStatus.BOT_NOT_ACTIVE;
        }

        BotInventorySessionManager.OpenResult result =
                inventorySessions.open(
                        bot.getUUID(),
                        generation,
                        viewer.getUUID());
        if (result.status()
                != BotInventorySessionManager.OpenStatus.OPENING) {
            return result.status()
                            == BotInventorySessionManager
                                    .OpenStatus.EXISTING_SESSION
                    ? finishExistingInventoryOpen(
                            viewer,
                            result.session().orElseThrow())
                    : result.status();
        }

        InventorySessionToken token =
                result.session().orElseThrow().token();
        BotInventorySession pendingSession =
                inventorySessions
                        .sessionForBot(bot.getUUID())
                        .filter(session ->
                                session.token().equals(token))
                        .orElse(null);
        if (pendingSession == null
                || pendingSession.state()
                        != InventorySessionState.OPENING
                || !inventorySessions
                        .revalidate(token)
                        .valid()) {
            failInventoryOpen(token, viewer);
            return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
        }

        OptionalInt menuId;
        try {
            menuId = viewer.openMenu(
                    new SimpleMenuProvider(
                            (containerId, viewerInventory, player) ->
                                    new BotInventoryMenu(
                                            containerId,
                                            viewerInventory,
                                            bot,
                                            token,
                                            inventorySessions),
                            Component.translatable(
                                    "container.botplayer.inventory")),
                    extraData -> extraData.writeVarInt(bot.getId()));
        } catch (RuntimeException exception) {
            failInventoryOpen(token, viewer);
            BotPlayer.LOGGER.error(
                    "Failed to open BotPlayer inventory menu for bot {} generation {}",
                    bot.getUUID(),
                    generation,
                    exception);
            return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
        }
        if (menuId.isEmpty()) {
            failInventoryOpen(token, viewer);
            return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
        }

        BotInventorySessionManager.OpenConfirmationStatus confirmation =
                inventorySessions.markOpened(token);
        if (confirmation
                        != BotInventorySessionManager
                                .OpenConfirmationStatus.OPENED
                && confirmation
                        != BotInventorySessionManager
                                .OpenConfirmationStatus.ALREADY_OPEN) {
            failInventoryOpen(token, viewer);
            return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
        }
        return result.status();
    }

    private BotInventorySessionManager.OpenStatus finishExistingInventoryOpen(
            ServerPlayer viewer,
            BotInventorySession session) {
        InventorySessionToken token =
                session.token();
        if (viewer.containerMenu instanceof BotInventoryMenu menu
                && menu.sessionToken().filter(token::equals).isPresent()) {
            return BotInventorySessionManager.OpenStatus.EXISTING_SESSION;
        }
        closeInventorySession(token, InventoryCloseReason.MENU_REPLACED);
        return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
    }

    public BotServerPlayer spawn(CommandSourceStack source, String requestedName) {
        requireServerThread();
        if (stopping) {
            throw new IllegalStateException("The server is stopping");
        }
        if (!VALID_NAME.matcher(requestedName).matches()) {
            throw new IllegalArgumentException(
                    "Bot name must contain 1-16 ASCII letters, digits, or underscores");
        }
        if (runtimes.size() >= BotPlayerConfig.MAX_BOTS.get()) {
            throw new IllegalStateException("The configured bot limit has been reached");
        }
        if (isNameInUse(requestedName)) {
            throw new IllegalArgumentException("A player or bot with that name is already online");
        }

        @Nullable UUID proposedOwnerId =
                source.getEntity() instanceof ServerPlayer owner
                                && !(owner instanceof BotServerPlayer)
                        ? owner.getUUID()
                        : null;
        BotProfile profile = roster.getOrCreate(requestedName, proposedOwnerId);
        UUID botId = profile.botId();
        String canonicalName = profile.name();
        if (!exactUuidBodies(botId).isEmpty()) {
            throw new IllegalArgumentException("That BotPlayer identity is already online");
        }
        VanillaDeathTicket tombstoneRecoveryTicket;
        try {
            tombstoneRecoveryTicket = deathTombstones
                    .read(botId)
                    .orElse(null);
            VanillaDeathTicket playerDataRecoveryTicket =
                    deathPlayerDataCommitter
                            .discoverHandoff(botId)
                            .orElse(null);
            if (tombstoneRecoveryTicket == null) {
                tombstoneRecoveryTicket =
                        playerDataRecoveryTicket;
            } else if (playerDataRecoveryTicket != null
                    && !tombstoneRecoveryTicket.equals(
                            playerDataRecoveryTicket)) {
                throw new IOException(
                        "Tombstone and playerdata death handoff conflict");
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not validate BotPlayer vanilla-death tombstone",
                    exception);
        }
        BotRuntimeHandle handle = handlesByBot.computeIfAbsent(
                botId,
                ignored -> new BotRuntimeHandle(
                        botId,
                        canonicalName,
                        profile.ownerId().orElse(null)));
        if (!handle.name().equals(canonicalName)
                || !handle.ownerId().equals(profile.ownerId())) {
            throw new IllegalStateException(
                    "The retained runtime handle does not match the persistent bot profile");
        }
        if (handle.player().isPresent()) {
            throw new IllegalStateException(
                    "The retained runtime handle is still attached to a player");
        }
        /*
         * 进程重启会重建内存 handle；先把 generation 锚定到已通过 schema/integrity
         * 校验且身份精确匹配的耐久记录，再 attach 新 body。任何不匹配 checkpoint
         * 都不会被用作 floor，更不会获得恢复资格。
         */
        SkillCheckpointRecoverySource pendingSkillCheckpointSource =
                skillCheckpoints.recoverySource(botId);
        SkillCheckpoint pendingSkillCheckpoint = pendingSkillCheckpointSource
                .checkpoint()
                .filter(checkpoint -> checkpoint.botId().equals(botId)
                        && checkpoint.playerId().equals(botId)
                        && checkpoint.serverInstanceId().equals(
                                roster.serverInstanceId()))
                .orElse(null);
        long durableGenerationFloor = tombstoneRecoveryTicket == null
                ? 0L
                : tombstoneRecoveryTicket.generation();
        if (pendingSkillCheckpoint != null) {
            durableGenerationFloor = Math.max(
                    durableGenerationFloor,
                    pendingSkillCheckpoint.generation());
        }
        handle.rebaseGenerationFloorBeforeAttach(durableGenerationFloor);
        RuntimeEntry runtime = new RuntimeEntry(handle, BotLifecycleState.SPAWNING);
        runtimes.put(botId, runtime);

        ServerLevel level = source.getLevel();
        Vec3 position = source.getPosition();
        Vec2 rotation = source.getRotation();
        GameProfile gameProfile = new GameProfile(botId, canonicalName);
        ClientInformation clientInformation = ClientInformation.createDefault();
        BotServerPlayer player =
                new BotServerPlayer(server, level, gameProfile, clientInformation, handle);
        if (tombstoneRecoveryTicket != null) {
            player.installDeathHandoffTicket(
                    tombstoneRecoveryTicket);
            player.suppressPlayerDataSaveUntilReleased();
            player.armDeathRetirementSaveFence();
        }
        BotConnection connection = new BotConnection();
        boolean hasExistingPlayerData = Files.isRegularFile(server
                .getWorldPath(LevelResource.PLAYER_DATA_DIR)
                .resolve(botId + ".dat"));

        try {
            CommonListenerCookie cookie = new CommonListenerCookie(
                    gameProfile,
                    0,
                    clientInformation,
                    false,
                    ConnectionType.OTHER);
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            if (!hasExistingPlayerData) {
                player.teleportTo(
                        level,
                        position.x,
                        position.y,
                        position.z,
                        Set.of(),
                        rotation.y,
                        rotation.x);
            }
            handle.attach(player);
            VanillaDeathTicket recoveryTicket = player
                    .deathHandoffTicket()
                    .orElse(tombstoneRecoveryTicket);
            if (recoveryTicket != null) {
                player.normalizeConsumedDeathBody(
                        recoveryTicket);
                player.suppressPlayerDataSaveUntilReleased();
                player.armDeathRetirementSaveFence();
                onDeath(player);
            } else if (player.isDeadOrDying()) {
                onDeath(player);
            } else {
                transition(runtime, BotLifecycleState.ACTIVE);
                perceptionService.activate(
                        botId, handle.generation());
                if (tombstoneRecoveryTicket == null
                        && pendingSkillCheckpoint != null
                        && !pendingSkillCheckpoint.continuationState()
                                .isTerminal()) {
                    runtime.pendingSkillCheckpointRecovery =
                            pendingSkillCheckpointSource;
                } else if (tombstoneRecoveryTicket == null
                        && pendingSkillCheckpointSource.loadStatus()
                                != SkillCheckpointLoadStatus.MISSING) {
                    /*
                     * 损坏、未知 schema 与 terminal fence 都不会静默变成“没有记录”。
                     * 但它们也绝不能在新 body 上反复排队一个不可能安全恢复的 suffix。
                     */
                    BotPlayer.LOGGER.warn(
                            "P5A checkpoint recovery is unavailable for BotPlayer {} ({}): {}",
                            canonicalName,
                            botId,
                            pendingSkillCheckpoint == null
                                    ? pendingSkillCheckpointSource.loadStatus()
                                            .name()
                                    : "terminal");
                }
            }
            BotPlayer.LOGGER.info(
                    "Spawned BotPlayer {} ({}) in {}",
                    canonicalName,
                    botId,
                    level.dimension().location());
            return player;
        } catch (RuntimeException exception) {
            rollbackFailedSpawn(player, connection, runtime);
            throw exception;
        }
    }

    public boolean removeByName(String name, Component reason) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        if (runtime == null) {
            return false;
        }
        disconnect(runtime, reason);
        return true;
    }

    public List<BotSnapshot> snapshots() {
        requireServerThread();
        return runtimes.values().stream()
                .map(runtime -> new BotSnapshot(
                        runtime.handle.botId(),
                        runtime.handle.name(),
                        runtime.state,
                        runtime.handle.generation()))
                .sorted(Comparator.comparing(BotSnapshot::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public NavigationSubmission startNavigation(
            String name, GridPoint target) {
        return startNavigation(
                name, target, NavigationPolicy.safeDefault());
    }

    public NavigationSubmission startNavigation(
            String name,
            GridPoint target,
            NavigationPolicy policy) {
        requireServerThread();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(policy, "policy");
        RuntimeEntry runtime = findByName(name);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE) {
            return NavigationSubmission.rejected(
                    NavigationSubmission.Status.BOT_NOT_ACTIVE,
                    "没有活动 BotPlayer：" + name);
        }
        BotServerPlayer player =
                runtime.handle.player().orElseThrow();
        long currentTick = server.getTickCount();
        UUID navigationId = UUID.randomUUID();
        NavigationRequest request = new NavigationRequest(
                navigationId,
                runtime.handle.botId(),
                runtime.handle.generation(),
                new NavigationGoal.ExactPosition(
                        player.serverLevel()
                                .dimension()
                                .location()
                                .toString(),
                        target,
                        0,
                        1),
                policy,
                currentTick + 12_000L,
                12_000,
                "command:" + navigationId);
        return navigationService.submit(request, currentTick);
    }

    public boolean stopNavigation(String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        if (runtime == null) {
            return false;
        }
        NavigationSessionView view = navigationService
                .inspect(runtime.handle.botId())
                .orElse(null);
        return view != null
                && navigationService.cancel(
                        view.navigationId(),
                        server.getTickCount(),
                        "由管理命令取消");
    }

    public Optional<NavigationSessionView> navigationSession(
            String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        return runtime == null
                ? Optional.empty()
                : navigationService.inspect(runtime.handle.botId());
    }

    public Optional<SafetyIncidentView> safetyIncident(
            String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        return runtime == null
                ? Optional.empty()
                : safetyService.inspect(runtime.handle.botId());
    }

    public Optional<SafetyFrame> latestSafetyFrame(String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        return runtime == null
                ? Optional.empty()
                : safetyService.latestFrame(runtime.handle.botId());
    }

    public Optional<SurvivalSkillRunView> survivalSkillRun(
            String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        return runtime == null
                ? Optional.empty()
                : survivalSkillService.inspect(
                        runtime.handle.botId());
    }

    /**
     * 按稳定身份读取最近技能视图，供 body 已隔离移除后的诊断与验收使用。
     */
    public Optional<SurvivalSkillRunView> survivalSkillRun(
            UUID botId) {
        requireServerThread();
        Objects.requireNonNull(botId, "botId");
        return survivalSkillService.inspect(botId);
    }

    /**
     * 读取通用 P5A DAG 的最近运行视图；body 已经退役时仍可用于诊断。
     */
    public Optional<SkillRunView> skillRun(UUID botId) {
        requireServerThread();
        Objects.requireNonNull(botId, "botId");
        return skillRuntime.inspect(botId);
    }

    /** P5A 有限自卫的只读运行视图。 */
    public Optional<SelfDefenseSkillService.RunView> selfDefenseRun(
            UUID botId) {
        requireServerThread();
        return selfDefenseSkillService.latestView(
                Objects.requireNonNull(botId, "botId"));
    }

    /**
     * 通过 generation/revision 绑定的 TaskSensor 查询世界；调用方不能跳过服务额度或
     * 直接持有 Minecraft 活对象。
     */
    public TaskSensorResponse queryTaskSensor(TaskSensorQuery query) {
        requireServerThread();
        Objects.requireNonNull(query, "query");
        long currentTick = server.getTickCount();
        taskSensorService.beginTick(currentTick);
        return taskSensorService.query(
                query, currentTick, taskSensorAdapter);
    }

    /**
     * 重载只会解析、静态校验并暂存外部 JSON；仅当持久账本中存在同一 revision 的实际审核者
     * 时才恢复批准，绝不会自动批准新 hash/version 或执行计划。
     */
    public SkillPackManager.ReloadResult reloadSkillPacks() {
        requireServerThread();
        long currentTick = server.getTickCount();
        SkillPackManager.ReloadResult result = skillPackManager.reload(
                currentTick);
        skillPackApprovalLedger.retainOnly(
                skillPackManager.records().stream()
                        .filter(record -> record.validation().valid())
                        .map(SkillPackRecord::revision)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        for (SkillPackTransition transition : result.transitions()) {
            SkillPackRecord record = transition.record().orElse(null);
            if (record == null) {
                continue;
            }
            UUID reviewerId = skillPackApprovalLedger
                    .reviewer(record.revision())
                    .orElse(null);
            if (reviewerId != null) {
                skillPackManager.restoreApproved(
                        record.revision(), reviewerId, currentTick);
            }
        }
        return result;
    }

    public SkillPackTransition approveSkillPack(
            SkillPackRevision revision, UUID administratorId) {
        requireServerThread();
        SkillPackRevision exactRevision = Objects.requireNonNull(
                revision, "revision");
        UUID exactAdministratorId = Objects.requireNonNull(
                administratorId, "administratorId");
        if (!skillPackApprovalLedger.canRecord(exactRevision)
                && skillPackManager.approved(exactRevision).isEmpty()) {
            return new SkillPackTransition(
                    SkillPackTransition.Status.CAPACITY_EXCEEDED,
                    skillPackManager.find(exactRevision.id()));
        }
        SkillPackTransition transition = skillPackManager.approve(
                exactRevision, exactAdministratorId, server.getTickCount());
        if (transition.status() == SkillPackTransition.Status.APPROVED) {
            skillPackApprovalLedger.approve(
                    exactRevision, exactAdministratorId);
        } else if (transition.status()
                == SkillPackTransition.Status.ALREADY_APPROVED) {
            transition.record()
                    .flatMap(SkillPackRecord::reviewedBy)
                    .filter(exactAdministratorId::equals)
                    .ifPresent(reviewerId -> skillPackApprovalLedger.approve(
                            exactRevision, reviewerId));
        }
        return transition;
    }

    public SkillPackTransition rejectSkillPack(
            SkillPackRevision revision, UUID administratorId) {
        requireServerThread();
        SkillPackRevision exactRevision = Objects.requireNonNull(
                revision, "revision");
        SkillPackTransition transition = skillPackManager.reject(
                exactRevision,
                Objects.requireNonNull(administratorId, "administratorId"),
                server.getTickCount());
        if (transition.status() == SkillPackTransition.Status.REJECTED
                || transition.status()
                        == SkillPackTransition.Status.ALREADY_REJECTED) {
            skillPackApprovalLedger.revoke(exactRevision);
        }
        return transition;
    }

    public Optional<SkillPackRecord> skillPack(SkillPackId id) {
        requireServerThread();
        return skillPackManager.find(Objects.requireNonNull(id, "id"));
    }

    /** 供管理员诊断使用的稳定、只读技能包审核快照。 */
    public List<SkillPackRecord> skillPacks() {
        requireServerThread();
        return skillPackManager.records();
    }

    /**
     * 仅运行精确 revision 已获管理员批准的声明式计划；revision、descriptor、计划
     * bot identity 会在审批与提交两层再次核验。
     */
    public SkillRunSubmission submitApprovedSkillPack(
            String name, SkillPackRevision revision) {
        requireServerThread();
        SkillPackRecord approved = skillPackManager.approved(
                Objects.requireNonNull(revision, "revision")).orElse(null);
        if (approved == null) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.PACK_NOT_APPROVED,
                    "技能包尚未以精确版本获管理员批准");
        }
        return submitSkillPlan(name,
                approved.candidate().definition().plan());
    }

    /**
     * 仅接受当前活动 generation 的已审核计划。计划的 node handler 由服务器启动时固定
     * 注册，外部技能包不能把可执行对象注入到这里。
     */
    public SkillRunSubmission submitSkillPlan(
            String name, SkillPlan plan) {
        requireServerThread();
        Objects.requireNonNull(plan, "plan");
        RuntimeEntry runtime = findByName(name);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE
                || runtime.handle.player().orElse(null) == null) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.BOT_NOT_ACTIVE,
                    "没有活动 BotPlayer 可执行技能计划");
        }
        if (!runtime.handle.botId().equals(plan.botId())) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.PLAN_BOT_MISMATCH,
                    "技能计划 Bot 身份与活动实例不一致");
        }
        if (requiresProductionCheckpointScope(plan)
                && !isCanonicalBootstrapPlan(plan)) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.INVALID_BUILTIN_PLAN,
                    "bootstrap iron 只能使用服务器编译的完整固定计划");
        }
        if (runtime.pendingSkillCheckpointRecovery != null) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.BOT_BUSY,
                    "耐久技能检查点正在恢复；恢复完成前拒绝新的 P5 计划");
        }
        if (survivalSkillService.inspect(runtime.handle.botId())
                .filter(view -> view.botGeneration()
                        == runtime.handle.generation()
                        && !view.state().isTerminal())
                .isPresent()) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.BOT_BUSY,
                    "原有生存技能仍持有该 Bot 的背包动作权限");
        }
        return skillRuntime.submit(new SkillRunRequest(
                runtime.handle.botId(),
                runtime.handle.generation(),
                plan,
                server.getTickCount()));
    }

    public SurvivalSkillSubmission startBasicArmor(
            String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        if (runtime == null
                || runtime.state
                        != BotLifecycleState.ACTIVE) {
            return SurvivalSkillSubmission.rejected(
                    SurvivalSkillSubmission.Status.BOT_NOT_ACTIVE,
                    "没有活动 BotPlayer：" + name);
        }
        BotServerPlayer player =
                runtime.handle.player().orElse(null);
        if (player == null
                || player.runtimeHandle()
                        != runtime.handle
                || runtime.handle.generation() <= 0L) {
            return SurvivalSkillSubmission.rejected(
                    SurvivalSkillSubmission.Status.BOT_NOT_ACTIVE,
                    "BotPlayer 活动代际尚未就绪");
        }
        if (runtime.pendingSkillCheckpointRecovery != null) {
            return SurvivalSkillSubmission.rejected(
                    SurvivalSkillSubmission.Status.BOT_BUSY,
                    "耐久技能检查点正在恢复；恢复完成前拒绝新的 P5 技能");
        }
        if (skillRuntime.inspect(runtime.handle.botId())
                .filter(view -> view.botGeneration()
                        == runtime.handle.generation()
                        && !view.state().isTerminal())
                .isPresent()) {
            return SurvivalSkillSubmission.rejected(
                    SurvivalSkillSubmission.Status.BOT_BUSY,
                    "通用 P5A 技能仍持有该 Bot 的背包动作权限");
        }
        return survivalSkillService.startBasicArmor(
                player, server.getTickCount());
    }

    /**
     * 启动编译进服务器二进制的木头到铁镐 P5A 纵切片。
     *
     * <p>它不是外部 JSON 的快捷执行入口：计划由
     * {@link ProductionSkillPlanCompiler#compileWoodToIronPick(UUID, long)} 每次从固定模板
     * 重新编译，随后仍完整经过 {@link #submitSkillPlan(String, SkillPlan)} 的活动 body、
     * generation、恢复 pending 与既有生存技能互斥检查。</p>
     */
    public SkillRunSubmission startBootstrapIron(String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE
                || runtime.handle.player().orElse(null) == null) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.BOT_NOT_ACTIVE,
                    "没有活动 BotPlayer 可执行 bootstrap iron 生产计划");
        }
        final long revision;
        try {
            revision = nextBuiltinSkillPlanRevision();
        } catch (IllegalStateException exception) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.PLAN_REVISION_UNAVAILABLE,
                    "内建生产计划 revision 已耗尽；拒绝重用旧 checkpoint 身份");
        }
        final SkillPlan plan;
        try {
            plan = ProductionSkillPlanCompiler.p5aDefault()
                    .compileWoodToIronPick(runtime.handle.botId(), revision);
        } catch (RuntimeException exception) {
            BotPlayer.LOGGER.error(
                    "Cannot compile the built-in P5A bootstrap iron plan",
                    exception);
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.INVALID_BUILTIN_PLAN,
                    "内建 bootstrap iron 计划未通过固定模板校验");
        }
        return submitSkillPlan(name, plan);
    }

    private long nextBuiltinSkillPlanRevision() {
        if (nextBuiltinSkillPlanRevision < 1L) {
            throw new IllegalStateException(
                    "built-in plan revision counter is invalid");
        }
        long revision = nextBuiltinSkillPlanRevision;
        try {
            nextBuiltinSkillPlanRevision = Math.incrementExact(revision);
        } catch (ArithmeticException exception) {
            /*
             * revision 不能回绕到负值或一：这会使同一进程里的新 run 与旧 checkpoint
             * 在审计面上不可区分，因此宁可拒绝后续内建提交。
             */
            nextBuiltinSkillPlanRevision = -1L;
        }
        return revision;
    }

    public Optional<ObservationSnapshot> latestPerception(
            String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE) {
            return Optional.empty();
        }
        return perceptionService.latest(
                runtime.handle.botId(),
                runtime.handle.generation());
    }

    public Optional<ObservationSnapshot> latestPerception(
            UUID botId, long expectedGeneration) {
        requireServerThread();
        return perceptionService.latest(
                botId, expectedGeneration);
    }

    public List<WorldFact> recentPerceptionFacts(
            String name, int limit) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE) {
            return List.of();
        }
        return perceptionService.recentFacts(
                runtime.handle.botId(),
                runtime.handle.generation(),
                limit);
    }

    public long perceptionCoverageGaps(String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE) {
            return 0L;
        }
        return perceptionService.coverageGapCount(
                runtime.handle.botId(),
                runtime.handle.generation());
    }

    /**
     * 仅供服务器诊断与 GameTest 使用的权威事件内部水位。
     */
    public long perceptionAuthoritySequence() {
        requireServerThread();
        return perceptionService.eventBus()
                .currentAuthoritySeq();
    }

    public void correctPerceivedActivity(
            CommandSourceStack source,
            String botName,
            String actorName,
            String correctedActivity) {
        requireServerThread();
        Objects.requireNonNull(source, "source");
        if (!source.hasPermission(2)) {
            throw new IllegalArgumentException(
                    "P3 activity correction requires operator permission");
        }
        RuntimeEntry runtime = findByName(botName);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE) {
            throw new IllegalArgumentException(
                    "No active BotPlayer named " + botName);
        }
        ServerPlayer actor = server.getPlayerList()
                .getPlayers()
                .stream()
                .filter(player -> player.getScoreboardName()
                        .equalsIgnoreCase(actorName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No online player named " + actorName));
        ServerPlayer corrector = source.getEntity()
                        instanceof ServerPlayer sourcePlayer
                ? sourcePlayer
                : null;
        UUID correctorId = corrector == null
                ? UUID.nameUUIDFromBytes(
                        ("botplayer:command_source:"
                                        + source.getTextName())
                                .getBytes(StandardCharsets.UTF_8))
                : corrector.getUUID();
        String correctorName = corrector == null
                ? source.getTextName()
                : corrector.getScoreboardName();
        perceptionService.recordActivityCorrection(
                runtime.handle.botId(),
                runtime.handle.generation(),
                actor.getUUID(),
                actor.getScoreboardName(),
                correctorId,
                correctorName,
                correctedActivity,
                server.getTickCount());
    }

    public long droppedSoundObservations() {
        requireServerThread();
        return perceptionService.droppedSounds();
    }

    public List<ActionTransition> actionTransitionHistory(int limit) {
        requireServerThread();
        return actionRuntime.transitionHistory(limit);
    }

    public List<BotLifecycleTransition> lifecycleTransitionHistory(int limit) {
        requireServerThread();
        if (limit < 0 || limit > LIFECYCLE_HISTORY_CAPACITY) {
            throw new IllegalArgumentException(
                    "limit must be between 0 and "
                            + LIFECYCLE_HISTORY_CAPACITY);
        }
        if (limit == 0 || lifecycleHistory.isEmpty()) {
            return List.of();
        }
        int skip = Math.max(0, lifecycleHistory.size() - limit);
        return lifecycleHistory.stream().skip(skip).toList();
    }

    /**
     * Applies one generation-owned movement intent immediately before Player#doTick.
     */
    public void applyPlayerInput(BotServerPlayer player) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        long generation = player.runtimeHandle().generation();
        if (generation <= 0) {
            MinecraftPlayerInputAdapter.clear(player);
            return;
        }
        BotActionTarget target =
                inspectActionTarget(player.getUUID(), generation);
        if (target.status() != BotActionTargetStatus.ACTIVE
                || target.player().orElse(null) != player) {
            MinecraftPlayerInputAdapter.clear(player);
            return;
        }
        inputController.applyOnce(
                player.getUUID(),
                generation,
                server.getTickCount(),
                input -> MinecraftPlayerInputAdapter.apply(player, input));
    }

    /**
     * Publishes the position produced by vanilla physics to listener and chunk tracking state.
     */
    public void syncPlayerInputAfterPhysics(BotServerPlayer player) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        long generation = player.runtimeHandle().generation();
        if (generation <= 0) {
            return;
        }
        BotActionTarget target =
                inspectActionTarget(player.getUUID(), generation);
        if (target.status() == BotActionTargetStatus.ACTIVE
                && target.player().orElse(null) == player) {
            MinecraftPlayerInputAdapter.syncAfterPhysics(player);
        }
    }

    /**
     * Cancels world-local work and rotates generation after a completed dimension transition.
     */
    public void onChangedDimension(BotServerPlayer player) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null || runtime.state != BotLifecycleState.ACTIVE) {
            return;
        }
        long oldGeneration = runtime.handle.generation();
        if (oldGeneration <= 0
                || resolveCleanupTarget(
                                        player.getUUID(),
                                        oldGeneration)
                                .orElse(null)
                        != player) {
            return;
        }
        GenerationRetirement retirement =
                retireGenerationBestEffort(
                        runtime,
                        oldGeneration,
                        InventoryCloseReason
                                .BOT_DIMENSION_CHANGE);
        if (runtimes.get(
                                runtime.handle.botId())
                        != runtime
                || runtime.disconnectingPlayer
                        != null
                || hasRequestedListenerDisconnect(
                        runtime)) {
            return;
        }
        if (!retirement.safelyClosed()) {
            BotPlayer.LOGGER.error(
                    "Refusing to rotate BotPlayer {} ({}) after dimension change because generation {} did not produce safe action and inventory-layout receipts",
                    runtime.handle.name(),
                    runtime.handle.botId(),
                    oldGeneration,
                    retirement.failure());
            prepareListenerDisconnect(
                    runtime,
                    player,
                    oldGeneration,
                    retirement);
            disconnect(
                    runtime,
                    Component.literal(
                            "Bot generation could not be safely closed after dimension change"));
            return;
        }
        if (runtime.state
                        != BotLifecycleState.ACTIVE
                || runtime.handle.generation()
                        != oldGeneration
                || resolveCleanupTarget(
                                        player.getUUID(),
                                        oldGeneration)
                                .orElse(null)
                        != player) {
            disconnect(
                    runtime,
                    Component.literal(
                            "Bot authority changed during dimension generation closure"));
            return;
        }
        runtime.handle.rotateGeneration(player);
        perceptionService.activate(
                runtime.handle.botId(),
                runtime.handle.generation());
    }

    /**
     * Resolves the current authoritative player only for the requested ACTIVE generation.
     */
    public Optional<BotServerPlayer> resolveActive(
            UUID botId, long expectedGeneration) {
        return resolveActionTarget(botId, expectedGeneration);
    }

    /**
     * Resolves the authoritative ACTIVE body for a generation-bound action.
     */
    public Optional<BotServerPlayer> resolveActionTarget(
            UUID botId, long expectedGeneration) {
        return inspectActionTarget(botId, expectedGeneration).player();
    }

    /**
     * Resolves action authority without ever treating PlayerList membership alone as sufficient.
     *
     * <p>The packet listener must already point at the same body. During vanilla respawn the new
     * body enters PlayerList before ServerGamePacketListenerImpl swaps its authoritative player
     * reference; that interim body is intentionally rejected.
     */
    public BotActionTarget inspectActionTarget(
            UUID botId, long expectedGeneration) {
        requireServerThread();
        requireActionIdentity(botId, expectedGeneration);
        BotRuntimeHandle retainedHandle = handlesByBot.get(botId);
        if (stopping) {
            return target(
                    BotActionTargetStatus.SERVER_STOPPING,
                    retainedHandle,
                    null);
        }

        RuntimeEntry runtime = runtimes.get(botId);
        if (runtime == null) {
            if (retainedHandle == null) {
                return new BotActionTarget(
                        BotActionTargetStatus.UNKNOWN_BOT,
                        0,
                        Optional.empty());
            }
            return target(
                    retainedHandle.generation() == expectedGeneration
                            ? BotActionTargetStatus.NOT_ACTIVE
                            : BotActionTargetStatus.STALE_GENERATION,
                    retainedHandle,
                    null);
        }
        if (retainedHandle != runtime.handle) {
            return target(
                    BotActionTargetStatus.INVALID_INSTANCE,
                    runtime.handle,
                    null);
        }
        if (runtime.handle.generation() != expectedGeneration) {
            return target(
                    BotActionTargetStatus.STALE_GENERATION,
                    runtime.handle,
                    null);
        }
        if (runtime.disconnectingPlayer != null) {
            return target(
                    BotActionTargetStatus.NOT_ACTIVE,
                    runtime.handle,
                    null);
        }
        if (runtime.state != BotLifecycleState.ACTIVE) {
            return target(
                    BotActionTargetStatus.NOT_ACTIVE,
                    runtime.handle,
                    null);
        }
        if (runtime.replacementHandoffInProgress) {
            return target(
                    BotActionTargetStatus.NOT_ACTIVE,
                    runtime.handle,
                    null);
        }

        BotServerPlayer player = runtime.handle.player().orElse(null);
        if (!isAuthoritativeInstance(runtime, player, true)) {
            return target(
                    BotActionTargetStatus.INVALID_INSTANCE,
                    runtime.handle,
                    null);
        }
        return target(
                BotActionTargetStatus.ACTIVE,
                runtime.handle,
                player);
    }

    /**
     * Resolves the exact body for cleanup, including a dead body before
     * listener replacement or a synchronously staged same-connection
     * replacement inheritor for an already armed old-generation cleanup.
     */
    public Optional<BotServerPlayer> resolveCleanupTarget(
            UUID botId, long expectedGeneration) {
        requireServerThread();
        requireActionIdentity(botId, expectedGeneration);
        RuntimeEntry runtime = runtimes.get(botId);
        if (runtime == null
                || handlesByBot.get(botId) != runtime.handle
                || runtime.handle.generation() != expectedGeneration) {
            return Optional.empty();
        }
        BotServerPlayer stagedPlayer =
                runtime.stagedCleanupPlayer;
        if (runtime.stagedCleanupGeneration
                                == expectedGeneration
                && isStagedCleanupAuthority(
                        runtime, stagedPlayer)) {
            return Optional.of(stagedPlayer);
        }
        BotServerPlayer player = runtime.handle.player().orElse(null);
        return isBoundListenerDisconnectTarget(
                                runtime, player)
                        || isRequestedRetirementCleanupTarget(
                                runtime, player)
                        || isAuthoritativeInstance(
                                runtime, player, false)
                ? Optional.of(player)
                : Optional.empty();
    }

    /**
     * Reports whether an exact cleanup target is a replacement body that
     * inherited only body-local state from the requested old generation.
     */
    public boolean isStagedReplacementCleanupTarget(
            UUID botId,
            long expectedGeneration,
            BotServerPlayer player) {
        requireServerThread();
        requireActionIdentity(
                botId, expectedGeneration);
        Objects.requireNonNull(player, "player");
        RuntimeEntry runtime = runtimes.get(botId);
        return runtime != null
                && handlesByBot.get(botId)
                        == runtime.handle
                && runtime.handle.generation()
                        == expectedGeneration
                && runtime.stagedCleanupGeneration
                        == expectedGeneration
                && runtime.stagedCleanupPlayer
                        == player
                && isStagedCleanupAuthority(
                        runtime, player);
    }

    /**
     * Verifies exact persistent ownership and opens the client-local credential screen.
     */
    public void openCredentialScreen(ServerPlayer requester, String name) {
        requireServerThread();
        if (stopping) {
            throw new IllegalStateException("The server is stopping");
        }
        if (requester instanceof BotServerPlayer) {
            throw new IllegalArgumentException("Only a real player can configure credentials");
        }

        RuntimeEntry runtime = findByName(name);
        if (runtime == null || runtime.state != BotLifecycleState.ACTIVE) {
            throw new IllegalArgumentException("No active BotPlayer named " + name);
        }
        if (!isExactOwner(runtime, requester)) {
            throw new IllegalArgumentException("You do not own that BotPlayer");
        }

        PacketDistributor.sendToPlayer(
                requester,
                new OpenCredentialScreenPayload(
                        roster.serverInstanceId(),
                        runtime.handle.botId(),
                        runtime.handle.name(),
                        Optional.ofNullable(activeAgentByBot.get(runtime.handle.botId()))));
    }

    /**
     * Applies a client-agent binding request after authenticating the sending real player.
     */
    public AgentBindingStatus updateAgentBinding(
            ServerPlayer requester, UUID botId, UUID agentId, boolean active) {
        requireServerThread();
        if (stopping) {
            return AgentBindingStatus.BOT_NOT_ACTIVE;
        }
        RuntimeEntry runtime = runtimes.get(botId);
        if (runtime == null || runtime.state != BotLifecycleState.ACTIVE) {
            return AgentBindingStatus.BOT_NOT_ACTIVE;
        }
        if (requester instanceof BotServerPlayer || !isExactOwner(runtime, requester)) {
            return AgentBindingStatus.NOT_OWNER;
        }

        UUID currentAgentId = activeAgentByBot.get(botId);
        if (!active) {
            if (currentAgentId == null) {
                return AgentBindingStatus.ALREADY_UNBOUND;
            }
            if (!currentAgentId.equals(agentId)) {
                return AgentBindingStatus.STALE_AGENT_ID;
            }
            clearAgentBinding(botId);
            return AgentBindingStatus.UNBOUND;
        }

        UUID boundBotId = botByActiveAgent.get(agentId);
        if (boundBotId != null && !boundBotId.equals(botId)) {
            return AgentBindingStatus.AGENT_ID_IN_USE;
        }
        if (agentId.equals(currentAgentId)) {
            return AgentBindingStatus.BOUND;
        }

        AgentBindingStatus status =
                currentAgentId == null
                        ? AgentBindingStatus.BOUND
                        : AgentBindingStatus.REPLACED;
        clearAgentBinding(botId);
        activeAgentByBot.put(botId, agentId);
        botByActiveAgent.put(agentId, botId);
        return status;
    }

    /**
     * Dispatches the one P6-R1 owner command path: a fixed completed snapshot from this command
     * Tick or its immediately preceding Tick may be reviewed by the owner client's locally
     * enabled Provider, but it cannot request an action or arbitrary chat completion.
     */
    public AiReviewOnlyDispatchReceipt requestAiReview(
            ServerPlayer requester, String name) {
        requireServerThread();
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(name, "name");
        if (stopping) {
            return AiReviewOnlyDispatchReceipt.rejected(
                    AiReviewOnlyDispatchStatus.BOT_NOT_ACTIVE);
        }
        if (requester instanceof BotServerPlayer) {
            return AiReviewOnlyDispatchReceipt.rejected(
                    AiReviewOnlyDispatchStatus.NOT_OWNER);
        }

        RuntimeEntry runtime = findByName(name);
        if (runtime == null || runtime.state != BotLifecycleState.ACTIVE) {
            return AiReviewOnlyDispatchReceipt.rejected(
                    AiReviewOnlyDispatchStatus.BOT_NOT_ACTIVE);
        }
        if (!isExactOwner(runtime, requester)) {
            return AiReviewOnlyDispatchReceipt.rejected(
                    AiReviewOnlyDispatchStatus.NOT_OWNER);
        }

        UUID botId = runtime.handle.botId();
        UUID ownerId = roster.findById(botId)
                .flatMap(BotProfile::ownerId)
                .orElse(null);
        ServerPlayer owner = ownerId == null
                ? null
                : server.getPlayerList().getPlayer(ownerId);
        if (owner == null
                || owner instanceof BotServerPlayer
                || !ownerId.equals(owner.getUUID())) {
            return AiReviewOnlyDispatchReceipt.rejected(
                    AiReviewOnlyDispatchStatus.OWNER_OFFLINE);
        }
        UUID agentId = activeAgentByBot.get(botId);
        if (agentId == null || !botId.equals(botByActiveAgent.get(agentId))) {
            return AiReviewOnlyDispatchReceipt.rejected(
                    AiReviewOnlyDispatchStatus.AGENT_NOT_BOUND);
        }

        long generation = runtime.handle.generation();
        long currentTick = server.getTickCount();
        ObservationSnapshot snapshot = perceptionService.latest(botId, generation).orElse(null);
        if (snapshot == null) {
            return AiReviewOnlyDispatchReceipt.rejected(
                    AiReviewOnlyDispatchStatus.SNAPSHOT_UNAVAILABLE);
        }
        final AiReviewOnlySnapshotProjection projection;
        try {
            projection = AiReviewOnlySnapshotProjection.fromRequestAiReviewCompletedSnapshot(
                    snapshot, botId, generation, currentTick);
        } catch (IllegalArgumentException exception) {
            return AiReviewOnlyDispatchReceipt.rejected(
                    AiReviewOnlyDispatchStatus.SNAPSHOT_NOT_CURRENT);
        }

        try {
            AiRequestDispatchReceipt dispatch = dispatchAiReviewOnlyRequest(
                    runtime,
                    owner,
                    ownerId,
                    agentId,
                    projection,
                    AiReviewOnlyContract.requestTemplate(projection));
            return AiReviewOnlyDispatchReceipt.dispatched(dispatch, projection);
        } catch (RuntimeException exception) {
            /* Do not attach exception text: local/Provider-shaped values must never reach chat. */
            closeAiProposalRequestForBot(botId);
            return AiReviewOnlyDispatchReceipt.rejected(
                    AiReviewOnlyDispatchStatus.INTERNAL_ERROR);
        }
    }

    /**
     * Opens and hands off exactly one fixed review dispatch. It is private so no scheduler or
     * caller can substitute a prompt, provider, model, tool policy, revision, or TTL.
     */
    private AiRequestDispatchReceipt dispatchAiReviewOnlyRequest(
            RuntimeEntry runtime,
            ServerPlayer owner,
            UUID ownerId,
            UUID agentId,
            AiReviewOnlySnapshotProjection projection,
            AiRequest requestTemplate) {
        UUID botId = runtime.handle.botId();
        if (!AiReviewOnlyContract.requestTemplate(projection).equals(requestTemplate)) {
            throw new IllegalArgumentException("review request template is not canonical");
        }
        AiClientRequestDispatch.requireDispatchableTemplate(
                AiReviewOnlyContract.PURPOSE,
                AiReviewOnlyContract.PROVIDER_ID,
                requestTemplate,
                AiReviewOnlyContract.REQUEST_TTL_TICKS);

        long issuedAtEpochMillis = System.currentTimeMillis();
        long ttlMillis;
        long expiresAtEpochMillis;
        try {
            ttlMillis = Math.multiplyExact(
                    AiReviewOnlyContract.REQUEST_TTL_TICKS, 50L);
            expiresAtEpochMillis = Math.addExact(issuedAtEpochMillis, ttlMillis);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("review request expiry overflow", exception);
        }

        /* A replacement closes the old gate and exact ticket before fresh correlation ids exist. */
        closeAiProposalRequestForBot(botId);
        AiProposalRequestEnvelope envelope = aiProposalSessionGate.open(
                botId,
                ownerId,
                agentId,
                runtime.handle.generation(),
                projection.snapshotId(),
                server.getTickCount(),
                AiReviewOnlyContract.REQUEST_TTL_TICKS,
                AiReviewOnlyContract.PURPOSE,
                true,
                AiReviewOnlyContract.firewallPolicy());
        try {
            AiClientRequestDispatch dispatch = AiClientRequestDispatch.fromEnvelope(
                    roster.serverInstanceId(),
                    envelope,
                    issuedAtEpochMillis,
                    expiresAtEpochMillis,
                    AiReviewOnlyContract.PROVIDER_ID,
                    requestTemplate);
            if (!AiReviewOnlyContract.isCanonicalDispatch(dispatch)) {
                throw new IllegalArgumentException("review dispatch is not canonical");
            }
            AiRequestDispatchReceipt receipt = AiRequestDispatchReceipt.fromEnvelope(envelope);
            AiReviewOnlyPhysicalAttemptOwner physicalAttemptOwner =
                    aiReviewOnlyPhysicalAttemptOwners.computeIfAbsent(
                            ownerId, AiReviewOnlyPhysicalAttemptOwner::new);
            AiPhysicalAttemptOffer offer = physicalAttemptOwner.offer(dispatch)
                    .offer()
                    .orElseThrow(() -> new IllegalStateException(
                            "review physical attempt offer was rejected"));
            AiPhysicalAttemptIdentity identity = offer.identity();
            AiPhysicalAttemptIdentity priorIdentity = aiReviewOnlyPhysicalAttemptsByBot
                    .putIfAbsent(botId, identity);
            if (priorIdentity != null) {
                physicalAttemptOwner.closeExact(identity);
                throw new IllegalStateException(
                        "review bot already retains a physical attempt identity");
            }
            aiReviewOnlyTickets.open(new AiReviewOnlyTicket(
                    receipt, projection, identity)).ifPresent(this::closePhysicalAttemptForTicket);
            PacketDistributor.sendToPlayer(owner,
                    new AiPhysicalAttemptOfferPayload(dispatch, offer));
            return receipt;
        } catch (RuntimeException exception) {
            // A packet that was not safely packaged/handed off retains neither gate nor ticket.
            closeAiProposalRequestForBot(botId);
            throw exception;
        }
    }

    /**
     * Authenticates one exact client preparation ACK before settling any server-owned attempt
     * budget and sending the corresponding start grant.
     *
     * <p>Malformed, stale, cross-owner, cross-generation, or already-closed acknowledgements are
     * intentionally ignored. Only a fully correlated current ticket may terminally close itself
     * on a failed settlement or a failed grant handoff; a forged old receipt must never clear a
     * newer bot request.
     */
    public void acknowledgeAiPhysicalAttempt(
            ServerPlayer sender, AiPhysicalAttemptPrepareAckPayload payload) {
        requireServerThread();
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(payload, "payload");
        AiPhysicalAttemptIdentity identity = payload.prepareAck().identity();
        AiRequestDispatchReceipt receipt = identity.dispatchReceipt();
        if (stopping
                || sender instanceof BotServerPlayer
                || !roster.serverInstanceId().equals(identity.serverInstanceId())
                || !sender.getUUID().equals(identity.ownerId())
                || receipt.purpose() != AiReviewOnlyContract.PURPOSE) {
            return;
        }

        RuntimeEntry runtime = runtimes.get(receipt.botId());
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE
                || runtime.handle.generation() != receipt.generation()
                || !roster.findById(receipt.botId())
                        .flatMap(BotProfile::ownerId)
                        .filter(sender.getUUID()::equals)
                        .isPresent()
                || !receipt.agentId().equals(activeAgentByBot.get(receipt.botId()))
                || !receipt.botId().equals(botByActiveAgent.get(receipt.agentId()))) {
            return;
        }

        AiProposalRequestEnvelope envelope = aiProposalSessionGate.findExact(receipt)
                .orElse(null);
        AiReviewOnlyTicket ticket = aiReviewOnlyTickets.findExact(receipt).orElse(null);
        AiReviewOnlyPhysicalAttemptOwner physicalAttemptOwner =
                aiReviewOnlyPhysicalAttemptOwners.get(identity.ownerId());
        if (envelope == null
                || ticket == null
                || physicalAttemptOwner == null
                || !identity.ownerId().equals(envelope.ownerId())
                || !identity.nonce().equals(envelope.nonce())
                || envelope.purpose() != AiReviewOnlyContract.PURPOSE
                || !ticket.physicalAttemptIdentity().filter(identity::equals).isPresent()
                || !identity.equals(aiReviewOnlyPhysicalAttemptsByBot.get(receipt.botId()))
                || !physicalAttemptOwner.findIdentity(receipt).filter(identity::equals)
                        .isPresent()) {
            return;
        }
        if (server.getTickCount() >= receipt.expiresAtTick()) {
            closeAiProposalRequestExact(receipt);
            return;
        }

        AiPhysicalAttemptPrepareResult prepared = physicalAttemptOwner.acknowledge(
                payload.prepareAck());
        if (!prepared.granted()
                || !identity.equals(prepared.grant().orElseThrow().identity())) {
            closeAiProposalRequestExact(receipt);
            return;
        }
        try {
            PacketDistributor.sendToPlayer(sender,
                    new AiPhysicalAttemptStartGrantPayload(prepared.grant().orElseThrow()));
        } catch (RuntimeException exception) {
            /* A settled grant may not be refunded; exact closure only tombstones it. */
            closeAiProposalRequestExact(receipt);
        }
    }

    /**
     * Reviews one untrusted C2S proposal and drops it after producing at most a secret-free R1
     * summary. This method has no SkillRuntime, action, or world-execution bridge.
     */
    public AiReviewOnlyReviewReceipt reviewAiProposal(
            ServerPlayer sender, AiProposalPayload payload) {
        requireServerThread();
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(payload, "payload");
        RuntimeEntry runtime = runtimes.get(payload.botId());
        boolean botActive = !stopping
                && runtime != null
                && runtime.state == BotLifecycleState.ACTIVE;
        Optional<UUID> persistentOwnerId = botActive
                ? roster.findById(payload.botId()).flatMap(BotProfile::ownerId)
                : Optional.empty();
        boolean senderIsPersistentOwner = persistentOwnerId
                .filter(sender.getUUID()::equals)
                .isPresent()
                && !(sender instanceof BotServerPlayer);
        Optional<UUID> activeAgentId = botActive
                ? Optional.ofNullable(activeAgentByBot.get(payload.botId()))
                : Optional.empty();
        OptionalLong activeGeneration = botActive
                && runtime.handle.generation() > 0L
                ? OptionalLong.of(runtime.handle.generation())
                : OptionalLong.empty();
        long currentTick = server.getTickCount();
        if (isCurrentReviewOnlyProposalAwaitingPhysicalGrant(
                payload,
                botActive,
                senderIsPersistentOwner,
                persistentOwnerId,
                activeAgentId,
                activeGeneration,
                currentTick)) {
            /* Keep the exact ticket live: the same owner may still ACK and receive its grant. */
            return AiReviewOnlyReviewReceipt.rejected(
                    AiReviewOnlyReviewStatus.GATE_REJECTED,
                    AiProposalReviewStatus.PHYSICAL_ATTEMPT_NOT_GRANTED);
        }
        AiProposalReviewReceipt gateReceipt = aiProposalSessionGate.reviewWithReceipt(
                payload,
                new AiProposalAuthority(
                        botActive,
                        senderIsPersistentOwner,
                        persistentOwnerId,
                        activeAgentId,
                        activeGeneration),
                currentTick);

        Optional<AiReviewOnlyTicket> ticket = gateReceipt.terminalDispatch()
                .flatMap(aiReviewOnlyTickets::close);
        ticket.ifPresent(this::closePhysicalAttemptForTicket);
        gateReceipt.terminalDispatch().ifPresent(this::closePhysicalAttemptForReceipt);
        if (gateReceipt.terminalDispatch().isEmpty()) {
            return AiReviewOnlyReviewReceipt.rejected(
                    AiReviewOnlyReviewStatus.GATE_REJECTED,
                    gateReceipt.review().status());
        }
        if (ticket.isEmpty()) {
            return AiReviewOnlyReviewReceipt.rejected(
                    AiReviewOnlyReviewStatus.UNTRACKED_TERMINAL_DROPPED,
                    gateReceipt.review().status());
        }
        if (!gateReceipt.review().acceptedNoExecution()) {
            return AiReviewOnlyReviewReceipt.rejected(
                    AiReviewOnlyReviewStatus.GATE_REJECTED,
                    gateReceipt.review().status());
        }
        if (!AiReviewOnlyContract.isCanonicalProposalPayload(payload)
                || gateReceipt.review().proposal().isEmpty()
                || !AiReviewOnlyContract.isCanonicalProposal(
                        gateReceipt.review().proposal().orElseThrow())) {
            return AiReviewOnlyReviewReceipt.rejected(
                    AiReviewOnlyReviewStatus.REVIEW_CONTRACT_REJECTED_DROPPED,
                    gateReceipt.review().status());
        }

        AiReviewOnlySnapshotProjection projection = ticket.orElseThrow().projection();
        return AiReviewOnlyReviewReceipt.accepted(
                gateReceipt.review().status(),
                new AiReviewOnlyProposalSummary(
                        projection.snapshotId(),
                        projection.gameTick(),
                        projection.threatCount(),
                        1));
    }

    /**
     * Returns true only for a live, fully correlated R1 proposal whose owner-local physical
     * attempt has not reached the server-side settled grant state.
     *
     * <p>The preliminary checks deliberately mirror the non-terminal gate checks. A malformed,
     * stale, cross-owner, or expired proposal still reaches {@link AiProposalSessionGate} so its
     * existing exact terminal/diagnostic semantics remain intact. This method never settles,
     * closes, or broadens a request: an owner that sent a response too early can still send the
     * exact ACK and then a post-grant response before the normal TTL.
     */
    private boolean isCurrentReviewOnlyProposalAwaitingPhysicalGrant(
            AiProposalPayload payload,
            boolean botActive,
            boolean senderIsPersistentOwner,
            Optional<UUID> persistentOwnerId,
            Optional<UUID> activeAgentId,
            OptionalLong activeGeneration,
            long currentTick) {
        if (!botActive
                || !senderIsPersistentOwner
                || persistentOwnerId.isEmpty()
                || activeAgentId.isEmpty()
                || !activeAgentId.orElseThrow().equals(payload.agentId())
                || activeGeneration.isEmpty()
                || activeGeneration.getAsLong() != payload.generation()) {
            return false;
        }
        AiProposalRequestEnvelope envelope = aiProposalSessionGate.findExactForProposal(payload)
                .orElse(null);
        if (envelope == null
                || envelope.purpose() != AiReviewOnlyContract.PURPOSE
                || currentTick >= envelope.expiresAtTick()
                || !persistentOwnerId.orElseThrow().equals(envelope.ownerId())) {
            return false;
        }
        AiRequestDispatchReceipt receipt = AiRequestDispatchReceipt.fromEnvelope(envelope);
        AiReviewOnlyTicket ticket = aiReviewOnlyTickets.findExact(receipt).orElse(null);
        AiPhysicalAttemptIdentity identity = ticket == null
                ? null
                : ticket.physicalAttemptIdentity().orElse(null);
        if (identity == null
                || !roster.serverInstanceId().equals(identity.serverInstanceId())
                || !envelope.ownerId().equals(identity.ownerId())
                || !envelope.nonce().equals(identity.nonce())
                || !identity.equals(aiReviewOnlyPhysicalAttemptsByBot.get(receipt.botId()))) {
            return true;
        }
        AiReviewOnlyPhysicalAttemptOwner physicalAttemptOwner =
                aiReviewOnlyPhysicalAttemptOwners.get(identity.ownerId());
        return physicalAttemptOwner == null
                || physicalAttemptOwner.findGrantedExact(identity).isEmpty();
    }

    /**
     * Clears transient bindings sponsored by a real player when that player disconnects.
     */
    public void onRealPlayerLogout(ServerPlayer player) {
        requireServerThread();
        if (player instanceof BotServerPlayer) {
            return;
        }

        closeViewerInventory(
                player.getUUID(),
                InventoryCloseReason.VIEWER_DISCONNECTED);
        UUID ownerId = player.getUUID();
        List<UUID> ownedBotIds = activeAgentByBot.keySet().stream()
                .filter(botId -> {
                    RuntimeEntry runtime = runtimes.get(botId);
                    return runtime != null
                            && roster.findById(botId)
                                    .flatMap(BotProfile::ownerId)
                                    .filter(ownerId::equals)
                                    .isPresent();
                })
                .toList();
        ownedBotIds.forEach(this::clearAgentBinding);
        /* Covers any defensive gate/binding divergence left outside activeAgentByBot. */
        closePhysicalAttemptsForOwner(ownerId);
    }

    public void onRealPlayerChangedDimension(ServerPlayer player) {
        requireServerThread();
        if (!(player instanceof BotServerPlayer)) {
            closeViewerInventory(
                    player.getUUID(),
                    InventoryCloseReason.DIMENSION_CHANGED);
        }
    }

    public void onDeath(BotServerPlayer player) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null) {
            player.releaseCompletedDeathAttemptSaveFence();
            return;
        }
        long generation = runtime.handle.generation();
        BotServerPlayer exactTarget =
                resolveCleanupTarget(
                                player.getUUID(), generation)
                        .orElse(null);
        if (exactTarget != player) {
            BotServerPlayer activeSuccessor =
                    runtime.handle.player().orElse(null);
            if (runtime.state == BotLifecycleState.ACTIVE
                    && activeSuccessor != null
                    && activeSuccessor != player
                    && player.runtimeHandle() == runtime.handle
                    && isAuthoritativeInstance(
                            runtime, activeSuccessor, true)) {
                /*
                 * 迟到的 predecessor 回调只能永久毒化旧对象，不能拆除已经
                 * ACTIVE 的新 generation。该旧引用可能仍被模组排队保存。
                 */
                player.suppressPlayerDataSaveUntilReleased();
                player.releaseCompletedDeathAttemptSaveFence();
                return;
            }
            if (!player.adoptCompletedDeathSaveFence()) {
                player.armDeathRetirementSaveFence();
            }
            BotServerPlayer successor =
                    knownDeathSuccessor(runtime, player);
            failDeathRetirementWithoutSave(
                    runtime,
                    player,
                    successor,
                    new IllegalStateException(
                            "Death was observed on a non-authoritative BotPlayer body"));
            return;
        }
        /* A death body can never become the authoritative ACTIVE body again. */
        closeAiProposalRequestForBot(runtime.handle.botId());
        revokePendingSkillCheckpointRecovery(
                runtime, "authoritative_death");
        if (runtime.state == BotLifecycleState.DEAD) {
            PendingDeathRetirement pending =
                    runtime.deathRetirement;
            if (pending != null
                    && !isExactDeathRetirementAuthority(
                            runtime, pending)) {
                if (!player.adoptCompletedDeathSaveFence()) {
                    player.armDeathRetirementSaveFence();
                }
                failDeathRetirementWithoutSave(
                        runtime,
                        player,
                        knownDeathSuccessor(runtime, player),
                        new IllegalStateException(
                                "Repeated death found a non-authoritative pending retirement"));
                return;
            }
            player.releaseCompletedDeathAttemptSaveFence();
            return;
        }
        if (runtime.state == BotLifecycleState.RESPAWNING) {
            if (hasCompleteDeathRetirement(runtime)
                    && runtime.handle.player().orElse(null)
                            == player) {
                player.releaseCompletedDeathAttemptSaveFence();
                return;
            }
            if (!player.adoptCompletedDeathSaveFence()) {
                player.armDeathRetirementSaveFence();
            }
            failDeathRetirementWithoutSave(
                    runtime,
                    player,
                    knownDeathSuccessor(runtime, player),
                    new IllegalStateException(
                            "Death interrupted respawn without a complete retirement owner"));
            return;
        }
        if (runtime.state == BotLifecycleState.DESPAWNING) {
            boolean existingOwner =
                    runtime.pendingNoSaveTeardown != null
                            || runtime.directDisconnectRetirement
                                    != null
                            || runtime.disconnectingPlayer != null
                            || runtime.generationRetirementInProgress
                            || runtime.deathRetirementFailClosedInProgress;
            if (existingOwner) {
                player.releaseCompletedDeathAttemptSaveFence();
                return;
            }
            if (!player.adoptCompletedDeathSaveFence()) {
                player.armDeathRetirementSaveFence();
            }
            failDeathRetirementWithoutSave(
                    runtime,
                    player,
                    knownDeathSuccessor(runtime, player),
                    new IllegalStateException(
                            "Death entered DESPAWNING without a teardown owner"));
            return;
        }
        if (!player.adoptCompletedDeathSaveFence()) {
            /* Loaded-dead/manual observations have no enclosing die invocation. */
            player.armDeathRetirementSaveFence();
        }
        VanillaDeathTicket vanillaConsumedTicket =
                player.completedVanillaDeathTicket()
                        .or(player::deathHandoffTicket)
                        .orElse(null);

        if (runtime.generationRetirementInProgress
                || runtime.replacementHandoffInProgress
                || runtime.stagedCleanupGeneration >= 0L
                || runtime.stagedCleanupPredecessor != null
                || runtime.stagedCleanupPlayer != null) {
            player.armDeathRetirementSaveFence();
            failDeathRetirementWithoutSave(
                    runtime,
                    player,
                    runtime.stagedCleanupPlayer,
                    new IllegalStateException(
                            "Death overlapped another generation retirement"));
            return;
        }
        transition(runtime, BotLifecycleState.DEAD);
        runtime.respawnCandidate = null;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnAttempts = 0;
        runtime.respawnSuppressed = true;
        runtime.respawnAtTick = -1;
        runtime.completedDeathRetirement = null;

        if (!(player.connection
                        instanceof BotGamePacketListener listener)
                || listener.player != player
                || !isBotConnectionOpen(player)) {
            player.armDeathRetirementSaveFence();
            RuntimeException failure =
                    new IllegalStateException(
                            "Death retirement could not bind the authoritative listener");
            failDeathRetirementWithoutSave(
                    runtime, player, null, failure);
            return;
        }

        Connection connection = listener.getConnection();
        long startedTick = server.getTickCount();
        GenerationRetirementKey key =
                new GenerationRetirementKey(
                        UUID.randomUUID(),
                        runtime.handle.botId(),
                        generation,
                        GenerationRetirementContinuation
                                .DEATH_RESPAWN);
        PendingDeathRetirement pending =
                new PendingDeathRetirement(
                        player,
                        listener,
                        connection,
                        generation,
                        GenerationRetirementSession.open(
                                key,
                                startedTick,
                                boundedRetirementDeadline(
                                        startedTick)),
                        vanillaConsumedTicket);
        runtime.deathRetirement = pending;
        player.armDeathRetirementSaveFence();
        if (!isExactDeathRetirementAuthority(
                runtime, pending)) {
            pending.failure = appendFailure(
                    pending.failure,
                    new IllegalStateException(
                            "Death retirement could not freeze its authoritative body"));
            return;
        }
        advanceDeathRetirementAttempt(
                runtime, pending, startedTick);
    }

    /**
     * 原版死亡链在任意位置抛出后执行 fail-closed 隔离。此时掉落可能尚未开始，
     * 也可能已经写入 durable ticket 并部分消费；两种情况都不允许 runtime 继续
     * ACTIVE。成功 respawn 后迟到的 predecessor 异常只毒化旧对象。
     */
    public void onDeathInvocationFailed(
            BotServerPlayer player,
            boolean authoritativeLootStarted,
            Throwable cause) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(cause, "cause");
        player.suppressPlayerDataSaveUntilReleased();
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null) {
            return;
        }
        BotServerPlayer activeSuccessor =
                runtime.handle.player().orElse(null);
        if (!authoritativeLootStarted
                && runtime.state == BotLifecycleState.ACTIVE
                && activeSuccessor != null
                && activeSuccessor != player
                && player.runtimeHandle() == runtime.handle
                && isAuthoritativeInstance(
                        runtime, activeSuccessor, true)) {
            return;
        }
        failDeathRetirementWithoutSave(
                runtime,
                player,
                knownDeathSuccessor(runtime, player),
                new IllegalStateException(
                        "BotPlayer death invocation threw before a safe lifecycle disposition",
                        cause));
    }

    @Nullable
    private BotServerPlayer knownDeathSuccessor(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer predecessor) {
        UUID botId = runtime.handle.botId();
        ServerPlayer listed =
                server.getPlayerList().getPlayer(botId);
        if (listed instanceof BotServerPlayer successor
                && successor != predecessor) {
            return successor;
        }
        if (predecessor != null
                && predecessor.connection != null
                && predecessor.connection.player
                        instanceof BotServerPlayer successor
                && successor != predecessor
                && successor.getUUID().equals(botId)) {
            return successor;
        }
        BotServerPlayer[] retained = {
            runtime.respawnCandidate,
            runtime.stagedCleanupPlayer,
            runtime.stagedCleanupPredecessor,
            runtime.handle.player().orElse(null)
        };
        for (BotServerPlayer candidate : retained) {
            if (candidate != null
                    && candidate != predecessor
                    && candidate.getUUID().equals(botId)) {
                return candidate;
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            Player levelPlayer =
                    level.getPlayerByUUID(botId);
            if (levelPlayer instanceof BotServerPlayer successor
                    && successor != predecessor) {
                return successor;
            }
            Entity entity = level.getEntity(botId);
            if (entity instanceof BotServerPlayer successor
                    && successor != predecessor) {
                return successor;
            }
        }
        return null;
    }

    /**
     * Records NeoForge's respawn event without granting authority early.
     *
     * <p>PlayerList fires this event before ServerGamePacketListenerImpl updates its {@code player}
     * field. Final attachment therefore happens only after the initiating packet handler returns.
     */
    public void onRespawnCandidate(BotServerPlayer player) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null
                || runtime.state != BotLifecycleState.RESPAWNING
                || hasRequestedListenerDisconnect(
                        runtime)
                || handlesByBot.get(player.getUUID()) != runtime.handle
                || player.runtimeHandle() != runtime.handle
                || runtime.handle.player().orElse(null) == player
                || server.getPlayerList().getPlayer(player.getUUID()) != player
                || player.serverLevel().getPlayerByUUID(player.getUUID())
                        != player) {
            return;
        }
        runtime.respawnCandidate = player;
    }

    /**
     * Handles a ServerPlayer replacement that completed outside the delayed death-respawn loop.
     */
    public void onConnectionPlayerReplaced(
            BotServerPlayer oldPlayer, BotServerPlayer replacement) {
        requireServerThread();
        Objects.requireNonNull(oldPlayer, "oldPlayer");
        Objects.requireNonNull(replacement, "replacement");
        RuntimeEntry runtime = runtimes.get(oldPlayer.getUUID());
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE
                || runtime.handle.player().orElse(null) != oldPlayer
                || runtime.replacementHandoffInProgress
                || runtime.handoffAborted
                || runtime.stagedCleanupPlayer != null
                || runtime.stagedCleanupGeneration >= 0L
                || runtime.disconnectingPlayer != null
                || !oldPlayer.getUUID()
                        .equals(replacement.getUUID())
                || oldPlayer.connection == null
                || replacement.runtimeHandle() != runtime.handle
                || replacement.connection == null
                || replacement.connection
                        != oldPlayer.connection
                || replacement.connection.player != replacement
                || !isListenerAuthority(replacement)
                || !isBotConnectionOpen(replacement)
                || server.getPlayerList().getPlayer(replacement.getUUID())
                        != replacement
                || replacement.serverLevel()
                                .getPlayerByUUID(replacement.getUUID())
                        != replacement
                || isPresentInAnyLevel(oldPlayer)) {
            return;
        }

        long oldGeneration = runtime.handle.generation();
        runtime.handoffAborted = false;
        runtime.replacementHandoffInProgress =
                true;
        runtime.stagedCleanupGeneration =
                oldGeneration;
        runtime.stagedCleanupPredecessor =
                oldPlayer;
        runtime.stagedCleanupPlayer =
                replacement;
        GenerationRetirement retirement =
                retireGenerationBestEffort(
                        runtime,
                        oldGeneration,
                        InventoryCloseReason.BOT_RESPAWN);
        boolean mayCommit =
                canCommitReplacementHandoff(
                        runtime,
                        oldPlayer,
                        replacement,
                        oldGeneration);
        if (!retirement.safelyClosed()
                || !mayCommit) {
            GenerationRetirement abortReceipt =
                    retirement;
            if (retirement.safelyClosed()
                    && !mayCommit
                    && !hasRequestedListenerDisconnect(
                            runtime)) {
                abortReceipt =
                        new GenerationRetirement(
                                false,
                                new IllegalStateException(
                                        "Replacement authority changed during generation closure"));
            }
            abortStagedReplacementHandoff(
                    runtime,
                    replacement,
                    oldGeneration,
                    abortReceipt,
                    retirement.safelyClosed()
                            ? "Bot replacement authority changed during generation closure"
                            : "Bot replacement inherited an unsafe generation");
            return;
        }

        runtime.stagedCleanupGeneration = -1L;
        runtime.stagedCleanupPredecessor = null;
        runtime.stagedCleanupPlayer = null;
        try {
            runtime.handle.attach(replacement);
            MinecraftPlayerInputAdapter.clear(
                    replacement);
            if (!canActivateReplacement(
                    runtime, replacement)) {
                throw new IllegalStateException(
                        "Replacement authority changed before activation");
            }
            perceptionService.activate(
                    runtime.handle.botId(),
                    runtime.handle.generation());
            if (!canActivateReplacement(
                    runtime, replacement)) {
                throw new IllegalStateException(
                        "Replacement authority changed during activation");
            }
            runtime.replacementHandoffInProgress =
                    false;
        } catch (RuntimeException exception) {
            abortReplacementHandoff(
                    runtime,
                    replacement,
                    exception,
                    "Bot replacement activation failed");
        }
    }

    /**
     * Closes generation-owned physical state while vanilla still owns the
     * exact player body.
     *
     * <p>{@link
     * net.minecraft.server.network.ServerGamePacketListenerImpl#onDisconnect}
     * saves and removes the player. The packet listener must therefore call
     * this hook first; post-disconnect cleanup alone can restore the detached
     * Java object while leaving a temporary skill inventory layout persisted
     * on disk.
     *
     * @return whether vanilla may proceed, must retry after the owning stack,
     *     or must stop because an inconsistent body was failed closed
     */
    public ListenerDisconnectDecision onDisconnecting(
            BotServerPlayer player,
            ServerGamePacketListenerImpl listener,
            Connection connection) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(connection, "connection");
        RuntimeEntry runtime =
                runtimes.get(player.getUUID());
        if (player.connection != listener
                || listener.player != player) {
            if (runtime == null
                    || player.runtimeHandle()
                            != runtime.handle) {
                return ListenerDisconnectDecision
                        .PROCEED;
            }
            if (runtime
                    .generationRetirementInProgress) {
                return ListenerDisconnectDecision
                        .RETRY;
            }
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    server.getPlayerList()
                            .getPlayer(
                                    player.getUUID()),
                    "Disconnect listener and BotPlayer body no longer shared the same connection");
            return ListenerDisconnectDecision.ABORTED;
        }
        if (runtime == null) {
            return ListenerDisconnectDecision.PROCEED;
        }

        PendingDeathRetirement deathRetirement =
                runtime.deathRetirement;
        if (deathRetirement != null) {
            if (!deathRetirement.matchesBinding(
                            player,
                            listener,
                            connection,
                            runtime.handle.generation())
                    || !isExactDeathRetirementAuthority(
                            runtime, deathRetirement)) {
                deathRetirement.failure = appendFailure(
                        deathRetirement.failure,
                        new IllegalStateException(
                                "Disconnect changed authority during death retirement"));
                failDeathRetirementWithoutSave(
                        runtime,
                        deathRetirement.player,
                        player,
                        deathRetirement.failure);
                return ListenerDisconnectDecision.ABORTED;
            }
            if (deathRetirement.status()
                    == GenerationRetirementStatus.UNSAFE) {
                failDeathRetirementWithoutSave(
                        runtime,
                        deathRetirement.player,
                        null,
                        deathRetirement.failure);
                return ListenerDisconnectDecision.ABORTED;
            }
            return ListenerDisconnectDecision.RETRY;
        }

        PendingDirectDisconnectRetirement directRetirement =
                runtime.directDisconnectRetirement;
        if (directRetirement != null
                && !directRetirement.matchesBinding(
                        player,
                        listener,
                        connection,
                        runtime.handle.generation())) {
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    server.getPlayerList()
                            .getPlayer(player.getUUID()),
                    "Direct disconnect retirement lost its exact body binding");
            return ListenerDisconnectDecision.ABORTED;
        }

        BotServerPlayer current =
                runtime.handle.player().orElse(null);
        boolean stagedReplacement =
                isPendingReplacementDisconnect(
                        runtime, player);
        boolean unstagedReplacement =
                isUnstagedReplacementDisconnect(
                        runtime, player);
        if (current != player
                && !stagedReplacement
                && !unstagedReplacement) {
            return ListenerDisconnectDecision.PROCEED;
        }

        /*
         * PlayerRespawnEvent is fired after PlayerList has installed the
         * replacement but before this listener changes its player field.
         * A synchronous event subscriber may request disconnect in that
         * window. Never let vanilla save/remove the predecessor: the listener
         * will retry from a queued TickTask after PlayerList#respawn unwinds.
         */
        ServerPlayer listedPlayer =
                server.getPlayerList()
                        .getPlayer(player.getUUID());
        Player levelPlayer =
                player.serverLevel()
                        .getPlayerByUUID(
                                player.getUUID());
        if (listedPlayer != player
                || levelPlayer != player) {
            runtime.handoffAborted = true;
            runtime.respawnSuppressed = true;
            if (isRespawnDisconnectTransition(
                            runtime,
                            player,
                            listener,
                            listedPlayer)
                    || runtime
                            .generationRetirementInProgress) {
                return ListenerDisconnectDecision.RETRY;
            }
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    listedPlayer,
                    "Disconnect body diverged from PlayerList or level identity");
            return ListenerDisconnectDecision.ABORTED;
        }

        long generation =
                stagedReplacement
                        ? runtime.stagedCleanupGeneration
                        : runtime.handle.generation();
        if (isPreparedListenerDisconnect(
                runtime,
                player,
                listener,
                connection,
                generation)) {
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
            if (!runtime
                            .disconnectPreparationSafelyClosed
                    || runtime
                                    .disconnectPreparationFailure
                            != null) {
                abortUnstableListenerDisconnect(
                        runtime,
                        player,
                        listedPlayer,
                        "Prepared disconnect did not hold a safe pre-save generation receipt");
                return ListenerDisconnectDecision.ABORTED;
            }
            return ListenerDisconnectDecision.PROCEED;
        }

        if (!bindListenerDisconnect(
                runtime,
                player,
                listener,
                        connection,
                        generation)) {
            runtime.handoffAborted = true;
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
            if (runtime
                    .generationRetirementInProgress) {
                return ListenerDisconnectDecision.RETRY;
            }
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    listedPlayer,
                    "Disconnect generation ticket conflicted with an existing listener binding");
            return ListenerDisconnectDecision.ABORTED;
        }
        runtime.handoffAborted |=
                runtime.replacementHandoffInProgress
                        || stagedReplacement
                        || unstagedReplacement;
        transition(
                runtime,
                BotLifecycleState.DESPAWNING);
        boolean directCurrentBody =
                current == player
                        && !stagedReplacement
                        && !unstagedReplacement;
        if (runtime.directDisconnectRetirement != null
                || directCurrentBody) {
            return advanceDirectListenerDisconnect(
                    runtime,
                    player,
                    listener,
                    connection,
                    generation);
        }
        if (runtime.generationRetirementInProgress) {
            return ListenerDisconnectDecision.RETRY;
        }
        RuntimeException failure = null;
        boolean safelyClosed = false;
        try {
            if (unstagedReplacement
                    && !stageDisconnectCleanupTarget(
                            runtime,
                            player,
                            generation)) {
                failure = appendFailure(
                        failure,
                        new IllegalStateException(
                                "Disconnecting replacement lost exact cleanup authority"));
            }
            GenerationRetirement retirement =
                    retireGenerationBestEffort(
                            runtime,
                            generation,
                            InventoryCloseReason
                                    .BOT_UNLOADED);
            safelyClosed =
                    retirement.safelyClosed();
            failure = appendFailure(
                    failure,
                    retirement.failure());
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }

        recordDisconnectPreparation(
                runtime,
                generation,
                new GenerationRetirement(
                        safelyClosed && failure == null,
                        failure));
        if (!safelyClosed || failure != null) {
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    listedPlayer,
                    "Disconnect retirement did not produce a safe pre-save generation receipt");
            return ListenerDisconnectDecision.ABORTED;
        }
        return ListenerDisconnectDecision.PROCEED;
    }

    /**
     * Converts a deferred disconnect that did not converge after its one
     * queued retry into a no-save fail-closed removal. A still-running
     * retirement keeps ownership and asks the listener to retry once more.
     */
    public boolean onDisconnectRetryExhausted(
            BotServerPlayer player,
            ServerGamePacketListenerImpl listener,
            Connection connection) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(connection, "connection");
        RuntimeEntry runtime =
                runtimes.get(player.getUUID());
        if (runtime == null
                || player.runtimeHandle()
                        != runtime.handle) {
            return true;
        }
        PendingDirectDisconnectRetirement directRetirement =
                runtime.directDisconnectRetirement;
        if (directRetirement != null
                && directRetirement.matchesBinding(
                        player,
                        listener,
                        connection,
                        runtime.handle.generation())
                && directRetirement.status()
                        == GenerationRetirementStatus.PENDING) {
            return false;
        }
        PendingDeathRetirement deathRetirement =
                runtime.deathRetirement;
        if (deathRetirement != null
                && deathRetirement.matchesBinding(
                        player,
                        listener,
                        connection,
                        runtime.handle.generation())
                && isExactDeathRetirementAuthority(
                        runtime, deathRetirement)
                && deathRetirement.status()
                        != GenerationRetirementStatus.UNSAFE) {
            return false;
        }
        if (runtime.generationRetirementInProgress) {
            return false;
        }
        abortUnstableListenerDisconnect(
                runtime,
                player,
                server.getPlayerList()
                        .getPlayer(player.getUUID()),
                "Deferred disconnect did not converge to one authoritative body");
        return true;
    }

    public void onDisconnected(
            BotServerPlayer player,
            ServerGamePacketListenerImpl listener,
            Connection connection) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(connection, "connection");
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null) {
            return;
        }
        BotServerPlayer current = runtime.handle.player().orElse(null);
        boolean stagedReplacement =
                isPendingReplacementDisconnect(
                        runtime, player);
        boolean unstagedReplacement =
                isUnstagedReplacementDisconnect(
                        runtime, player);
        if (current != player
                && !stagedReplacement
                && !unstagedReplacement) {
            return;
        }

        long generation =
                stagedReplacement
                        ? runtime.stagedCleanupGeneration
                        : runtime.handle.generation();
        boolean preparedListener =
                runtime.disconnectingPlayer == player
                        && runtime.disconnectingListener
                                == listener
                        && runtime.disconnectingConnection
                                == connection
                        && runtime.disconnectingGeneration
                                == generation;
        if (runtime.disconnectingPlayer != null
                && !preparedListener) {
            RuntimeException failure =
                    new IllegalStateException(
                            "Post-disconnect generation did not match the bound pre-save transaction");
            failure = appendFailure(
                    failure,
                    finalizeRuntimeTeardown(
                            runtime, player));
            BotPlayer.LOGGER.error(
                    "BotPlayer {} ({}) disconnected outside its exact generation ticket",
                    runtime.handle.name(),
                    player.getUUID(),
                    failure);
            return;
        }
        if (!preparedListener
                        && (player.connection != listener
                                || listener.player
                                        != player)) {
            return;
        }
        runtime.handoffAborted |=
                stagedReplacement
                        || unstagedReplacement;
        transition(
                runtime,
                BotLifecycleState.DESPAWNING);
        GenerationRetirement retirement;
        if (runtime.disconnectPreparationComplete
                && runtime.disconnectingGeneration
                        == generation) {
            retirement = new GenerationRetirement(
                    runtime
                            .disconnectPreparationSafelyClosed,
                    runtime.disconnectPreparationFailure);
        } else {
            retirement = new GenerationRetirement(
                    false,
                    new IllegalStateException(
                            "Missing exact pre-save disconnect preparation receipt for generation "
                                    + generation));
        }
        RuntimeException failure =
                retirement.failure();
        failure = appendFailure(
                failure,
                finalizeRuntimeTeardown(
                        runtime, player));
        if (failure != null) {
            BotPlayer.LOGGER.error(
                    "BotPlayer {} ({}) disconnected with cleanup failures",
                    runtime.handle.name(),
                    player.getUUID(),
                    failure);
        } else if (!retirement.safelyClosed()) {
            BotPlayer.LOGGER.error(
                    "BotPlayer {} ({}) disconnected without a safe pre-save generation receipt",
                    runtime.handle.name(),
                    player.getUUID());
        }
        BotPlayer.LOGGER.info(
                "Unloaded BotPlayer {} ({})",
                runtime.handle.name(),
                player.getUUID());
    }

    /**
     * Native item-use preflight reached immediately before vanilla advances a
     * consumable. This must remain much narrower than the normal lifecycle
     * tick: it only lets the action backend stop an already-active strict use,
     * so it cannot schedule, observe, or mutate any unrelated bot state.
     *
     * @return whether vanilla must skip the pending item-use update
     */
    public boolean beforeNativeItemUseUpdate(BotServerPlayer player) {
        Objects.requireNonNull(player, "player");
        requireServerThread();
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE
                || runtime.handle.generation()
                        != player.runtimeHandle().generation()
                || runtime.handle.player().orElse(null) != player
                || !isListenerAuthority(player)) {
            /*
             * A stale, retiring or detached Bot body must not advance a
             * consumable between lifecycle teardown and action cleanup. The
             * hook is reached only for a live native use, so suppressing it is
             * the fail-closed outcome even for a non-strict legacy use.
             */
            return true;
        }
        return minecraftActionBackend.beforeNativeItemUseUpdate(player);
    }

    /**
     * Final strict-use recheck immediately before vanilla invokes
     * {@code completeUsingItem()}.
     *
     * <p>Unlike the ordinary update entry, this point is the native physical
     * commit boundary. A stale or detached Bot body is therefore always
     * rejected, even when a nested listener has already stopped its live use
     * flag while the outer vanilla method still holds a stale stack argument.
     */
    public boolean beforeNativeItemUseCompletion(BotServerPlayer player) {
        Objects.requireNonNull(player, "player");
        requireServerThread();
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE
                || runtime.handle.generation()
                        != player.runtimeHandle().generation()
                || runtime.handle.player().orElse(null) != player
                || !isListenerAuthority(player)) {
            return true;
        }
        return minecraftActionBackend.beforeNativeItemUseCompletion(player);
    }

    public void tick() {
        requireServerThread();
        if (stopping) {
            return;
        }

        long tickStartedNanos = serverTickStartedNanos;
        serverTickStartedNanos = -1L;
        int currentTick = server.getTickCount();
        aiProposalSessionGate.closeExpiredThrough(currentTick)
                .forEach(envelope -> {
                    AiRequestDispatchReceipt receipt = AiRequestDispatchReceipt.fromEnvelope(
                            envelope);
                    aiReviewOnlyTickets.close(receipt)
                            .ifPresent(this::closePhysicalAttemptForTicket);
                    closePhysicalAttemptForReceipt(receipt);
                    sendAiRequestCancellation(envelope);
                });
        /* Defensive orphan cleanup: a ticket never outlives its dispatch TTL. */
        aiReviewOnlyTickets.closeExpiredThrough(currentTick).forEach(ticket -> {
            closePhysicalAttemptForTicket(ticket);
            closePhysicalAttemptForReceipt(ticket.dispatch());
            aiProposalSessionGate.closeExact(ticket.dispatch())
                    .ifPresent(this::sendAiRequestCancellation);
        });
        expireAiPhysicalAttempts();
        taskSensorService.beginTick(currentTick);
        for (RuntimeEntry runtime : List.copyOf(runtimes.values())) {
            if (hasRequestedListenerDisconnect(
                    runtime)) {
                continue;
            }
            if (runtime.state == BotLifecycleState.RESPAWNING) {
                if (finalizeRespawnIfAuthoritative(
                        runtime, currentTick)) {
                    continue;
                }
                if (runtime.respawnFinalizeDeadlineTick >= 0
                        && currentTick
                                >= runtime.respawnFinalizeDeadlineTick) {
                    failRespawnAttempt(
                            runtime,
                            currentTick,
                            "listener authority did not converge");
                }
                continue;
            }

            BotServerPlayer attachedPlayer =
                    runtime.handle.player().orElse(null);
            if (attachedPlayer != null
                    && isListenerAuthority(attachedPlayer)) {
                attachedPlayer.tickClientlessConnectionPhase();
            }
            if (runtime.state == BotLifecycleState.DEAD
                    && runtime.respawnAtTick < 0
                    && !runtime.respawnSuppressed
                    && hasCompleteDeathRetirement(runtime)
                    && BotPlayerConfig.AUTO_RESPAWN.get()) {
                runtime.respawnAtTick =
                        currentTick + BotPlayerConfig.RESPAWN_DELAY_TICKS.get();
            }
            if (runtime.state != BotLifecycleState.DEAD
                    || runtime.respawnAtTick < 0
                    || currentTick < runtime.respawnAtTick
                    || !hasCompleteDeathRetirement(runtime)) {
                continue;
            }

            BotServerPlayer player = runtime.handle.player().orElse(null);
            if (player == null || !player.isDeadOrDying() || player.connection == null) {
                continue;
            }

            beginRespawnAttempt(runtime, currentTick);
            try {
                player.connection.handleClientCommand(
                        new ServerboundClientCommandPacket(
                                ServerboundClientCommandPacket.Action
                                        .PERFORM_RESPAWN));
            } catch (RuntimeException exception) {
                failRespawnAttempt(
                        runtime,
                        currentTick,
                        "respawn handler threw "
                                + exception.getClass().getSimpleName());
                continue;
            }
            if (!finalizeRespawnIfAuthoritative(
                            runtime, currentTick)
                    && runtime.respawnFinalizeDeadlineTick
                            == currentTick) {
                failRespawnAttempt(
                        runtime,
                        currentTick,
                        "respawn handler returned without listener authority");
            }
        }
        revalidateInventorySessions();
        for (RuntimeEntry runtime :
                List.copyOf(runtimes.values())) {
            if (runtime.state != BotLifecycleState.ACTIVE) {
                continue;
            }
            BotServerPlayer player =
                    runtime.handle.player().orElse(null);
            if (player != null
                    && isListenerAuthority(player)) {
                safetyService.tickBot(
                        player,
                        runtime.handle.generation(),
                        currentTick);
            }
        }
        /*
         * P5C ordering is deliberate: a successful melee child can complete
         * its technique before the self-defense FSM consumes its Action
         * receipt and considers another bounded decision. Action outcomes are
         * then drained back into the exact child ticket before finishTick
         * seals this server tick against late callbacks.
         */
        techniqueLifecycleCoordinator.tick(currentTick);
        selfDefenseSkillService.tick(currentTick);
        resumeSafetyPausedSkillRuns(currentTick);
        advanceSkillCheckpointRecoveries(currentTick);
        skillRuntime.tick(currentTick);
        persistSafeSkillCheckpoints(currentTick);
        survivalSkillService.tick(currentTick);
        navigationService.tick(currentTick);
        actionRuntime.tick(currentTick);
        techniqueLifecycleCoordinator.drainCompletedChildren(currentTick);
        techniqueLifecycleCoordinator.finishTick(currentTick);
        advanceDirectDisconnectRetirements(currentTick);
        advanceDeathRetirements(currentTick);
        perceptionService.tick(currentTick);
        if (tickStartedNanos >= 0L) {
            perceptionService.recordTickDurationNanos(
                    Math.max(
                            0L,
                            System.nanoTime() - tickStartedNanos));
        }
    }

    /**
     * 只在没有未确认动作/菜单的中央安全状态写 checkpoint。写入失败时立即取消该 run，
     * 不让“看似可恢复、实际没有耐久证据”的计划继续改变世界。
     */
    private void advanceSkillCheckpointRecoveries(long currentTick) {
        for (RuntimeEntry runtime : List.copyOf(runtimes.values())) {
            SkillCheckpointRecoverySource source =
                    runtime.pendingSkillCheckpointRecovery;
            if (source == null
                    || runtime.state != BotLifecycleState.ACTIVE
                    || currentTick < runtime.nextSkillCheckpointRecoveryAttemptTick) {
                continue;
            }
            if (!checkpointStoreWritable()) {
                /*
                 * 不能把“下次可能会保存”的 SavedData 当成恢复证据。同步 durability
                 * 已失效时立即撤销 pending source，而不是在每 Tick 重试一个永远不会
                 * 入队的 suffix。
                 */
                revokePendingSkillCheckpointRecovery(
                        runtime, "checkpoint_store_not_durable");
                reportSkillCheckpointRecovery(
                        runtime, "checkpoint_store_not_durable");
                continue;
            }
            UUID botId = runtime.handle.botId();
            BotServerPlayer player = runtime.handle.player().orElse(null);
            if (player == null || !isListenerAuthority(player)) {
                deferSkillCheckpointRecovery(
                        runtime, currentTick, "listener_not_ready", false);
                continue;
            }
            String currentOwnershipBlocker =
                    pendingSkillCheckpointRecoveryBlocker(
                            runtime, player);
            if (currentOwnershipBlocker != null) {
                deferSkillCheckpointRecovery(
                        runtime,
                        currentTick,
                        currentOwnershipBlocker,
                        false);
                continue;
            }

            SkillCheckpoint checkpoint = source.checkpoint().orElse(null);
            if (checkpoint != null
                    && (checkpoint.attemptCount()
                                    >= SkillCheckpoint.MAX_ATTEMPTS
                            || checkpoint.recoveryCount()
                                    >= SkillCheckpoint.MAX_RECOVERIES)) {
                revokePendingSkillCheckpointRecovery(
                        runtime, "recovery_budget_exhausted");
                reportSkillCheckpointRecovery(
                        runtime,
                        "recovery_budget_exhausted");
                continue;
            }

            Optional<SkillPlan> approvedPlan = checkpoint == null
                    ? Optional.empty()
                    : approvedSkillPlanForCheckpoint(checkpoint);
            boolean currentQuiescent = hasDurableCheckpointQuiescence(
                    player, botId, runtime.handle.generation());
            boolean oldGenerationActionsQuiescent = checkpoint != null
                    && isP5GenerationQuiescent(
                            botId, checkpoint.generation());
            boolean oldGenerationTokensCleared = false;
            if (currentQuiescent && oldGenerationActionsQuiescent) {
                try {
                    /*
                     * 旧 run、信号和多资源 lease 都是瞬态的；只在动作 drain 已证明
                     * 安全后关闭精确旧代际。新的 request 会另取 runId 与全部 lease。
                     */
                    skillRuntime.closeGeneration(
                            botId,
                            checkpoint.generation(),
                            currentTick,
                            "checkpoint recovery replaced the old generation");
                    skillReservations.closeGeneration(
                            botId, checkpoint.generation());
                    oldGenerationTokensCleared = true;
                } catch (RuntimeException exception) {
                    reportSkillCheckpointRecovery(
                            runtime, "old_generation_cleanup_failed");
                }
            }

            SkillCheckpointReobservation reobservation = checkpoint == null
                    || approvedPlan.isEmpty()
                            ? new SkillCheckpointReobservation(
                                    false,
                                    Optional.empty(),
                                    List.of())
                            : skillCheckpointScopeObserver.reobserve(
                                    player,
                                    checkpoint,
                                    approvedPlan.orElseThrow(),
                                    currentTick);
            SkillCheckpointRecoveryCoordination coordination;
            try {
                coordination = SkillCheckpointRecoveryCoordinator.coordinate(
                        new SkillCheckpointRecoveryCoordinationRequest(
                                source,
                                roster.serverInstanceId(),
                                botId,
                                player.getUUID(),
                                runtime.handle.generation(),
                                approvedPlan,
                                approvedPlan.map(
                                                this::allSkillDescriptorsAvailable)
                                        .orElse(false),
                                new SkillCheckpointRecoverySafety(
                                        player.containerMenu
                                                == player.inventoryMenu,
                                        isP5GenerationQuiescent(
                                                botId,
                                                runtime.handle.generation()),
                                        player.inventoryMenu
                                                .getCarried().isEmpty(),
                                        oldGenerationTokensCleared),
                                reobservation,
                                currentTick));
            } catch (RuntimeException exception) {
                deferSkillCheckpointRecovery(
                        runtime, currentTick, "coordinator_internal_failure", true);
                continue;
            }
            if (coordination.request().isEmpty()) {
                Optional<SkillCheckpointRecoveryRejection> rejection =
                        coordination.rejection();
                if (rejection.orElse(null)
                        == SkillCheckpointRecoveryRejection
                                .CHECKPOINT_SCOPE_MISSING) {
                    /*
                     * 缺少 scope 的旧记录不可能在后续 Tick 获得重新观察材料；撤销
                     * pending 引用，防止每 Tick 把同一条不可验证的恢复意图重新入队。
                     */
                    revokePendingSkillCheckpointRecovery(
                            runtime, "checkpoint_scope_missing");
                    reportSkillCheckpointRecovery(
                            runtime, "checkpoint_scope_missing");
                    continue;
                }
                String outcome = rejection
                        .map(value -> "rejected_" + value.name().toLowerCase(Locale.ROOT))
                        .orElse("restart_plan_unavailable");
                deferSkillCheckpointRecovery(
                        runtime, currentTick, outcome, true);
                continue;
            }

            /*
             * submit() 只会在内存中创建 run；若在这里与首个新的安全 checkpoint 之间
             * 掉电，旧记录不能仍显示“从未恢复”。先以同 generation、更高 revision
             * 写一次 handoff，持久消耗恰好一个恢复预算。它不声称已有新动作或新 body，
             * 因此下一次重启仍能以严格更高的 body generation 安全重建 suffix。
             */
            SkillCheckpoint handoff;
            try {
                if (!checkpointStoreWritable()) {
                    throw new IllegalStateException(
                            "checkpoint store is not writable for recovery handoff");
                }
                handoff = SkillCheckpointBridge.recoveryHandoff(
                        Objects.requireNonNull(
                                checkpoint, "valid recovery checkpoint"),
                        currentTick);
                skillCheckpointCommitter.upsert(handoff);
                runtime.pendingSkillCheckpointRecovery =
                        SkillCheckpointRecoverySource.valid(handoff);
            } catch (RuntimeException exception) {
                deferSkillCheckpointRecovery(
                        runtime, currentTick, "handoff_persist_failed", true);
                continue;
            }

            SkillRunSubmission submission = skillRuntime.submit(
                    coordination.request().orElseThrow());
            if (submission.status() != SkillRunSubmission.Status.ACCEPTED) {
                deferSkillCheckpointRecovery(
                        runtime,
                        currentTick,
                        "runtime_" + submission.status().name().toLowerCase(Locale.ROOT),
                        true);
                continue;
            }
            UUID newRunId = submission.runId().orElseThrow();
            recoveredSkillCheckpointLineagesByRun.put(
                    newRunId,
                    new RecoveredSkillCheckpointLineage(
                            Objects.requireNonNull(
                                    checkpoint, "valid recovery checkpoint"),
                            coordination.restartPlan().orElseThrow()));
            runtime.pendingSkillCheckpointRecovery = null;
            runtime.nextSkillCheckpointRecoveryAttemptTick = 0L;
            runtime.lastSkillCheckpointRecoveryOutcome = null;
            skillCheckpointRevisions.remove(botId);
            BotPlayer.LOGGER.info(
                    "Recovered P5A checkpoint for BotPlayer {} ({}) into generation {}",
                    runtime.handle.name(),
                    botId,
                    runtime.handle.generation());
        }
    }

    /**
     * 新 body 接管旧 checkpoint 前不得已有任何 P5 所有者。这里除了运行时状态机，
     * 还把原版菜单/cursor 与动作代际一并当作布局所有权；否则恢复 suffix 会和一个
     * 尚未落盘的点击或安全自卫并发，保守延后比猜测其结果安全。
     */
    @Nullable
    private String pendingSkillCheckpointRecoveryBlocker(
            RuntimeEntry runtime, BotServerPlayer player) {
        UUID botId = runtime.handle.botId();
        long generation = runtime.handle.generation();
        if (skillRuntime.inspect(botId)
                .filter(view -> view.botGeneration() == generation
                        && !view.state().isTerminal())
                .isPresent()) {
            return "generic_p5_active";
        }
        if (survivalSkillService.inspect(botId)
                .filter(view -> view.botGeneration() == generation
                        && !view.state().isTerminal())
                .isPresent()) {
            return "legacy_p5_active";
        }
        if (selfDefenseSkillService.latestView(botId)
                .filter(view -> view.generation() == generation
                        && !view.status().terminal())
                .isPresent()) {
            return "self_defense_active";
        }
        if (player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) {
            return "menu_layout_active";
        }
        if (!isP5GenerationQuiescent(botId, generation)) {
            return "action_layout_active";
        }
        return null;
    }

    private Optional<SkillPlan> approvedSkillPlanForCheckpoint(
            SkillCheckpoint checkpoint) {
        /*
         * bootstrap_iron 是服务器二进制内固定、按精确模板编译的内建计划；它不需要也不允许
         * 外部 Pack 的审批来恢复。这里仍以 checkpoint 的 planId/revision/digest 三元组完整
         * 比对，任何被篡改、未来变体或不同 Bot 的记录都会落回 empty 并由恢复协调器拒绝。
         */
        Optional<SkillPlan> builtInBootstrap = builtinBootstrapPlanForCheckpoint(
                checkpoint);
        if (builtInBootstrap.isPresent()) {
            return builtInBootstrap;
        }
        List<SkillPlan> matches = skillPackManager.records().stream()
                .filter(record -> record.state() == SkillPackState.APPROVED)
                .map(record -> record.candidate().definition().plan())
                .filter(plan -> plan.botId().equals(checkpoint.botId()))
                .filter(plan -> new io.github.greytaiwolf.botplayer.skill.checkpoint
                        .SkillCheckpointPlan(
                                plan.planId(),
                                plan.revision(),
                                SkillCheckpointBridge.planDigest(plan))
                        .equals(checkpoint.plan()))
                .toList();
        return matches.size() == 1
                ? Optional.of(matches.get(0))
                : Optional.empty();
    }

    private static Optional<SkillPlan> builtinBootstrapPlanForCheckpoint(
            SkillCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        try {
            SkillPlan plan = ProductionSkillPlanCompiler.p5aDefault()
                    .compileWoodToIronPick(
                            checkpoint.botId(), checkpoint.plan().revision());
            io.github.greytaiwolf.botplayer.skill.checkpoint
                    .SkillCheckpointPlan expected = new io.github.greytaiwolf.botplayer
                            .skill.checkpoint.SkillCheckpointPlan(
                                    plan.planId(),
                                    plan.revision(),
                                    SkillCheckpointBridge.planDigest(plan));
            return expected.equals(checkpoint.plan())
                    ? Optional.of(plan)
                    : Optional.empty();
        } catch (RuntimeException exception) {
            /* 静态内建模板异常时绝不能猜测恢复内容。 */
            return Optional.empty();
        }
    }

    private boolean allSkillDescriptorsAvailable(SkillPlan plan) {
        return plan.nodes().stream().allMatch(node -> skillRegistry.find(
                node.skillId(), node.skillVersion()).isPresent());
    }

    private void deferSkillCheckpointRecovery(
            RuntimeEntry runtime,
            long currentTick,
            String outcome,
            boolean report) {
        runtime.nextSkillCheckpointRecoveryAttemptTick = currentTick
                >= Long.MAX_VALUE - 20L
                        ? Long.MAX_VALUE
                        : currentTick + 20L;
        if (report) {
            reportSkillCheckpointRecovery(runtime, outcome);
        }
    }

    private void reportSkillCheckpointRecovery(
            RuntimeEntry runtime, String outcome) {
        if (outcome.equals(runtime.lastSkillCheckpointRecoveryOutcome)) {
            return;
        }
        runtime.lastSkillCheckpointRecoveryOutcome = outcome;
        BotPlayer.LOGGER.warn(
                "P5A checkpoint recovery for BotPlayer {} ({}) is fail-closed: {}",
                runtime.handle.name(), runtime.handle.botId(), outcome);
    }

    private void persistSafeSkillCheckpoints(long currentTick) {
        for (RuntimeEntry runtime : List.copyOf(runtimes.values())) {
            if (runtime.state != BotLifecycleState.ACTIVE) {
                continue;
            }
            UUID botId = runtime.handle.botId();
            long generation = runtime.handle.generation();
            Optional<SkillRuntimeCheckpoint> checkpoint =
                    skillRuntime.checkpoint(botId);
            if (checkpoint.isEmpty()) {
                skillRuntime.inspect(botId)
                        .filter(view -> view.botGeneration() == generation
                                && view.state().isTerminal())
                        .ifPresent(view -> {
                            terminalizeSkillCheckpointGeneration(
                                    botId, generation, currentTick);
                            skillCheckpointRevisions.remove(botId);
                            recoveredSkillCheckpointLineagesByRun.remove(
                                    view.runId());
                        });
                continue;
            }
            SkillRuntimeCheckpoint value = checkpoint.orElseThrow();
            if (value.view().botGeneration() != generation
                    || !safeCheckpointState(value.view().state())
                    || skillCheckpointRevisions.getOrDefault(
                            botId, -1L) >= value.view().stateRevision()) {
                continue;
            }
            BotServerPlayer player = runtime.handle.player().orElse(null);
            if (player == null
                    || !hasDurableCheckpointQuiescence(
                            player, botId, generation)) {
                continue;
            }
            if (!requiresProductionCheckpointScope(value.plan())) {
                /*
                 * 外部 Pack 和非生产内建技能没有可审核的世界范围，不能把一份空 scope
                 * 写成 restartable 记录。它们仍可正常执行；重启时只会丢失进度而不会
                 * 重放任何原版副作用。
                 */
                terminalizeSkillCheckpointGeneration(
                        botId, generation, currentTick);
                skillCheckpointRevisions.remove(botId);
                continue;
            }
            try {
                RecoveredSkillCheckpointLineage lineage =
                        recoveredSkillCheckpointLineagesByRun.get(
                                value.view().runId());
                SkillCheckpointPlan sourcePlan = lineage == null
                        ? new SkillCheckpointPlan(
                                value.plan().planId(),
                                value.plan().revision(),
                                SkillCheckpointBridge.planDigest(
                                        value.plan()))
                        : lineage.prior().plan();
                MinecraftSkillCheckpointScopeObserver.CaptureResult captured =
                        skillCheckpointScopeObserver.capture(
                                player,
                                value,
                                sourcePlan,
                                Optional.ofNullable(lineage).map(
                                        RecoveredSkillCheckpointLineage
                                                ::restartPlan),
                                currentTick);
                SkillCheckpointScope scope = captured.scope()
                        .orElseThrow(() -> new IllegalStateException(
                                "P5A checkpoint scope capture is unavailable: "
                                        + captured.rejectionCode()));
                SkillCheckpoint persisted = lineage == null
                        ? SkillCheckpointBridge.checkpoint(
                                roster.serverInstanceId(),
                                player.getUUID(),
                                value,
                                scope)
                        : SkillCheckpointBridge.recoveredCheckpoint(
                                roster.serverInstanceId(),
                                player.getUUID(),
                                value,
                                lineage.prior(),
                                lineage.restartPlan(),
                                scope);
                skillCheckpointCommitter.upsert(persisted);
                if (lineage != null) {
                    recoveredSkillCheckpointLineagesByRun.put(
                            value.view().runId(),
                            new RecoveredSkillCheckpointLineage(
                                    persisted, lineage.restartPlan()));
                }
                skillCheckpointRevisions.put(
                        botId, value.view().stateRevision());
            } catch (RuntimeException exception) {
                RecoveredSkillCheckpointLineage lineage =
                        recoveredSkillCheckpointLineagesByRun.get(
                                value.view().runId());
                if (lineage != null) {
                    terminalizeSkillCheckpointGeneration(
                            botId,
                            lineage.prior().generation(),
                            currentTick);
                }
                terminalizeSkillCheckpointGeneration(
                        botId, generation, currentTick);
                skillRuntime.cancel(
                        value.view().runId(),
                        currentTick,
                        "技能检查点持久化失败，已安全取消计划");
                BotPlayer.LOGGER.error(
                        "Cancelled P5A skill run {} after checkpoint persistence failed",
                        value.view().runId(),
                        exception);
            }
        }
    }

    private static boolean safeCheckpointState(SkillRunState state) {
        return state == SkillRunState.PREPARING
                || state == SkillRunState.PAUSED;
    }

    private static boolean requiresProductionCheckpointScope(SkillPlan plan) {
        return plan.nodes().stream().anyMatch(node -> node.skillId().equals(
                P5ABuiltinSkillIds.BOOTSTRAP_IRON));
    }

    private static boolean isCanonicalBootstrapPlan(SkillPlan plan) {
        try {
            return plan.equals(ProductionSkillPlanCompiler.p5aDefault()
                    .compileWoodToIronPick(
                            plan.botId(), plan.revision()));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Java 状态机显示“可保存”还不够：原版菜单、cursor 和动作运行时也必须处于
     * 代际静止点。这样停服后的 checkpoint 从不声称能恢复半个点击或半开的世界容器。
     */
    private boolean hasDurableCheckpointQuiescence(
            BotServerPlayer player, UUID botId, long generation) {
        return player.containerMenu == player.inventoryMenu
                && player.inventoryMenu.getCarried().isEmpty()
                && isP5GenerationQuiescent(botId, generation);
    }

    /**
     * An Action ledger drain alone is not enough after P5C: a completed melee
     * Action may still be waiting for its owner-thread technique acknowledgement
     * and terminal boundary. Check both independent fences before a body or
     * checkpoint is treated as quiescent.
     */
    private boolean isP5GenerationQuiescent(UUID botId, long generation) {
        return actionRuntime.isGenerationSafe(botId, generation)
                && techniqueLifecycleCoordinator.isGenerationSafe(
                        botId, generation);
    }

    /**
     * {@link SkillRuntime} 在调用 node handler 之前进入这里。只要磁盘上还留着一份
     * restartable checkpoint，就必须先把它同步替换为 terminal dispatch fence；否则动作
     * 已改变世界、进程却在下一个安全点之前掉电时，旧安全点会在新 JVM 中重放该动作。
     */
    private SkillRuntimeDispatchFence.Result fenceSkillNodeDispatch(
            SkillRuntimeCheckpoint runtimeCheckpoint, long currentTick) {
        Objects.requireNonNull(runtimeCheckpoint, "runtimeCheckpoint");
        if (!checkpointStoreWritable()) {
            return SkillRuntimeDispatchFence.Result.reject(
                    "checkpoint 存储没有可用的同步耐久边界；拒绝派发原版动作");
        }
        SkillRunView view = runtimeCheckpoint.view();
        RuntimeEntry runtime = runtimes.get(view.botId());
        BotServerPlayer player = runtime == null
                ? null
                : runtime.handle.player().orElse(null);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE
                || runtime.handle.generation() != view.botGeneration()
                || player == null
                || !isListenerAuthority(player)) {
            return SkillRuntimeDispatchFence.Result.reject(
                    "派发前 BotPlayer 生命周期身份已变化");
        }
        SkillCheckpoint persisted = skillCheckpoints.load(view.botId())
                .orElse(null);
        if (persisted == null || persisted.continuationState().isTerminal()) {
            /* 没有旧恢复点时，崩溃最多失去进度，绝不会重放一份旧动作。 */
            return SkillRuntimeDispatchFence.Result.permit();
        }
        if (!persisted.serverInstanceId().equals(roster.serverInstanceId())
                || !persisted.botId().equals(view.botId())
                || !persisted.playerId().equals(player.getUUID())) {
            return SkillRuntimeDispatchFence.Result.reject(
                    "旧 checkpoint 身份不匹配；拒绝覆盖或派发动作");
        }
        try {
            skillCheckpointCommitter.upsert(
                    SkillCheckpointBridge.dispatchFence(
                            persisted, currentTick));
            /* 下一次真正到达安全点必须重新落盘，不可沿用已撤销 revision。 */
            skillCheckpointRevisions.remove(view.botId());
            return SkillRuntimeDispatchFence.Result.permit();
        } catch (RuntimeException exception) {
            BotPlayer.LOGGER.error(
                    "Could not durably fence P5A dispatch for BotPlayer {} ({})",
                    runtime.handle.name(), view.botId(), exception);
            return SkillRuntimeDispatchFence.Result.reject(
                    "checkpoint 派发围栏无法同步提交；拒绝原版动作");
        }
    }

    /**
     * 仅当耐久记录精确对应当前 run/revision、且这一刻仍是原版与动作层共同静止点时，
     * 正常 shutdown 才可保留它。这里不写盘；失败或竞态只会使后续走删除 checkpoint 的
     * 保守路径。
     */
    private Optional<Long> currentShutdownCheckpointGeneration(
            RuntimeEntry runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (!checkpointStoreWritable()
                || runtime.state != BotLifecycleState.ACTIVE) {
            return Optional.empty();
        }
        BotServerPlayer player = runtime.handle.player().orElse(null);
        long generation = runtime.handle.generation();
        if (player == null
                || generation <= 0L
                || !isListenerAuthority(player)
                || !hasDurableCheckpointQuiescence(
                        player, runtime.handle.botId(), generation)) {
            return Optional.empty();
        }
        SkillRuntimeCheckpoint runtimeCheckpoint = skillRuntime.checkpoint(
                runtime.handle.botId()).orElse(null);
        if (runtimeCheckpoint == null
                || runtimeCheckpoint.view().botGeneration() != generation
                || !safeCheckpointState(runtimeCheckpoint.view().state())
                || skillCheckpointRevisions.getOrDefault(
                        runtime.handle.botId(), -1L)
                        != runtimeCheckpoint.view().stateRevision()) {
            return Optional.empty();
        }
        SkillCheckpoint persisted = skillCheckpoints.load(
                runtime.handle.botId()).orElse(null);
        if (persisted == null
                || !persisted.serverInstanceId().equals(
                        roster.serverInstanceId())
                || !persisted.botId().equals(runtime.handle.botId())
                || !persisted.playerId().equals(player.getUUID())
                || persisted.generation() != generation
                || !persisted.runId().equals(
                        runtimeCheckpoint.view().runId())
                || !persisted.checkpointId().equals(
                        runtimeCheckpoint.view().runId())
                || persisted.stateRevision()
                        != runtimeCheckpoint.view().stateRevision()
                || persisted.continuationState().isTerminal()
                || persisted.scope().isEmpty()) {
            return Optional.empty();
        }
        RecoveredSkillCheckpointLineage lineage =
                recoveredSkillCheckpointLineagesByRun.get(
                        runtimeCheckpoint.view().runId());
        io.github.greytaiwolf.botplayer.skill.checkpoint
                .SkillCheckpointPlan expectedPlan = lineage == null
                        ? new io.github.greytaiwolf.botplayer.skill.checkpoint
                                .SkillCheckpointPlan(
                                        runtimeCheckpoint.view().planId(),
                                        runtimeCheckpoint.view().planRevision(),
                                        SkillCheckpointBridge.planDigest(
                                                runtimeCheckpoint.plan()))
                        : lineage.prior().plan();
        return persisted.plan().equals(expectedPlan)
                ? Optional.of(generation)
                : Optional.empty();
    }

    private boolean checkpointStoreWritable() {
        return skillCheckpoints.loadStatus()
                        == SkillCheckpointLoadStatus.VALID
                && skillCheckpointCommitter.isReady();
    }

    /**
     * 以 durable terminal tombstone 取代“内存删除后再保存”。在动作已经发生过的场景，
     * 删除提交失败会让下一 JVM 重新读到旧的 restartable checkpoint；而 tombstone 一旦
     * 成功落盘，即使后续真正删除永远失败也只会丢进度、不会重放副作用。
     */
    private boolean terminalizeSkillCheckpointGeneration(
            UUID botId, long generation, long currentTick) {
        Objects.requireNonNull(botId, "botId");
        if (generation <= 0L || !checkpointStoreWritable()) {
            return false;
        }
        SkillCheckpoint existing = skillCheckpoints.load(botId).orElse(null);
        if (existing == null || existing.generation() != generation
                || existing.continuationState().isTerminal()) {
            return true;
        }
        try {
            skillCheckpointCommitter.upsert(
                    SkillCheckpointBridge.terminalTombstone(
                            existing, currentTick));
            skillCheckpointRevisions.remove(botId);
            return true;
        } catch (RuntimeException exception) {
            BotPlayer.LOGGER.error(
                    "Could not durably terminalize P5A checkpoint for BotPlayer {} generation {}",
                    botId, generation, exception);
            return false;
        }
    }

    public void shutdown() {
        requireServerThread();
        if (stopping) {
            return;
        }
        long currentTick = server.getTickCount();
        stopping = true;
        closeAllAiProposalRequests();
        List<RuntimeEntry> shutdownRuntimes =
                new ArrayList<>(runtimes.values());
        RuntimeException preparationFailure = null;

        /*
         * 这份资格必须在关闭 runtime 之前冻结：若计划已经离开上一份安全点、发出了
         * 动作或打开过菜单，旧 checkpoint 不能因为“正常停服”而幸存下来重做副作用。
         * 后续任何 no-save/fallback 都会显式撤销该资格。
         */
        for (RuntimeEntry runtime : shutdownRuntimes) {
            runtime.preserveSkillCheckpointGeneration =
                    currentShutdownCheckpointGeneration(runtime)
                            .orElse(-1L);
            if (runtime.preserveSkillCheckpointGeneration < 0L) {
                revokePendingSkillCheckpointRecovery(
                        runtime,
                        "shutdown_checkpoint_not_eligible");
            }
        }

        /*
         * 必须先于任何菜单/动作回调建立保存 fence。setter 本身不进入原版
         * 回调；随后完整 ticket 再覆盖 PlayerList、level、replacement 与
         * 已绑定 listener 能看到的全部 exact body。
         */
        for (RuntimeEntry runtime : shutdownRuntimes) {
            BotServerPlayer attached =
                    runtime.handle.player().orElse(null);
            armImmediatelyKnownNoSaveBodies(
                    runtime, attached, null);
            try {
                PendingNoSaveTeardown teardown =
                        rememberNoSaveTeardown(
                                runtime, attached, null);
                for (BotServerPlayer exactBody :
                        teardown.exactBodies) {
                    exactBody
                            .suppressPlayerDataSaveUntilReleased();
                }
            } catch (RuntimeException exception) {
                preparationFailure = appendFailure(
                        preparationFailure, exception);
            }
        }
        try {
            closeAllInventories(
                    InventoryCloseReason.SERVER_STOPPING);
        } catch (RuntimeException exception) {
            preparationFailure = appendFailure(
                    preparationFailure, exception);
        }
        try {
            techniqueLifecycleCoordinator.shutdown(currentTick);
        } catch (RuntimeException exception) {
            preparationFailure = appendFailure(
                    preparationFailure, exception);
        }
        try {
            selfDefenseSkillService.close();
        } catch (RuntimeException exception) {
            preparationFailure = appendFailure(
                    preparationFailure, exception);
        }
        try {
            safetyService.shutdown();
        } catch (RuntimeException exception) {
            preparationFailure = appendFailure(
                    preparationFailure, exception);
        }
        try {
            navigationService.close();
        } catch (RuntimeException exception) {
            preparationFailure = appendFailure(
                    preparationFailure, exception);
        }
        try {
            skillRuntime.close();
        } catch (RuntimeException exception) {
            preparationFailure = appendFailure(
                    preparationFailure, exception);
        }
        safetyPausedSkillRuns.clear();
        try {
            actionRuntime.shutdown(currentTick);
        } catch (RuntimeException exception) {
            preparationFailure = appendFailure(
                    preparationFailure, exception);
        }
        try {
            techniqueLifecycleCoordinator.drainCompletedChildren(currentTick);
        } catch (RuntimeException exception) {
            preparationFailure = appendFailure(
                    preparationFailure, exception);
        }

        /*
         * 任一准备步骤失去证明后，不能再依据局部 runtime 视图走原版保存。
         * 仍继续为每个 exact body 建票、上持久 fence 并执行 no-save 移除，
         * 这样一次 shutdown 异常不会跳过后续 Bot 的隔离。
         */
        boolean allowNormalPlayerSave =
                preparationFailure == null;
        if (!allowNormalPlayerSave) {
            BotPlayer.LOGGER.error(
                    "BotPlayer shutdown preparation failed; all remaining bots will use no-save isolation",
                    preparationFailure);
        }
        try {
            for (RuntimeEntry runtime : shutdownRuntimes) {
                try {
                    PendingNoSaveTeardown teardown =
                            rememberNoSaveTeardown(
                                    runtime,
                                    runtime.handle.player()
                                            .orElse(null),
                                    null);
                    UUID botId = teardown.botId;
                    boolean normalDisconnectStarted =
                            allowNormalPlayerSave
                                    && tryNormalShutdownDisconnect(
                                            runtime,
                                            teardown);
                    if (!normalDisconnectStarted
                            || runtimes.get(botId) == runtime) {
                        RuntimeException isolationFailure =
                                finalizeRuntimeTeardownWithoutSave(
                                        runtime,
                                        runtime.handle.player()
                                                .orElse(null),
                                        null);
                        if (isolationFailure != null) {
                            throw isolationFailure;
                        }
                    }
                } catch (RuntimeException exception) {
                    BotPlayer.LOGGER.error(
                            "Failed to cleanly unload BotPlayer {} ({}) during server stop",
                            runtime.handle.name(),
                            runtime.handle.botId(),
                            exception);
                    closeFailedShutdownRuntime(runtime);
                }
            }
            try {
                survivalSkillService.shutdown(
                        currentTick,
                        (botId, generation) ->
                                allowNormalPlayerSave
                                        && isP5GenerationQuiescent(
                                                botId, generation));
            } catch (RuntimeException exception) {
                BotPlayer.LOGGER.error(
                        "Failed to close BotPlayer survival skill state during server stop",
                        exception);
            }
        } finally {
            try {
                perceptionService.shutdown();
            } catch (RuntimeException exception) {
                BotPlayer.LOGGER.error(
                        "Failed to close BotPlayer perception state during server stop",
                        exception);
            } finally {
                runtimes.clear();
                activeAgentByBot.clear();
                botByActiveAgent.clear();
            }
        }
    }

    /**
     * 保持 persistent fence 完成动作、技能布局与 listener 票据的全部退休；只有
     * topology 仍是单 body/单 listener 时才释放真正会被 vanilla 保存的 body。
     */
    private boolean tryNormalShutdownDisconnect(
            RuntimeEntry runtime,
            PendingNoSaveTeardown teardown) {
        UUID botId = teardown.botId;
        long generation = teardown.retiredGeneration;
        BotServerPlayer player =
                runtime.handle.player().orElse(null);
        if (runtime.directDisconnectRetirement != null
                || runtime.deathRetirement != null
                || runtimes.get(botId) != runtime
                || runtime.handle.generation()
                        != generation
                || player == null
                || !isP5GenerationQuiescent(
                        botId, generation)) {
            return false;
        }

        GenerationRetirement retirement =
                retireGenerationBestEffort(
                        runtime,
                        generation,
                        InventoryCloseReason.SERVER_STOPPING);
        if (!retirement.safelyClosed()
                || retirement.failure() != null
                || !prepareListenerDisconnect(
                        runtime,
                        player,
                        generation,
                        retirement)) {
            return false;
        }

        PendingNoSaveTeardown refreshed =
                rememberNoSaveTeardown(
                        runtime, player, null);
        if (refreshed != teardown
                || !isSingleShutdownSaveTopology(
                        runtime,
                        refreshed,
                        player,
                        generation)) {
            return false;
        }

        player.releasePlayerDataSaveSuppression();
        /*
         * 后续 listener disconnect 仍会走统一的退休状态机。此前在 shutdown 入口
         * 冻结的精确代际标记会留到它完成；这里绝不能临时补发资格，否则一个已经
         * 离开安全点的旧 checkpoint 会被错误保留。
         */
        disconnect(
                runtime,
                Component.literal("Server stopping"));
        return true;
    }

    private boolean isSingleShutdownSaveTopology(
            RuntimeEntry runtime,
            PendingNoSaveTeardown teardown,
            BotServerPlayer player,
            long generation) {
        if (!(player.connection
                        instanceof BotGamePacketListener listener)) {
            return false;
        }
        Connection connection = listener.getConnection();
        return teardown.exactBodies.size() == 1
                && teardown.exactBodies.contains(player)
                && teardown.exactListeners.size() == 1
                && teardown.exactListeners.contains(listener)
                && teardown.boundDisconnectListener == listener
                && teardown.boundDisconnectConnection == connection
                && isPreparedListenerDisconnect(
                        runtime,
                        player,
                        listener,
                        connection,
                        generation)
                && runtimes.get(teardown.botId) == runtime
                && runtime.handle.player().orElse(null)
                        == player
                && runtime.handle.generation()
                        == generation
                && player.connection == listener
                && listener.player == player
                && server.getPlayerList()
                                .getPlayer(teardown.botId)
                        == player
                && player.serverLevel()
                                .getPlayerByUUID(
                                        teardown.botId)
                        == player;
    }

    private void disconnect(RuntimeEntry runtime, Component reason) {
        revokePendingSkillCheckpointRecovery(
                runtime, "disconnect_requested");
        if (runtime.disconnectingPlayer != null
                || hasRequestedListenerDisconnect(
                        runtime)) {
            runtime.respawnAtTick = -1;
            runtime.respawnFinalizeDeadlineTick = -1;
            runtime.respawnCandidate = null;
            runtime.respawnSuppressed = true;
            runtime.handoffAborted |=
                    runtime.replacementHandoffInProgress;
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
            if (runtime.disconnectingPlayer != null
                    && !listenerDisconnectRequested(
                            runtime.disconnectingPlayer)
                    && runtime.disconnectingListener
                            instanceof BotGamePacketListener
                                    botListener) {
                botListener.disconnect(reason);
            }
            return;
        }
        runtime.respawnAtTick = -1;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnCandidate = null;
        runtime.respawnSuppressed = true;
        BotServerPlayer attached =
                runtime.handle.player().orElse(null);
        BotServerPlayer disconnectBody = attached;
        if (attached != null
                && attached.connection != null
                && attached.connection.player
                        instanceof BotServerPlayer
                                listenerPlayer
                && listenerPlayer.runtimeHandle()
                        == runtime.handle
                && listenerPlayer.getUUID()
                        .equals(runtime.handle.botId())) {
            disconnectBody = listenerPlayer;
        }

        RuntimeException failure = null;
        if (attached != null
                && attached.connection != null) {
            try {
                attached.connection.disconnect(reason);
            } catch (RuntimeException exception) {
                failure = appendFailure(
                        failure, exception);
            }
        }
        if (runtime.disconnectingPlayer == null
                && !hasRequestedListenerDisconnect(
                        runtime)
                && runtimes.get(
                        runtime.handle.botId())
                == runtime) {
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
            GenerationRetirement retirement =
                    retireGenerationBestEffort(
                            runtime,
                            runtime.handle.generation(),
                            InventoryCloseReason
                                    .BOT_UNLOADED);
            failure = appendFailure(
                    failure,
                    retirement.failure());
            failure = appendFailure(
                    failure,
                    finalizeRuntimeTeardown(
                            runtime,
                            disconnectBody));
        }
        if (failure != null) {
            throw failure;
        }
    }

    private ListenerDisconnectDecision
            advanceDirectListenerDisconnect(
                    RuntimeEntry runtime,
                    BotServerPlayer player,
                    ServerGamePacketListenerImpl listener,
                    Connection connection,
                    long generation) {
        PendingDirectDisconnectRetirement pending =
                runtime.directDisconnectRetirement;
        boolean opened = false;
        if (pending == null) {
            if (generation <= 0L
                    || runtime.handle.player()
                                    .orElse(null)
                            != player) {
                abortUnstableListenerDisconnect(
                        runtime,
                        player,
                        server.getPlayerList()
                                .getPlayer(player.getUUID()),
                        "Direct disconnect could not freeze its authoritative body");
                return ListenerDisconnectDecision.ABORTED;
            }
            long startedTick = server.getTickCount();
            GenerationRetirementKey key =
                    new GenerationRetirementKey(
                            UUID.randomUUID(),
                            runtime.handle.botId(),
                            generation,
                            GenerationRetirementContinuation
                                    .DISCONNECT_PRE_SAVE);
            pending = new PendingDirectDisconnectRetirement(
                    player,
                    listener,
                    connection,
                    generation,
                    GenerationRetirementSession.open(
                            key,
                            startedTick,
                            boundedRetirementDeadline(
                                    startedTick)));
            runtime.directDisconnectRetirement = pending;
            player.armDisconnectPreSaveFence();
            opened = true;
        }
        if (!isExactDirectDisconnectAuthority(
                runtime, pending)) {
            pending.failure = appendFailure(
                    pending.failure,
                    new IllegalStateException(
                            "Direct disconnect authority changed before retirement completed"));
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    server.getPlayerList()
                            .getPlayer(player.getUUID()),
                    "Direct disconnect retirement lost exact authority");
            return ListenerDisconnectDecision.ABORTED;
        }

        GenerationRetirementStatus status = opened
                ? advanceDirectDisconnectRetirement(
                        runtime,
                        pending,
                        server.getTickCount())
                : pending.status();
        if (runtimes.get(runtime.handle.botId())
                != runtime) {
            return ListenerDisconnectDecision.ABORTED;
        }
        if (status == GenerationRetirementStatus.PENDING) {
            return ListenerDisconnectDecision.RETRY;
        }
        if (status == GenerationRetirementStatus.UNSAFE
                || !isExactDirectDisconnectAuthority(
                        runtime, pending)) {
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    server.getPlayerList()
                            .getPlayer(player.getUUID()),
                    "Direct disconnect retirement did not reach a safe pre-save endpoint");
            return ListenerDisconnectDecision.ABORTED;
        }

        runtime.disconnectPreparationComplete = true;
        runtime.disconnectPreparationSafelyClosed = true;
        runtime.disconnectPreparationFailure = null;
        if (!player.hasDisconnectPreSaveFence()
                || !player.releaseDisconnectPreSaveFence()) {
            pending.failure = appendFailure(
                    pending.failure,
                    new IllegalStateException(
                            "Another save fence remained after direct disconnect retirement"));
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    server.getPlayerList()
                            .getPlayer(player.getUUID()),
                    "Direct disconnect could not exclusively release its pre-save fence");
            return ListenerDisconnectDecision.ABORTED;
        }
        return ListenerDisconnectDecision.PROCEED;
    }

    private void advanceDirectDisconnectRetirements(
            long currentTick) {
        for (RuntimeEntry runtime :
                List.copyOf(runtimes.values())) {
            PendingDirectDisconnectRetirement pending =
                    runtime.directDisconnectRetirement;
            if (pending == null
                    || pending.status()
                            != GenerationRetirementStatus.PENDING) {
                continue;
            }
            if (!isExactDirectDisconnectAuthority(
                    runtime, pending)) {
                pending.failure = appendFailure(
                        pending.failure,
                        new IllegalStateException(
                                "Direct disconnect authority changed while cleanup was pending"));
                abortUnstableListenerDisconnect(
                        runtime,
                        pending.player,
                        server.getPlayerList()
                                .getPlayer(
                                        pending.player.getUUID()),
                        "Pending direct disconnect lost exact authority");
                continue;
            }
            GenerationRetirementStatus status =
                    advanceDirectDisconnectRetirement(
                            runtime,
                            pending,
                            currentTick);
            if (runtimes.get(runtime.handle.botId())
                    != runtime) {
                continue;
            }
            if (status == GenerationRetirementStatus.UNSAFE) {
                abortUnstableListenerDisconnect(
                        runtime,
                        pending.player,
                        server.getPlayerList()
                                .getPlayer(
                                        pending.player.getUUID()),
                        "Pending direct disconnect exhausted its safe cleanup contract");
            }
        }
    }

    private GenerationRetirementStatus
            advanceDirectDisconnectRetirement(
                    RuntimeEntry runtime,
                    PendingDirectDisconnectRetirement pending,
                    long currentTick) {
        GenerationRetirementStatus currentStatus =
                pending.status();
        if (currentStatus
                        != GenerationRetirementStatus.PENDING
                || pending.lastAdvanceTick == currentTick
                || pending.session.attempt() > 0
                        && currentTick
                                < pending.session.nextRetryTick()
                || runtime.generationRetirementInProgress) {
            return currentStatus;
        }
        pending.lastAdvanceTick = currentTick;
        runtime.generationRetirementInProgress = true;
        try {
            if (!pending.beginAttempted) {
                pending.beginAttempted = true;
                pending.failure = appendFailure(
                        pending.failure,
                        beginDirectDisconnectRetirement(
                                runtime, pending));
            }

            GenerationRetirementStatus observedStatus;
            if (pending.failure != null) {
                observedStatus =
                        GenerationRetirementStatus.UNSAFE;
            } else {
                GenerationDrainStatus drainStatus =
                        actionRuntime.generationDrainStatus(
                                runtime.handle.botId(),
                                pending.generation);
                observedStatus = switch (drainStatus) {
                    case PENDING ->
                            GenerationRetirementStatus.PENDING;
                    case UNSAFE ->
                            GenerationRetirementStatus.UNSAFE;
                    case COMPLETE ->
                            finishDirectDisconnectSurvival(
                                    runtime, pending)
                                    ? GenerationRetirementStatus.COMPLETE
                                    : GenerationRetirementStatus.UNSAFE;
                };
                if (drainStatus == GenerationDrainStatus.UNSAFE) {
                    pending.failure = appendFailure(
                            pending.failure,
                            new IllegalStateException(
                                    "Action generation was quarantined during direct disconnect"));
                }
            }

            GenerationRetirementTicket ticket;
            if (pending.lastTicket == null) {
                ticket = new GenerationRetirementTicket(
                        pending.session.key(),
                        pending.session.startedTick(),
                        pending.session.deadlineTick(),
                        currentTick,
                        1);
            } else {
                ticket = pending.lastTicket.next(
                        Objects.requireNonNull(
                                pending.lastReceipt,
                                "lastReceipt"),
                        currentTick);
            }
            long progressRevision =
                    observedStatus
                                    == GenerationRetirementStatus.PENDING
                            ? pending.session.progressRevision()
                            : incrementRetirementProgress(
                                    pending.session
                                            .progressRevision());
            long nextRetryTick =
                    observedStatus
                                    == GenerationRetirementStatus.PENDING
                            ? nextRetirementTick(currentTick)
                            : -1L;
            GenerationRetirementSession.Update update =
                    pending.session.observe(
                            ticket,
                            observedStatus,
                            progressRevision,
                            nextRetryTick);
            pending.session = update.session();
            pending.lastTicket = ticket;
            pending.lastReceipt = update.receipt();
            return pending.status();
        } catch (RuntimeException exception) {
            pending.failure = appendFailure(
                    pending.failure, exception);
            return GenerationRetirementStatus.UNSAFE;
        } finally {
            runtime.generationRetirementInProgress = false;
        }
    }

    @Nullable
    private RuntimeException beginDirectDisconnectRetirement(
            RuntimeEntry runtime,
            PendingDirectDisconnectRetirement pending) {
        RuntimeException failure = null;
        try {
            closeBotInventory(
                    runtime,
                    InventoryCloseReason.BOT_UNLOADED);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDirectAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        try {
            cancelBotActions(
                    runtime,
                    pending.generation,
                    ActionCancellationReason.LIFECYCLE);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDirectAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        try {
            inputController.forceClear(
                    runtime.handle.botId(),
                    pending.generation);
            MinecraftPlayerInputAdapter.clear(
                    pending.player);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDirectAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        try {
            perceptionService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDirectAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        long currentTick = server.getTickCount();
        try {
            navigationService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation,
                    currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        try {
            safetyService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation,
                    currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        return appendDirectAuthorityFailure(
                runtime, pending, failure);
    }

    private boolean finishDirectDisconnectSurvival(
            RuntimeEntry runtime,
            PendingDirectDisconnectRetirement pending) {
        if (pending.survivalCloseAttempted) {
            return pending.failure == null;
        }
        pending.survivalCloseAttempted = true;
        try {
            if (!survivalSkillService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation,
                    server.getTickCount(),
                    true)) {
                pending.failure = appendFailure(
                        pending.failure,
                        new IllegalStateException(
                                "Survival layout did not produce a safe direct-disconnect receipt"));
            }
        } catch (RuntimeException exception) {
            pending.failure = appendFailure(
                    pending.failure, exception);
        }
        try {
            closeGenericSkillGeneration(
                    runtime.handle.botId(),
                    pending.generation,
                    server.getTickCount(),
                    "Bot direct disconnect closed this generation",
                    runtime.preserveSkillCheckpointGeneration
                            == pending.generation);
            runtime.preserveSkillCheckpointGeneration = -1L;
        } catch (RuntimeException exception) {
            pending.failure = appendFailure(
                    pending.failure, exception);
        }
        pending.failure = appendDirectAuthorityFailure(
                runtime,
                pending,
                pending.failure);
        return pending.failure == null;
    }

    @Nullable
    private RuntimeException appendDirectAuthorityFailure(
            RuntimeEntry runtime,
            PendingDirectDisconnectRetirement pending,
            @Nullable RuntimeException failure) {
        if (isExactDirectDisconnectAuthority(
                runtime, pending)) {
            return failure;
        }
        return appendFailure(
                failure,
                new IllegalStateException(
                        "Direct disconnect authority changed during generation retirement"));
    }

    private boolean isExactDirectDisconnectAuthority(
            RuntimeEntry runtime,
            PendingDirectDisconnectRetirement pending) {
        UUID botId = runtime.handle.botId();
        return runtimes.get(botId) == runtime
                && handlesByBot.get(botId)
                        == runtime.handle
                && runtime.state
                        == BotLifecycleState.DESPAWNING
                && runtime.handle.generation()
                        == pending.generation
                && runtime.handle.player()
                                .orElse(null)
                        == pending.player
                && pending.matchesBinding(
                        pending.player,
                        pending.listener,
                        pending.connection,
                        pending.generation)
                && runtime.disconnectingPlayer
                        == pending.player
                && runtime.disconnectingListener
                        == pending.listener
                && runtime.disconnectingConnection
                        == pending.connection
                && runtime.disconnectingGeneration
                        == pending.generation
                && pending.player.runtimeHandle()
                        == runtime.handle
                && pending.player.hasDisconnectPreSaveFence()
                && pending.player.connection
                        == pending.listener
                && pending.listener.player
                        == pending.player
                && pending.listener.getConnection()
                        == pending.connection
                && server.getPlayerList()
                                .getPlayer(botId)
                        == pending.player
                && pending.player.serverLevel()
                                .getPlayerByUUID(botId)
                        == pending.player
                && isBotConnectionOpen(
                        pending.player);
    }

    private void advanceDeathRetirements(long currentTick) {
        for (RuntimeEntry runtime :
                List.copyOf(runtimes.values())) {
            PendingDeathRetirement pending =
                    runtime.deathRetirement;
            if (pending == null) {
                continue;
            }
            GenerationRetirementStatus status =
                    pending.status();
            if (status
                    == GenerationRetirementStatus.PENDING) {
                if (!isExactDeathRetirementAuthority(
                        runtime, pending)) {
                    pending.failure = appendFailure(
                            pending.failure,
                            new IllegalStateException(
                                    "Death retirement lost exact authority while cleanup was pending"));
                    status =
                            GenerationRetirementStatus.UNSAFE;
                } else {
                    status = advanceDeathRetirementAttempt(
                            runtime,
                            pending,
                            currentTick);
                }
            }
            if (runtimes.get(runtime.handle.botId())
                    != runtime) {
                continue;
            }
            if (status
                            == GenerationRetirementStatus.COMPLETE
                    && finishDeathRetirement(
                            runtime, pending, currentTick)) {
                continue;
            }
            if (status
                            == GenerationRetirementStatus.UNSAFE
                    || pending.failure != null
                    || !isExactDeathRetirementAuthority(
                            runtime, pending)) {
                failDeathRetirementWithoutSave(
                        runtime,
                        pending.player,
                        null,
                        pending.failure);
            }
        }
    }

    private GenerationRetirementStatus
            advanceDeathRetirementAttempt(
                    RuntimeEntry runtime,
                    PendingDeathRetirement pending,
                    long currentTick) {
        GenerationRetirementStatus currentStatus =
                pending.status();
        if (currentStatus
                        != GenerationRetirementStatus.PENDING
                || pending.lastAdvanceTick == currentTick
                || pending.session.attempt() > 0
                        && currentTick
                                < pending.session.nextRetryTick()
                || runtime.generationRetirementInProgress) {
            return currentStatus;
        }
        pending.lastAdvanceTick = currentTick;
        runtime.generationRetirementInProgress = true;
        try {
            if (!pending.beginAttempted) {
                pending.beginAttempted = true;
                pending.failure = appendFailure(
                        pending.failure,
                        beginDeathRetirement(
                                runtime, pending));
            }

            GenerationRetirementStatus observedStatus;
            if (pending.failure != null) {
                observedStatus =
                        GenerationRetirementStatus.UNSAFE;
            } else {
                GenerationDrainStatus drainStatus =
                        actionRuntime.generationDrainStatus(
                                runtime.handle.botId(),
                                pending.generation);
                observedStatus = switch (drainStatus) {
                    case PENDING ->
                            GenerationRetirementStatus.PENDING;
                    case UNSAFE ->
                            GenerationRetirementStatus.UNSAFE;
                    case COMPLETE ->
                            finishDeathRetirementSurvival(
                                    runtime, pending)
                                    ? GenerationRetirementStatus.COMPLETE
                                    : GenerationRetirementStatus.UNSAFE;
                };
                if (drainStatus
                        == GenerationDrainStatus.UNSAFE) {
                    pending.failure = appendFailure(
                            pending.failure,
                            new IllegalStateException(
                                    "Action generation was quarantined during death retirement"));
                }
            }

            GenerationRetirementTicket ticket;
            if (pending.lastTicket == null) {
                ticket = new GenerationRetirementTicket(
                        pending.session.key(),
                        pending.session.startedTick(),
                        pending.session.deadlineTick(),
                        currentTick,
                        1);
            } else {
                ticket = pending.lastTicket.next(
                        Objects.requireNonNull(
                                pending.lastReceipt,
                                "lastReceipt"),
                        currentTick);
            }
            long progressRevision =
                    observedStatus
                                    == GenerationRetirementStatus.PENDING
                            ? pending.session.progressRevision()
                            : incrementRetirementProgress(
                                    pending.session
                                            .progressRevision());
            long nextRetryTick =
                    observedStatus
                                    == GenerationRetirementStatus.PENDING
                            ? nextRetirementTick(currentTick)
                            : -1L;
            GenerationRetirementSession.Update update =
                    pending.session.observe(
                            ticket,
                            observedStatus,
                            progressRevision,
                            nextRetryTick);
            pending.session = update.session();
            pending.lastTicket = ticket;
            pending.lastReceipt = update.receipt();
            return pending.status();
        } catch (RuntimeException exception) {
            pending.failure = appendFailure(
                    pending.failure, exception);
            return GenerationRetirementStatus.UNSAFE;
        } finally {
            runtime.generationRetirementInProgress = false;
        }
    }

    @Nullable
    private RuntimeException beginDeathRetirement(
            RuntimeEntry runtime,
            PendingDeathRetirement pending) {
        RuntimeException failure = null;
        if (pending.vanillaConsumedTicket != null) {
            try {
                long currentTick = server.getTickCount();
                closeSelfDefenseGeneration(runtime.handle.botId(),
                        pending.generation, currentTick);
                GenerationDrainStatus status = actionRuntime
                        .consumeBotGenerationForVanillaDeathNow(
                                runtime.handle.botId(),
                                pending.generation,
                                currentTick);
                if (status == GenerationDrainStatus.UNSAFE) {
                    failure = appendFailure(
                            failure,
                            new IllegalStateException(
                                    "Vanilla-death action consumption was unsafe"));
                }
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
            failure = appendDeathAuthorityFailure(
                    runtime, pending, failure);
            if (failure != null) {
                return failure;
            }
        }
        try {
            closeBotInventory(
                    runtime,
                    InventoryCloseReason.BOT_DEATH);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDeathAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        if (pending.vanillaConsumedTicket == null) {
            try {
                cancelBotActions(
                        runtime,
                        pending.generation,
                        ActionCancellationReason.LIFECYCLE);
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        failure = appendDeathAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        try {
            inputController.forceClear(
                    runtime.handle.botId(),
                    pending.generation);
            MinecraftPlayerInputAdapter.clear(
                    pending.player);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDeathAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        try {
            perceptionService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDeathAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        long currentTick = server.getTickCount();
        try {
            navigationService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation,
                    currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        try {
            safetyService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation,
                    currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        return appendDeathAuthorityFailure(
                runtime, pending, failure);
    }

    private boolean finishDeathRetirementSurvival(
            RuntimeEntry runtime,
            PendingDeathRetirement pending) {
        if (pending.survivalCloseAttempted) {
            return pending.failure == null;
        }
        pending.survivalCloseAttempted = true;
        try {
            boolean safelyClosed = pending.vanillaConsumedTicket == null
                    ? survivalSkillService.closeGeneration(
                            runtime.handle.botId(),
                            pending.generation,
                            server.getTickCount(),
                            true)
                    : survivalSkillService
                            .closeVanillaDeathConsumedGeneration(
                                    runtime.handle.botId(),
                                    pending.generation,
                                    server.getTickCount(),
                                    true);
            if (!safelyClosed) {
                pending.failure = appendFailure(
                        pending.failure,
                        new IllegalStateException(
                                "Survival layout did not produce a safe death-retirement receipt"));
            }
        } catch (RuntimeException exception) {
            pending.failure = appendFailure(
                    pending.failure, exception);
        }
        try {
            closeGenericSkillGeneration(
                    runtime.handle.botId(),
                    pending.generation,
                    server.getTickCount(),
                    "Bot death retirement closed this generation");
        } catch (RuntimeException exception) {
            pending.failure = appendFailure(
                    pending.failure, exception);
        }
        pending.failure = appendDeathAuthorityFailure(
                runtime,
                pending,
                pending.failure);
        return pending.failure == null;
    }

    private boolean finishDeathRetirement(
            RuntimeEntry runtime,
            PendingDeathRetirement pending,
            long currentTick) {
        GenerationRetirementReceipt receipt =
                pending.lastReceipt;
        if (receipt == null
                || receipt.status()
                        != GenerationRetirementStatus.COMPLETE
                || !isExactDeathRetirementAuthority(
                        runtime, pending)) {
            pending.failure = appendFailure(
                    pending.failure,
                    new IllegalStateException(
                            "Death retirement completion lost its exact receipt or authority"));
            return false;
        }
        if (!pending.player.hasDeathRetirementSaveFence()) {
            pending.failure = appendFailure(
                    pending.failure,
                    new IllegalStateException(
                            "Death-retirement save fence disappeared before completion"));
            return false;
        }
        if (pending.vanillaConsumedTicket != null
                && !commitConsumedDeathPlayerData(
                        runtime, pending, currentTick)) {
            return false;
        }
        /*
         * Release only the retirement-owned share. A separately owned persistent
         * no-save fence is allowed to remain and is intentionally inherited by the
         * authoritative respawn body; it must not turn an otherwise safe retirement
         * into a failure.
         */
        pending.player.releaseDeathRetirementSaveFence();

        runtime.completedDeathRetirement = receipt;
        runtime.deathRetirement = null;
        runtime.respawnSuppressed = false;
        runtime.respawnAtTick =
                BotPlayerConfig.AUTO_RESPAWN.get()
                        ? server.getTickCount()
                                + BotPlayerConfig.RESPAWN_DELAY_TICKS.get()
                        : -1;
        return true;
    }

    private boolean commitConsumedDeathPlayerData(
            RuntimeEntry runtime,
            PendingDeathRetirement pending,
            long currentTick) {
        if (pending.deadPlayerDataCommitted) {
            return true;
        }
        VanillaDeathTicket ticket = Objects.requireNonNull(
                pending.vanillaConsumedTicket,
                "consumed death persistence lost its ticket");
        if (pending.persistenceInProgress) {
            return false;
        }
        if (pending.persistenceRetry.exhausted(currentTick)) {
            pending.failure = appendFailure(
                    pending.failure,
                    new IllegalStateException(
                            "Vanilla-death playerdata persistence exhausted its bounded retry budget"));
            return false;
        }
        if (!pending.persistenceRetry.canAttempt(currentTick)) {
            return false;
        }

        pending.persistenceInProgress = true;
        try {
            pending.player.normalizeConsumedDeathBody(ticket);
            if (!isExactConsumedPersistenceAuthority(
                    runtime, pending, ticket)) {
                pending.failure = appendFailure(
                        pending.failure,
                        new IllegalStateException(
                                "Consumed death lost authority before playerdata commit"));
                return false;
            }
            boolean committed = deathPlayerDataCommitter.commitDead(
                    pending.player,
                    ticket,
                    () -> isExactConsumedPersistenceAuthority(
                            runtime, pending, ticket));
            if (!committed) {
                recordConsumedPersistenceFailure(
                        pending,
                        currentTick,
                        "playerdata save or readback lost exact authority",
                        null);
                return false;
            }
            deathTombstones.clear(
                    ticket.botId(), ticket.transactionId());
            if (!isExactConsumedPersistenceAuthority(
                    runtime, pending, ticket)) {
                pending.failure = appendFailure(
                        pending.failure,
                        new IllegalStateException(
                                "Consumed death changed authority after durable tombstone clearance"));
                return false;
            }
            pending.player
                    .suppressPlayerDataSaveUntilReleased();
            runtime.deathPersistenceTicket = ticket;
            pending.deadPlayerDataCommitted = true;
            return true;
        } catch (IOException | RuntimeException exception) {
            recordConsumedPersistenceFailure(
                    pending,
                    currentTick,
                    "playerdata commit failed",
                    exception);
            return false;
        } finally {
            pending.persistenceInProgress = false;
        }
    }

    private void recordConsumedPersistenceFailure(
            PendingDeathRetirement pending,
            long currentTick,
            String detail,
            @Nullable Exception cause) {
        pending.persistenceRetry =
                pending.persistenceRetry.afterFailure(currentTick);
        BotPlayer.LOGGER.error(
                "BotPlayer vanilla-death persistence attempt {}/{} failed for {}: {}",
                pending.persistenceRetry.failures(),
                DeathPersistenceRetry.MAX_ATTEMPTS,
                pending.player.getUUID(),
                detail,
                cause);
        if (pending.persistenceRetry.exhausted(currentTick)) {
            pending.failure = appendFailure(
                    pending.failure,
                    new IllegalStateException(
                            "Vanilla-death playerdata persistence exhausted its bounded retry budget",
                            cause));
        }
    }

    private boolean isExactConsumedPersistenceAuthority(
            RuntimeEntry runtime,
            PendingDeathRetirement pending,
            VanillaDeathTicket ticket) {
        if (!isExactDeathRetirementAuthority(runtime, pending)
                || pending.vanillaConsumedTicket != ticket
                || !pending.player
                        .hasExactConsumedDeathRuntimeState(ticket)) {
            return false;
        }
        return hasOnlyExactUuidBody(
                ticket.botId(), pending.player);
    }

    @Nullable
    private RuntimeException appendDeathAuthorityFailure(
            RuntimeEntry runtime,
            PendingDeathRetirement pending,
            @Nullable RuntimeException failure) {
        if (isExactDeathRetirementAuthority(
                runtime, pending)) {
            return failure;
        }
        return appendFailure(
                failure,
                new IllegalStateException(
                        "Death retirement authority changed during generation cleanup"));
    }

    private boolean isExactDeathRetirementAuthority(
            RuntimeEntry runtime,
            PendingDeathRetirement pending) {
        UUID botId = runtime.handle.botId();
        return runtimes.get(botId) == runtime
                && handlesByBot.get(botId)
                        == runtime.handle
                && runtime.state == BotLifecycleState.DEAD
                && runtime.deathRetirement == pending
                && runtime.completedDeathRetirement == null
                && runtime.handle.generation()
                        == pending.generation
                && runtime.handle.player()
                                .orElse(null)
                        == pending.player
                && pending.matchesBinding(
                        pending.player,
                        pending.listener,
                        pending.connection,
                        pending.generation)
                && !runtime.replacementHandoffInProgress
                && !runtime.handoffAborted
                && runtime.stagedCleanupGeneration < 0L
                && runtime.stagedCleanupPredecessor == null
                && runtime.stagedCleanupPlayer == null
                && runtime.respawnCandidate == null
                && runtime.disconnectingPlayer == null
                && runtime.disconnectingListener == null
                && runtime.disconnectingConnection == null
                && runtime.directDisconnectRetirement == null
                && pending.player.runtimeHandle()
                        == runtime.handle
                && pending.player.isDeadOrDying()
                && pending.player
                        .hasDeathRetirementSaveFence()
                && pending.player.connection
                        == pending.listener
                && pending.listener.player
                        == pending.player
                && pending.listener.getConnection()
                        == pending.connection
                && server.getPlayerList()
                                .getPlayer(botId)
                        == pending.player
                && pending.player.serverLevel()
                                .getPlayerByUUID(botId)
                        == pending.player
                && isBotConnectionOpen(
                        pending.player);
    }

    private boolean hasCompleteDeathRetirement(
            RuntimeEntry runtime) {
        GenerationRetirementReceipt receipt =
                runtime.completedDeathRetirement;
        return runtime.deathRetirement == null
                && receipt != null
                && receipt.status()
                        == GenerationRetirementStatus.COMPLETE
                && receipt.failure()
                        == GenerationRetirementFailure.NONE
                && receipt.attemptedTick()
                        <= receipt.deadlineTick()
                && receipt.nextRetryTick() == -1L
                && receipt.key().botId()
                        .equals(runtime.handle.botId())
                && receipt.key().generation()
                        == runtime.handle.generation()
                && receipt.key().continuation()
                        == GenerationRetirementContinuation.DEATH_RESPAWN;
    }

    private void failDeathRetirementWithoutSave(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer preferredPlayer,
            @Nullable BotServerPlayer additionalPlayer,
            @Nullable RuntimeException priorFailure) {
        if (runtimes.get(runtime.handle.botId())
                != runtime) {
            return;
        }
        if (runtime.deathRetirementFailClosedInProgress) {
            return;
        }
        runtime.deathRetirementFailClosedInProgress = true;
        try {
            revokePendingSkillCheckpointRecovery(
                    runtime, "death_fail_closed");
            runtime.respawnAtTick = -1;
            runtime.respawnFinalizeDeadlineTick = -1;
            runtime.respawnCandidate = null;
            runtime.respawnSuppressed = true;
            RuntimeException failure = priorFailure;
            long generation = runtime.deathRetirement == null
                    ? runtime.handle.generation()
                    : runtime.deathRetirement.generation;
            armImmediatelyKnownNoSaveBodies(
                    runtime,
                    preferredPlayer,
                    additionalPlayer);
            try {
                closeBotInventory(
                        runtime,
                        InventoryCloseReason.BOT_DEATH);
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
            if (generation > 0L) {
                try {
                    long currentTick = server.getTickCount();
                    closeSelfDefenseGeneration(runtime.handle.botId(),
                            generation, currentTick);
                    actionRuntime.quarantineBotGenerationNow(
                            runtime.handle.botId(),
                            generation,
                            currentTick);
                } catch (RuntimeException exception) {
                    failure = appendFailure(failure, exception);
                }
                try {
                    inputController.forceClear(
                            runtime.handle.botId(),
                            generation);
                } catch (RuntimeException exception) {
                    failure = appendFailure(failure, exception);
                }
                try {
                    perceptionService.closeGeneration(
                            runtime.handle.botId(),
                            generation);
                } catch (RuntimeException exception) {
                    failure = appendFailure(failure, exception);
                }
                try {
                    navigationService.closeGeneration(
                            runtime.handle.botId(),
                            generation,
                            server.getTickCount());
                } catch (RuntimeException exception) {
                    failure = appendFailure(failure, exception);
                }
                try {
                    safetyService.closeGeneration(
                            runtime.handle.botId(),
                            generation,
                            server.getTickCount());
                } catch (RuntimeException exception) {
                    failure = appendFailure(failure, exception);
                }
            }
            failure = appendFailure(
                    failure,
                    finalizeRuntimeTeardownWithoutSave(
                            runtime,
                            preferredPlayer,
                            additionalPlayer));
            if (failure != null) {
                BotPlayer.LOGGER.error(
                        "Death retirement failed closed without saving BotPlayer {} ({})",
                        runtime.handle.name(),
                        runtime.handle.botId(),
                        failure);
            }
        } finally {
            if (runtimes.get(runtime.handle.botId())
                    == runtime) {
                runtime.deathRetirementFailClosedInProgress = false;
            }
        }
    }

    private static long boundedRetirementDeadline(
            long startedTick) {
        return startedTick
                        > Long.MAX_VALUE
                                - BotActionRuntime.MAX_CLEANUP_TICKS
                ? Long.MAX_VALUE
                : startedTick
                        + BotActionRuntime.MAX_CLEANUP_TICKS;
    }

    private static long nextRetirementTick(
            long currentTick) {
        return currentTick == Long.MAX_VALUE
                ? -1L
                : currentTick + 1L;
    }

    private static long incrementRetirementProgress(
            long revision) {
        return revision == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : revision + 1L;
    }

    private GenerationRetirement
            retireGenerationBestEffort(
                    RuntimeEntry runtime,
                    long generation,
                    InventoryCloseReason inventoryReason) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(
                inventoryReason, "inventoryReason");
        if (generation <= 0L) {
            return new GenerationRetirement(
                    true, null);
        }
        /* Any retirement invalidates a proposal bound to the predecessor body generation. */
        closeAiProposalRequestForBot(runtime.handle.botId());
        if (runtime.generationRetirementInProgress) {
            return new GenerationRetirement(
                    false,
                    new IllegalStateException(
                            "Generation retirement is already in progress"));
        }
        runtime.generationRetirementInProgress =
                true;
        GenerationRetirement retirement;
        try {
            retirement = retireGenerationStarted(
                    runtime,
                    generation,
                    inventoryReason);
        } finally {
            runtime.generationRetirementInProgress =
                    false;
        }
        retirement = rememberGenerationRetirement(
                runtime, generation, retirement);
        captureRequestedDisconnectRetirement(
                runtime, generation, retirement);
        recordDisconnectPreparation(
                runtime, generation, retirement);
        return retirement;
    }

    private static GenerationRetirement
            rememberGenerationRetirement(
                    RuntimeEntry runtime,
                    long generation,
                    GenerationRetirement retirement) {
        if (runtime.rememberedRetirementGeneration
                > generation) {
            return retirement;
        }
        if (runtime.rememberedRetirementGeneration
                < generation) {
            runtime.rememberedRetirementGeneration =
                    generation;
            runtime.rememberedRetirementSafelyClosed =
                    retirement.safelyClosed();
            runtime.rememberedRetirementFailure =
                    retirement.failure();
        } else {
            runtime.rememberedRetirementSafelyClosed &=
                    retirement.safelyClosed();
            runtime.rememberedRetirementFailure =
                    appendFailure(
                            runtime
                                    .rememberedRetirementFailure,
                            retirement.failure());
        }
        return new GenerationRetirement(
                runtime.rememberedRetirementSafelyClosed,
                runtime.rememberedRetirementFailure);
    }

    private GenerationRetirement
            retireGenerationStarted(
                    RuntimeEntry runtime,
                    long generation,
                    InventoryCloseReason inventoryReason) {
        UUID botId = runtime.handle.botId();
        RuntimeException failure = null;
        try {
            closeBotInventory(
                    runtime, inventoryReason);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        if (runtimes.get(botId) != runtime) {
            return new GenerationRetirement(
                    false, failure);
        }
        try {
            cancelBotActions(
                    runtime,
                    generation,
                    ActionCancellationReason.LIFECYCLE);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        if (runtimes.get(botId) != runtime) {
            return new GenerationRetirement(
                    false, failure);
        }
        try {
            inputController.forceClear(
                    botId, generation);
            runtime.handle
                    .player()
                    .ifPresent(
                            MinecraftPlayerInputAdapter
                                    ::clear);
            if (runtime.stagedCleanupPlayer
                    != null) {
                MinecraftPlayerInputAdapter.clear(
                        runtime.stagedCleanupPlayer);
            }
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        if (runtimes.get(botId) != runtime) {
            return new GenerationRetirement(
                    false, failure);
        }
        try {
            perceptionService.closeGeneration(
                    botId, generation);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        if (runtimes.get(botId) != runtime) {
            return new GenerationRetirement(
                    false, failure);
        }

        boolean safelyClosed = false;
        try {
            safelyClosed =
                    closeP4Generation(
                            botId,
                            generation,
                            inventoryReason
                                    == InventoryCloseReason.SERVER_STOPPING
                                    && runtime.preserveSkillCheckpointGeneration
                                            == generation);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
            try {
                actionRuntime
                        .quarantineBotGenerationNow(
                                botId,
                                generation,
                                server.getTickCount());
            } catch (RuntimeException quarantineFailure) {
                failure = appendFailure(
                        failure,
                        quarantineFailure);
            }
            if (runtimes.get(botId)
                    == runtime) {
                try {
                    closeP4Generation(
                            botId,
                            generation,
                            false);
                } catch (RuntimeException retryFailure) {
                    failure = appendFailure(
                            failure,
                            retryFailure);
                }
            }
        }
        return new GenerationRetirement(
                safelyClosed && failure == null,
                failure);
    }

    @Nullable
    private RuntimeException finalizeRuntimeTeardown(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer preferredPlayer) {
        return finalizeRuntimeTeardown(
                runtime, preferredPlayer, false);
    }

    @Nullable
    private RuntimeException finalizeRuntimeTeardown(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer preferredPlayer,
            boolean suppressPlayerDataSave) {
        UUID botId = runtime.handle.botId();
        if (runtimes.get(botId) != runtime) {
            return null;
        }
        RuntimeException failure = null;
        try {
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }

        BotServerPlayer attached =
                runtime.handle.player().orElse(null);
        BotServerPlayer staged =
                runtime.stagedCleanupPlayer;
        long generation =
                runtime.handle.generation();
        if (!canRetainShutdownCheckpointAtFinalTeardown(
                runtime, generation, suppressPlayerDataSave)) {
            revokePendingSkillCheckpointRecovery(
                    runtime, "runtime_teardown");
            if (generation > 0L) {
                try {
                    closeGenericSkillGeneration(
                            botId,
                            generation,
                            server.getTickCount(),
                            "runtime teardown did not retain a verified shutdown checkpoint",
                            false);
                } catch (RuntimeException exception) {
                    failure = appendFailure(failure, exception);
                }
            }
        }
        runtime.handoffAborted = true;
        runtime.replacementHandoffInProgress =
                false;
        runtime.preserveSkillCheckpointGeneration = -1L;
        runtime.disconnectingGeneration = -1L;
        runtime.disconnectingPlayer = null;
        runtime.disconnectingListener = null;
        runtime.disconnectingConnection = null;
        runtime.disconnectPreparationComplete =
                false;
        runtime.disconnectPreparationSafelyClosed =
                false;
        runtime.disconnectPreparationFailure = null;
        runtime.rememberedRetirementGeneration =
                -1L;
        runtime.rememberedRetirementSafelyClosed =
                false;
        runtime.rememberedRetirementFailure = null;
        runtime.directDisconnectRetirement = null;
        runtime.deathRetirement = null;
        runtime.completedDeathRetirement = null;
        runtime.deathPersistenceTicket = null;
        runtime.successorPersistenceRetry = null;
        runtime.successorPersistenceInProgress = false;
        runtime.successorPlayerDataCommitted = false;
        runtime.deathRetirementFailClosedInProgress = false;
        runtime.stagedCleanupGeneration = -1L;
        runtime.stagedCleanupPredecessor = null;
        runtime.stagedCleanupPlayer = null;
        runtime.respawnAtTick = -1;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnCandidate = null;
        runtime.respawnSuppressed = true;
        clearAgentBinding(botId);
        runtimes.remove(botId, runtime);
        try {
            if (generation > 0L) {
                inputController.forgetBot(
                        botId, generation);
            }
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        if (attached != null) {
            runtime.handle.detach(attached);
        }

        try {
            perceptionService.closeBot(botId);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        RuntimeException removalFailure = removePlayerIdentity(
                attached, suppressPlayerDataSave);
        if (preferredPlayer != attached) {
            removalFailure = appendFailure(
                    removalFailure,
                    removePlayerIdentity(
                            preferredPlayer,
                            suppressPlayerDataSave));
        }
        if (staged != attached
                && staged != preferredPlayer) {
            removalFailure = appendFailure(
                    removalFailure,
                    removePlayerIdentity(
                            staged,
                            suppressPlayerDataSave));
        }
        failure = appendFailure(failure, removalFailure);
        return failure;
    }

    @Nullable
    private RuntimeException
            finalizeRuntimeTeardownWithoutSave(
                    RuntimeEntry runtime,
                    @Nullable BotServerPlayer preferredPlayer,
                    @Nullable BotServerPlayer additionalPlayer) {
        revokePendingSkillCheckpointRecovery(
                runtime, "no_save_teardown");
        armImmediatelyKnownNoSaveBodies(
                runtime,
                preferredPlayer,
                additionalPlayer);
        PendingNoSaveTeardown teardown =
                rememberNoSaveTeardown(
                        runtime,
                        preferredPlayer,
                        additionalPlayer);
        UUID botId = teardown.botId;
        long retiredGeneration =
                teardown.retiredGeneration;
        BotRuntimeHandle handle = teardown.handle;
        LinkedHashSet<BotServerPlayer> exactBodies =
                teardown.exactBodies;
        LinkedHashSet<ServerGamePacketListenerImpl> exactListeners =
                teardown.exactListeners;
        RuntimeException checkpointRevocationFailure = null;

        /*
         * 若 normal shutdown 的拓扑或 listener 证明后来失效，已保留的 checkpoint
         * 不能随 no-save 隔离路径逃逸；它只对那条完整、正常的保存链有效。
         */
        runtime.preserveSkillCheckpointGeneration = -1L;
        if (retiredGeneration > 0L) {
            try {
                closeGenericSkillGeneration(
                        botId,
                        retiredGeneration,
                        server.getTickCount(),
                        "no-save teardown revoked shutdown checkpoint preservation",
                        false);
            } catch (RuntimeException exception) {
                checkpointRevocationFailure = exception;
            }
        }

        for (BotServerPlayer exactBody : exactBodies) {
            exactBody.suppressPlayerDataSaveUntilReleased();
        }

        /*
         * 必须先撤销全部 listener 权威，再触发 PlayerList.remove。否则 remove/logout
         * 回调重入 disconnect 时仍可能走原版保存，把未验证的临时布局落盘。
         */
        RuntimeException failure = checkpointRevocationFailure;
        for (ServerGamePacketListenerImpl exactListener :
                exactListeners) {
            try {
                if (exactListener
                        instanceof BotGamePacketListener botListener) {
                    botListener.closeForNoSaveIsolation();
                } else {
                    failure = appendFailure(
                            failure,
                            new IllegalStateException(
                                    "No-save teardown captured a non-Bot listener"));
                }
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        if (teardown.boundDisconnectConnection != null) {
            try {
                if (teardown.boundDisconnectConnection
                        instanceof BotConnection botConnection) {
                    botConnection.markClosed();
                } else {
                    failure = appendFailure(
                            failure,
                            new IllegalStateException(
                                    "No-save teardown captured a non-Bot connection"));
                }
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        failure = appendFailure(
                failure,
                finalizeRuntimeTeardown(
                        runtime,
                        preferredPlayer,
                        true));
        for (BotServerPlayer exactBody : exactBodies) {
            failure = appendFailure(
                    failure,
                    removePlayerIdentity(
                            exactBody, true));
        }
        if (!noSaveRemovalConfirmed(
                runtime,
                handle,
                botId,
                teardown.expectedPostGeneration,
                exactBodies,
                exactListeners,
                teardown.boundDisconnectListener,
                teardown.boundDisconnectConnection)) {
            PendingNoSaveTeardown refreshed =
                    rememberNoSaveTeardown(
                            runtime, null, null);
            for (BotServerPlayer residualBody :
                    refreshed.exactBodies) {
                residualBody
                        .suppressPlayerDataSaveUntilReleased();
            }
            for (ServerGamePacketListenerImpl residualListener :
                    refreshed.exactListeners) {
                try {
                    if (residualListener
                            instanceof BotGamePacketListener botListener) {
                        botListener.closeForNoSaveIsolation();
                    }
                } catch (RuntimeException exception) {
                    failure = appendFailure(
                            failure, exception);
                }
            }
            return appendFailure(
                    failure,
                    new IllegalStateException(
                            "No-save teardown retained an exact player body or listener authority"));
        }
        for (BotServerPlayer exactBody : exactBodies) {
            exactBody.retainPersistentSavePoisonAfterRemoval();
        }
        if (retiredGeneration > 0L) {
            try {
                if (!survivalSkillService
                        .closeRemovedGenerationWithoutSave(
                                botId,
                                retiredGeneration,
                                server.getTickCount())) {
                    failure = appendFailure(
                            failure,
                            new IllegalStateException(
                                    "No-save generation removal retained an authoritative skill body"));
                }
            } catch (RuntimeException exception) {
                failure = appendFailure(
                        failure, exception);
            }
            try {
                closeGenericSkillGeneration(
                        botId,
                        retiredGeneration,
                        server.getTickCount(),
                        "No-save removal closed this generation");
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        if (failure == null) {
            runtime.pendingNoSaveTeardown = null;
        }
        return failure;
    }

    /**
     * 在建票、generation 加法和全服身份扫描之前，先给无需外部查询即可取得的
     * body 上 fence。后续建票即使抛错，这些仍可能被原版 saveAll 看见的对象
     * 也不能写入临时布局。
     */
    private void armImmediatelyKnownNoSaveBodies(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer preferredPlayer,
            @Nullable BotServerPlayer additionalPlayer) {
        UUID botId = runtime.handle.botId();
        LinkedHashSet<BotServerPlayer> bodies =
                new LinkedHashSet<>();
        addNoSaveBody(
                bodies,
                runtime.handle.player().orElse(null),
                botId);
        addNoSaveBody(bodies, preferredPlayer, botId);
        addNoSaveBody(bodies, additionalPlayer, botId);
        addNoSaveBody(
                bodies, runtime.stagedCleanupPlayer, botId);
        addNoSaveBody(
                bodies,
                runtime.stagedCleanupPredecessor,
                botId);
        addNoSaveBody(
                bodies, runtime.respawnCandidate, botId);
        addNoSaveBody(
                bodies,
                runtime.disconnectingPlayer,
                botId);
        if (runtime.disconnectingListener != null) {
            addNoSaveBody(
                    bodies,
                    runtime.disconnectingListener.player,
                    botId);
        }
        boolean expanded;
        do {
            expanded = false;
            for (BotServerPlayer body :
                    List.copyOf(bodies)) {
                int before = bodies.size();
                if (body.connection != null) {
                    addNoSaveBody(
                            bodies,
                            body.connection.player,
                            botId);
                }
                expanded |= bodies.size() != before;
            }
        } while (expanded);
        for (BotServerPlayer body : bodies) {
            body.suppressPlayerDataSaveUntilReleased();
        }
    }

    private PendingNoSaveTeardown rememberNoSaveTeardown(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer preferredPlayer,
            @Nullable BotServerPlayer additionalPlayer) {
        PendingNoSaveTeardown teardown =
                runtime.pendingNoSaveTeardown;
        UUID botId = teardown == null
                ? runtime.handle.botId()
                : teardown.botId;
        LinkedHashSet<BotServerPlayer> discoveredBodies =
                collectNoSaveBodies(
                        runtime,
                        preferredPlayer,
                        additionalPlayer,
                        botId);
        for (BotServerPlayer discoveredBody :
                discoveredBodies) {
            discoveredBody
                    .suppressPlayerDataSaveUntilReleased();
        }
        if (teardown == null) {
            BotRuntimeHandle handle = runtime.handle;
            long retiredGeneration = handle.generation();
            boolean attachedBodyWillDetach =
                    handle.player().isPresent();
            teardown = new PendingNoSaveTeardown(
                    handle.botId(),
                    retiredGeneration,
                    handle,
                    attachedBodyWillDetach
                            ? Math.incrementExact(
                                    retiredGeneration)
                            : retiredGeneration);
            runtime.pendingNoSaveTeardown = teardown;
        }
        teardown.captureRuntimeBindings(runtime);
        teardown.exactBodies.addAll(discoveredBodies);
        for (BotServerPlayer exactBody :
                teardown.exactBodies) {
            if (exactBody.connection != null) {
                teardown.exactListeners.add(
                        exactBody.connection);
            }
        }
        return teardown;
    }

    private LinkedHashSet<BotServerPlayer>
            collectNoSaveBodies(
                    RuntimeEntry runtime,
                    @Nullable BotServerPlayer preferredPlayer,
                    @Nullable BotServerPlayer additionalPlayer,
                    UUID botId) {
        LinkedHashSet<BotServerPlayer> bodies =
                new LinkedHashSet<>();
        addNoSaveBody(
                bodies,
                runtime.handle.player().orElse(null),
                botId);
        addNoSaveBody(
                bodies, preferredPlayer, botId);
        addNoSaveBody(
                bodies, additionalPlayer, botId);
        addNoSaveBody(
                bodies,
                runtime.stagedCleanupPlayer,
                botId);
        addNoSaveBody(
                bodies,
                runtime.stagedCleanupPredecessor,
                botId);
        addNoSaveBody(
                bodies,
                runtime.respawnCandidate,
                botId);
        addNoSaveBody(
                bodies,
                runtime.disconnectingPlayer,
                botId);
        if (runtime.disconnectingListener != null) {
            addNoSaveBody(
                    bodies,
                    runtime.disconnectingListener.player,
                    botId);
        }
        for (ServerPlayer player :
                server.getPlayerList().getPlayers()) {
            addNoSaveBody(bodies, player, botId);
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                addNoSaveBody(bodies, player, botId);
            }
        }

        boolean expanded;
        do {
            expanded = false;
            for (BotServerPlayer body :
                    List.copyOf(bodies)) {
                int before = bodies.size();
                if (body.connection != null) {
                    addNoSaveBody(
                            bodies,
                            body.connection.player,
                            botId);
                }
                expanded |= bodies.size() != before;
            }
        } while (expanded);
        return bodies;
    }

    private static void addNoSaveBody(
            Set<BotServerPlayer> bodies,
            @Nullable ServerPlayer candidate,
            UUID botId) {
        if (candidate instanceof BotServerPlayer bot
                && bot.getUUID().equals(botId)) {
            bodies.add(bot);
        }
    }

    private boolean noSaveRemovalConfirmed(
            RuntimeEntry runtime,
            BotRuntimeHandle handle,
            UUID botId,
            long expectedPostGeneration,
            Set<BotServerPlayer> exactBodies,
            Set<ServerGamePacketListenerImpl> exactListeners,
            @Nullable ServerGamePacketListenerImpl
                    boundDisconnectListener,
            @Nullable Connection boundDisconnectConnection) {
        if (runtimes.get(botId) == runtime
                || handle.player().isPresent()
                || handle.generation()
                        != expectedPostGeneration
                || server.getPlayerList()
                                .getPlayer(botId)
                        != null
                || server.getPlayerList()
                        .getPlayers()
                        .stream()
                        .anyMatch(player ->
                                player.getUUID()
                                        .equals(botId))) {
            return false;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getPlayerByUUID(botId) != null
                    || level.getEntity(botId) != null) {
                return false;
            }
            for (ServerPlayer candidate : level.players()) {
                if (candidate.getUUID().equals(botId)) {
                    return false;
                }
            }
        }
        for (BotServerPlayer player : exactBodies) {
            if (isPresentInAnyLevel(player)
                    || server.getPlayerList()
                                    .getPlayer(player.getUUID())
                            == player
                    || (player.connection != null
                            && (!(player.connection
                                            instanceof BotGamePacketListener
                                                    botListener)
                                    || botListener
                                            .acceptsRuntimeAuthority()))
                    || (player.connection != null
                            && (!(player.connection
                                                    .getConnection()
                                            instanceof BotConnection connection)
                                    || connection.snapshot()
                                            .open()))) {
                return false;
            }
        }
        for (ServerGamePacketListenerImpl listener :
                exactListeners) {
            if (!(listener
                            instanceof BotGamePacketListener botListener)
                    || botListener.acceptsRuntimeAuthority()
                    || !(listener.getConnection()
                            instanceof BotConnection connection)
                    || connection.snapshot().open()) {
                return false;
            }
        }
        if (boundDisconnectListener != null
                && (!(boundDisconnectListener
                                        instanceof BotGamePacketListener
                                                botListener)
                        || botListener.acceptsRuntimeAuthority())) {
            return false;
        }
        if (boundDisconnectConnection != null
                && (!(boundDisconnectConnection
                                        instanceof BotConnection connection)
                        || connection.snapshot().open())) {
            return false;
        }
        return true;
    }

    @Nullable
    private RuntimeException removePlayerIdentity(
            @Nullable BotServerPlayer player) {
        return removePlayerIdentity(player, false);
    }

    @Nullable
    private RuntimeException removePlayerIdentity(
            @Nullable BotServerPlayer player,
            boolean suppressPlayerDataSave) {
        if (player == null) {
            return null;
        }
        RuntimeException failure = null;
        try {
            if (server.getPlayerList()
                                    .getPlayer(
                                            player.getUUID())
                            == player
                    || server.getPlayerList()
                            .getPlayers()
                            .stream()
                            .anyMatch(candidate ->
                                    candidate == player)
                    || isPresentInAnyLevel(player)) {
                if (suppressPlayerDataSave) {
                    player.suppressNextPlayerDataSave();
                }
                try {
                    server.getPlayerList()
                            .remove(player);
                } finally {
                    player.clearPlayerDataSaveSuppression();
                }
            }
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        try {
            MinecraftPlayerInputAdapter.clear(player);
            if (suppressPlayerDataSave
                    && player.connection
                            instanceof BotGamePacketListener botListener) {
                botListener.closeForNoSaveIsolation();
            }
            if (player.connection != null
                    && player.connection
                                    .getConnection()
                            instanceof BotConnection connection) {
                connection.markClosed();
            }
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        return failure;
    }

    private void rollbackFailedSpawn(
            BotServerPlayer player, BotConnection connection, RuntimeEntry runtime) {
        if (runtime.state != BotLifecycleState.DESPAWNING) {
            transition(runtime, BotLifecycleState.DESPAWNING);
        }
        boolean isRegistered = server.getPlayerList().getPlayer(player.getUUID()) == player;
        boolean isInLevel =
                player.serverLevel().getPlayerByUUID(player.getUUID()) == player;
        if ((isRegistered || isInLevel) && player.connection != null) {
            player.connection.disconnect(Component.literal("BotPlayer spawn failed"));
        } else if (isRegistered || isInLevel) {
            server.getPlayerList().remove(player);
        }
        connection.markClosed();
        perceptionService.closeBot(runtime.handle.botId());
        closeP4Generation(
                runtime.handle.botId(),
                runtime.handle.generation(),
                false);
        clearPlayerInput(runtime, true);
        runtime.handle.detach(player);
        runtimes.remove(runtime.handle.botId());
    }

    private void closeFailedShutdownRuntime(RuntimeEntry runtime) {
        RuntimeException fallbackFailure;
        try {
            fallbackFailure =
                    finalizeRuntimeTeardownWithoutSave(
                            runtime,
                            runtime.handle.player().orElse(null),
                            null);
        } catch (RuntimeException exception) {
            fallbackFailure = exception;
        }
        if (fallbackFailure != null) {
            BotPlayer.LOGGER.error(
                    "No-save fallback removal also failed for BotPlayer {} ({})",
                    runtime.handle.name(),
                    runtime.handle.botId(),
                    fallbackFailure);
        }
    }

    private void revalidateInventorySessions() {
        for (RuntimeEntry runtime :
                List.copyOf(runtimes.values())) {
            BotInventorySession session = inventorySessions
                    .sessionForBot(runtime.handle.botId())
                    .orElse(null);
            if (session == null) {
                continue;
            }
            InventorySessionToken token = session.token();
            if (!inventorySessions.revalidate(token).valid()) {
                closeInventorySession(
                        token, InventoryCloseReason.VIEWER_INVALID);
            }
        }
    }

    private void closeBotInventory(
            RuntimeEntry runtime, InventoryCloseReason reason) {
        BotInventorySession session = inventorySessions
                .sessionForBot(runtime.handle.botId())
                .orElse(null);
        if (session != null) {
            closeInventorySession(session.token(), reason);
        }
    }

    private void closeViewerInventory(
            UUID viewerId, InventoryCloseReason reason) {
        BotInventorySession session =
                inventorySessions
                        .sessionForViewer(viewerId)
                        .orElse(null);
        if (session != null) {
            closeInventorySession(session.token(), reason);
        }
    }

    private void closeAllInventories(
            InventoryCloseReason reason) {
        List<InventorySessionToken> requested =
                inventorySessions.forceCloseAll(reason);
        for (InventorySessionToken token : requested) {
            closeInventorySession(token, reason);
        }
        for (ServerPlayer player :
                List.copyOf(server.getPlayerList().getPlayers())) {
            BotInventorySession session = inventorySessions
                    .sessionForViewer(player.getUUID())
                    .orElse(null);
            if (session != null) {
                closeInventorySession(session.token(), reason);
            }
        }
    }

    private void closeInventorySession(
            InventorySessionToken token,
            InventoryCloseReason reason) {
        BotInventorySessionManager.ForceCloseStatus status =
                inventorySessions.forceClose(token, reason);
        if (status
                        == BotInventorySessionManager.ForceCloseStatus
                                .NOT_FOUND
                || status
                        == BotInventorySessionManager.ForceCloseStatus
                                .ALREADY_CLOSED) {
            return;
        }

        ServerPlayer viewer =
                server.getPlayerList().getPlayer(token.viewerId());
        if (viewer != null
                && viewer.containerMenu instanceof BotInventoryMenu menu
                && menu.sessionToken().filter(token::equals).isPresent()) {
            viewer.closeContainer();
        }
        inventorySessions.confirmClosed(token);
    }

    private void failInventoryOpen(
            InventorySessionToken token,
            ServerPlayer viewer) {
        inventorySessions.failOpen(token);
        if (viewer.containerMenu
                        instanceof BotInventoryMenu menu
                && menu.sessionToken()
                        .filter(token::equals)
                        .isPresent()) {
            viewer.closeContainer();
        }
    }

    private boolean canWriteBotInventory(
            UUID botId, UUID viewerId) {
        ServerPlayer viewer =
                server.getPlayerList().getPlayer(viewerId);
        if (viewer == null || viewer instanceof BotServerPlayer) {
            return false;
        }
        boolean owner = roster.findById(botId)
                .flatMap(BotProfile::ownerId)
                .filter(viewerId::equals)
                .isPresent();
        return owner
                || server.getPlayerList()
                        .isOp(viewer.getGameProfile());
    }

    private InventoryLifecycleValidator.LifecycleStatus
            validateInventoryLifecycle(
                    UUID botId, long expectedGeneration) {
        return switch (inspectActionTarget(
                        botId, expectedGeneration)
                .status()) {
            case ACTIVE ->
                    InventoryLifecycleValidator.LifecycleStatus.ACTIVE;
            case UNKNOWN_BOT ->
                    InventoryLifecycleValidator.LifecycleStatus.UNKNOWN_BOT;
            case NOT_ACTIVE ->
                    InventoryLifecycleValidator.LifecycleStatus.NOT_ACTIVE;
            case STALE_GENERATION ->
                    InventoryLifecycleValidator.LifecycleStatus
                            .STALE_GENERATION;
            case INVALID_INSTANCE ->
                    InventoryLifecycleValidator.LifecycleStatus
                            .INVALID_INSTANCE;
            case SERVER_STOPPING ->
                    InventoryLifecycleValidator.LifecycleStatus
                            .SERVER_STOPPING;
        };
    }

    private InventoryDistanceValidator.SpatialStatus
            validateInventoryDistance(UUID botId, UUID viewerId) {
        ServerPlayer viewer =
                server.getPlayerList().getPlayer(viewerId);
        if (viewer == null
                || viewer instanceof BotServerPlayer
                || !viewer.isAlive()
                || viewer.isDeadOrDying()) {
            return InventoryDistanceValidator.SpatialStatus
                    .VIEWER_NOT_ALIVE;
        }
        RuntimeEntry runtime = runtimes.get(botId);
        BotServerPlayer bot = runtime == null
                ? null
                : runtime.handle.player().orElse(null);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE
                || bot == null
                || !bot.isAlive()
                || bot.isDeadOrDying()
                || !isListenerAuthority(bot)) {
            return InventoryDistanceValidator.SpatialStatus
                    .BOT_NOT_ALIVE;
        }
        if (viewer.serverLevel() != bot.serverLevel()) {
            return InventoryDistanceValidator.SpatialStatus
                    .DIFFERENT_DIMENSION;
        }
        double maximumDistance =
                BotPlayerConfig.INVENTORY_VIEW_DISTANCE.get();
        return viewer.distanceToSqr(bot)
                        <= maximumDistance * maximumDistance
                ? InventoryDistanceValidator.SpatialStatus.IN_RANGE
                : InventoryDistanceValidator.SpatialStatus.OUT_OF_RANGE;
    }

    private void beginRespawnAttempt(
            RuntimeEntry runtime, int currentTick) {
        runtime.handle.player()
                .ifPresent(BotServerPlayer
                        ::suppressPlayerDataSaveUntilReleased);
        transition(runtime, BotLifecycleState.RESPAWNING);
        runtime.respawnAtTick = -1;
        runtime.respawnCandidate = null;
        runtime.respawnAttempts++;
        if (runtime.deathPersistenceTicket == null) {
            runtime.respawnFinalizeDeadlineTick =
                    currentTick + RESPAWN_FINALIZE_TIMEOUT_TICKS;
            runtime.successorPersistenceRetry = null;
        } else {
            long deadline = boundedRetirementDeadline(
                    currentTick);
            runtime.respawnFinalizeDeadlineTick =
                    (int) Math.min(
                            Integer.MAX_VALUE, deadline);
            runtime.successorPersistenceRetry =
                    DeathPersistenceRetry.open(
                            currentTick, deadline);
        }
        runtime.successorPlayerDataCommitted = false;
    }

    private boolean finalizeRespawnIfAuthoritative(
            RuntimeEntry runtime, int currentTick) {
        if (runtime.state != BotLifecycleState.RESPAWNING
                || hasRequestedListenerDisconnect(
                        runtime)) {
            return false;
        }
        BotServerPlayer oldPlayer =
                runtime.handle.player().orElse(null);
        if (!hasCompleteDeathRetirement(runtime)
                || oldPlayer == null
                || oldPlayer.connection == null
                || !oldPlayer.isDeadOrDying()
                || oldPlayer.hasDeathRetirementSaveFence()) {
            failDeathRetirementWithoutSave(
                    runtime,
                    oldPlayer,
                    runtime.respawnCandidate,
                    new IllegalStateException(
                            "Respawn finalization lacked its complete death-retirement authority"));
            return false;
        }
        ServerPlayer listenerPlayer = oldPlayer.connection.player;
        if (listenerPlayer == oldPlayer) {
            return false;
        }
        if (!(listenerPlayer instanceof BotServerPlayer replacement)) {
            failDeathRetirementWithoutSave(
                    runtime,
                    oldPlayer,
                    runtime.respawnCandidate,
                    new IllegalStateException(
                            "Respawn listener selected a non-BotPlayer body"));
            return false;
        }
        if (replacement == oldPlayer
                || replacement.runtimeHandle() != runtime.handle
                || replacement.connection != oldPlayer.connection
                || replacement.connection.player != replacement
                || !replacement.isAlive()
                || replacement.isDeadOrDying()
                || replacement.hasDeathRetirementSaveFence()
                || server.getPlayerList().getPlayer(replacement.getUUID())
                        != replacement
                || replacement.serverLevel()
                                .getPlayerByUUID(replacement.getUUID())
                        != replacement
                || (runtime.respawnCandidate != null
                        && runtime.respawnCandidate != replacement)) {
            failDeathRetirementWithoutSave(
                    runtime,
                    oldPlayer,
                    replacement,
                    new IllegalStateException(
                            "Respawn replacement failed final authority validation"));
            return false;
        }

        VanillaDeathTicket persistenceTicket =
                runtime.deathPersistenceTicket;
        if (persistenceTicket != null
                && !commitRespawnSuccessorPlayerData(
                        runtime,
                        oldPlayer,
                        replacement,
                        persistenceTicket,
                        currentTick)) {
            return false;
        }

        clearPlayerInput(runtime, false);
        if (runtime.state != BotLifecycleState.RESPAWNING
                || !hasCompleteDeathRetirement(runtime)
                || runtime.handle.player().orElse(null)
                        != oldPlayer
                || oldPlayer.connection == null
                || oldPlayer.connection.player != replacement
                || oldPlayer.hasDeathRetirementSaveFence()
                || replacement.runtimeHandle() != runtime.handle
                || replacement.connection != oldPlayer.connection
                || !replacement.isAlive()
                || replacement.isDeadOrDying()
                || replacement.hasDeathRetirementSaveFence()
                || server.getPlayerList().getPlayer(replacement.getUUID())
                        != replacement
                || replacement.serverLevel()
                                .getPlayerByUUID(replacement.getUUID())
                        != replacement
                || (runtime.respawnCandidate != null
                        && runtime.respawnCandidate != replacement)) {
            failDeathRetirementWithoutSave(
                    runtime,
                    oldPlayer,
                    replacement,
                    new IllegalStateException(
                            "Respawn authority changed immediately before attachment"));
            return false;
        }
        if (persistenceTicket != null) {
            replacement.clearExactDeathHandoffTicket(
                    persistenceTicket);
        }
        replacement.releaseInheritedPersistentSaveFence();
        runtime.handle.attach(replacement);
        MinecraftPlayerInputAdapter.clear(replacement);
        transition(runtime, BotLifecycleState.ACTIVE);
        perceptionService.activate(
                runtime.handle.botId(),
                runtime.handle.generation());
        runtime.respawnAtTick = -1;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnCandidate = null;
        runtime.respawnAttempts = 0;
        runtime.respawnSuppressed = false;
        runtime.completedDeathRetirement = null;
        runtime.deathPersistenceTicket = null;
        runtime.successorPersistenceRetry = null;
        runtime.successorPlayerDataCommitted = false;
        return true;
    }

    private boolean commitRespawnSuccessorPlayerData(
            RuntimeEntry runtime,
            BotServerPlayer predecessor,
            BotServerPlayer successor,
            VanillaDeathTicket ticket,
            long currentTick) {
        if (runtime.successorPlayerDataCommitted) {
            return true;
        }
        DeathPersistenceRetry retry =
                runtime.successorPersistenceRetry;
        if (retry == null) {
            failDeathRetirementWithoutSave(
                    runtime,
                    predecessor,
                    successor,
                    new IllegalStateException(
                            "Respawn successor persistence lost its retry owner"));
            return false;
        }
        if (runtime.successorPersistenceInProgress
                || !retry.canAttempt(currentTick)) {
            return false;
        }

        runtime.successorPersistenceInProgress = true;
        try {
            successor.applyDeathHandoffToSuccessor(ticket);
            boolean committed = deathPlayerDataCommitter
                    .commitSuccessor(
                            successor,
                            ticket,
                            () -> isExactRespawnPersistenceAuthority(
                                    runtime,
                                    predecessor,
                                    successor,
                                    ticket));
            if (committed) {
                runtime.successorPlayerDataCommitted = true;
                return true;
            }
            return recordRespawnPersistenceFailure(
                    runtime,
                    predecessor,
                    successor,
                    currentTick,
                    null);
        } catch (IOException | RuntimeException exception) {
            return recordRespawnPersistenceFailure(
                    runtime,
                    predecessor,
                    successor,
                    currentTick,
                    exception);
        } finally {
            runtime.successorPersistenceInProgress = false;
        }
    }

    private boolean recordRespawnPersistenceFailure(
            RuntimeEntry runtime,
            BotServerPlayer predecessor,
            BotServerPlayer successor,
            long currentTick,
            @Nullable Exception cause) {
        DeathPersistenceRetry retry = Objects.requireNonNull(
                runtime.successorPersistenceRetry,
                "respawn persistence retry owner");
        runtime.successorPersistenceRetry =
                retry.afterFailure(currentTick);
        BotPlayer.LOGGER.error(
                "BotPlayer respawn playerdata attempt {}/{} failed for {}",
                runtime.successorPersistenceRetry.failures(),
                DeathPersistenceRetry.MAX_ATTEMPTS,
                successor.getUUID(),
                cause);
        if (runtime.successorPersistenceRetry
                .exhausted(currentTick)) {
            failDeathRetirementWithoutSave(
                    runtime,
                    predecessor,
                    successor,
                    new IllegalStateException(
                            "Respawn successor playerdata persistence exhausted its bounded retry budget",
                            cause));
        }
        return false;
    }

    private boolean isExactRespawnPersistenceAuthority(
            RuntimeEntry runtime,
            BotServerPlayer predecessor,
            BotServerPlayer successor,
            VanillaDeathTicket ticket) {
        if (runtimes.get(ticket.botId()) != runtime
                || handlesByBot.get(ticket.botId())
                        != runtime.handle
                || runtime.state != BotLifecycleState.RESPAWNING
                || runtime.deathPersistenceTicket != ticket
                || runtime.handle.player().orElse(null)
                        != predecessor
                || predecessor.connection == null
                || predecessor.connection.player != successor
                || successor.runtimeHandle() != runtime.handle
                || successor.connection
                        != predecessor.connection
                || server.getPlayerList().getPlayer(ticket.botId())
                        != successor
                || successor.serverLevel().getPlayerByUUID(
                                ticket.botId())
                        != successor
                || isPresentInAnyLevel(predecessor)
                || !hasOnlyExactUuidBody(
                        ticket.botId(), successor)
                || !successor
                        .hasExactSuccessorRuntimeState(ticket)) {
            return false;
        }
        return true;
    }

    private void failRespawnAttempt(
            RuntimeEntry runtime,
            int currentTick,
            String safeReason) {
        if (runtime.state != BotLifecycleState.RESPAWNING) {
            return;
        }
        BotPlayer.LOGGER.error(
                "BotPlayer respawn attempt {} failed for {} ({}): {}",
                runtime.respawnAttempts,
                runtime.handle.name(),
                runtime.handle.botId(),
                safeReason);
        BotServerPlayer oldPlayer =
                runtime.handle.player().orElse(null);
        BotServerPlayer successor =
                knownDeathSuccessor(runtime, oldPlayer);
        if (successor != null) {
            failDeathRetirementWithoutSave(
                    runtime,
                    oldPlayer,
                    successor,
                    new IllegalStateException(
                            "Respawn failed after a successor body was partially installed: "
                                    + safeReason));
            return;
        }
        runtime.respawnCandidate = null;
        runtime.respawnFinalizeDeadlineTick = -1;
        transition(runtime, BotLifecycleState.DEAD);

        boolean retryable =
                oldPlayer != null
                        && isAuthoritativeInstance(
                                runtime, oldPlayer, false)
                        && oldPlayer.isDeadOrDying()
                        && oldPlayer.connection != null;
        if (retryable
                && runtime.respawnAttempts < MAX_RESPAWN_ATTEMPTS
                && BotPlayerConfig.AUTO_RESPAWN.get()) {
            runtime.respawnAtTick =
                    currentTick + RESPAWN_RETRY_DELAY_TICKS;
            return;
        }
        runtime.respawnAtTick = -1;
        runtime.respawnSuppressed = true;
    }

    private void cancelBotActions(
            RuntimeEntry runtime,
            long throughGeneration,
            ActionCancellationReason reason) {
        if (throughGeneration <= 0) {
            return;
        }
        long currentTick = server.getTickCount();
        closeSelfDefenseGeneration(runtime.handle.botId(), throughGeneration,
                currentTick);
        actionRuntime.cancelBotNow(
                runtime.handle.botId(),
                throughGeneration,
                reason,
                currentTick);
    }

    /**
     * A self-defense melee has two owner-thread layers above the Action
     * runtime. Close the technique first so it can retract and cancel its
     * exact immutable child, then close the limited-defense session before an
     * Action generation is drained or quarantined.
     */
    private void closeSelfDefenseGeneration(
            UUID botId, long generation, long currentTick) {
        techniqueLifecycleCoordinator.closeGeneration(
                botId, generation, currentTick);
        selfDefenseSkillService.closeGeneration(
                botId, generation, currentTick);
    }

    private boolean closeP4Generation(
            UUID botId,
            long generation,
            boolean preserveSafeCheckpoint) {
        if (generation <= 0L) {
            return true;
        }
        long currentTick = server.getTickCount();
        boolean safelyClosed = false;
        RuntimeException failure = null;
        try {
            safelyClosed =
                    survivalSkillService.closeGeneration(
                            botId,
                            generation,
                            currentTick,
                            actionRuntime
                                    .isGenerationSafe(
                                            botId,
                                            generation));
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        try {
            closeGenericSkillGeneration(
                    botId,
                    generation,
                    currentTick,
                    "Bot lifecycle closed this generation",
                    preserveSafeCheckpoint);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        try {
            techniqueLifecycleCoordinator.closeGeneration(
                    botId, generation, currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        try {
            selfDefenseSkillService.closeGeneration(
                    botId, generation, currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        try {
            navigationService.closeGeneration(
                    botId, generation, currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        try {
            safetyService.closeGeneration(
                    botId, generation, currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        if (failure != null) {
            throw failure;
        }
        return safelyClosed && isP5GenerationQuiescent(botId, generation);
    }

    private void closeGenericSkillGeneration(
            UUID botId,
            long generation,
            long currentTick,
            String reason) {
        closeGenericSkillGeneration(
                botId, generation, currentTick, reason, false);
    }

    /**
     * 关闭瞬态运行时，同时只在经过正常停服路径时保留已经落盘的中央安全 checkpoint。
     * 断线、死亡、异常回滚和 no-save 隔离绝不能带走任何可恢复声明。
     */
    private void closeGenericSkillGeneration(
            UUID botId,
            long generation,
            long currentTick,
            String reason,
            boolean preserveSafeCheckpoint) {
        safetyPausedSkillRuns.computeIfPresent(
                botId,
                (ignored, paused) -> paused.generation() == generation
                        ? null
                        : paused);
        skillRuntime.closeGeneration(
                botId, generation, currentTick, reason);
        if (!preserveSafeCheckpoint) {
            if (!terminalizeSkillCheckpointGeneration(
                    botId, generation, currentTick)
                    && checkpointStoreWritable()) {
                throw new IllegalStateException(
                        "P5A checkpoint terminal tombstone could not be committed");
            }
            skillCheckpointRevisions.remove(botId);
            recoveredSkillCheckpointLineagesByRun.entrySet().removeIf(entry ->
                    entry.getValue().prior().botId().equals(botId)
                            && entry.getValue().prior().generation()
                                    <= generation);
        }
    }

    /**
     * pending source 是一次尚未接管完成的旧 body 恢复意图，不是当前 generation 的
     * 运行权限。任何死亡、异常断线、no-save 或未获正常停服回执的路径都必须先清掉
     * 它，并且只关闭 source 中记录的原 generation；不能用当前 generation 的上界
     * 清理，以免迟到 predecessor 波及已经 ACTIVE 的 successor。
     */
    private void revokePendingSkillCheckpointRecovery(
            RuntimeEntry runtime, String safeReason) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(safeReason, "safeReason");
        SkillCheckpointRecoverySource source =
                runtime.pendingSkillCheckpointRecovery;
        runtime.pendingSkillCheckpointRecovery = null;
        runtime.nextSkillCheckpointRecoveryAttemptTick = 0L;
        runtime.lastSkillCheckpointRecoveryOutcome =
                "revoked_" + safeReason;
        if (source == null) {
            return;
        }
        SkillCheckpoint checkpoint = source.checkpoint().orElse(null);
        if (checkpoint == null
                || !checkpoint.botId().equals(runtime.handle.botId())
                || !checkpoint.playerId().equals(runtime.handle.botId())
                || !checkpoint.serverInstanceId().equals(
                        roster.serverInstanceId())
                || checkpoint.generation() <= 0L) {
            return;
        }
        try {
            closeGenericSkillGeneration(
                    checkpoint.botId(),
                    checkpoint.generation(),
                    server.getTickCount(),
                    "pending checkpoint recovery revoked: " + safeReason,
                    false);
        } catch (RuntimeException exception) {
            /*
             * 上层 teardown 仍会继续 no-save 隔离；不能让撤销失败保留 pending 引用
             * 并在稍后的 Tick 把同一恢复意图重新入队。
             */
            BotPlayer.LOGGER.error(
                    "Could not revoke pending P5A checkpoint recovery for BotPlayer {} ({})",
                    runtime.handle.name(),
                    runtime.handle.botId(),
                    exception);
        }
    }

    /**
     * 正常停服的 checkpoint 仅在 listener 已带着精确、成功的 pre-save receipt 返回
     * 时才能穿过 final teardown。任何更早的重入 disconnect 都走 false 分支，删除
     * 旧记录而不是把“曾经安全”的 snapshot 错当作本次保存已验证。
     */
    private boolean canRetainShutdownCheckpointAtFinalTeardown(
            RuntimeEntry runtime,
            long generation,
            boolean suppressPlayerDataSave) {
        return !suppressPlayerDataSave
                && stopping
                && generation > 0L
                && runtime.preserveSkillCheckpointGeneration == generation
                && runtime.disconnectingGeneration == generation
                && runtime.disconnectingPlayer != null
                && runtime.disconnectingListener != null
                && runtime.disconnectingConnection != null
                && runtime.disconnectPreparationComplete
                && runtime.disconnectPreparationSafelyClosed
                && runtime.disconnectPreparationFailure == null;
    }

    private void clearPlayerInput(
            RuntimeEntry runtime, boolean forget) {
        long generation = runtime.handle.generation();
        if (generation <= 0) {
            return;
        }
        inputController.forceClear(
                runtime.handle.botId(), generation);
        runtime.handle
                .player()
                .ifPresent(MinecraftPlayerInputAdapter::clear);
        if (forget) {
            inputController.forgetBot(
                    runtime.handle.botId(), generation);
        }
    }

    private boolean transition(
            RuntimeEntry runtime, BotLifecycleState next) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(next, "next");
        BotLifecycleState previous = runtime.state;
        if (previous == next) {
            return false;
        }
        previous.requireTransitionTo(next);
        runtime.state = next;
        long generation = runtime.handle.generation();
        if (generation > 0) {
            if (lifecycleHistory.size()
                    == LIFECYCLE_HISTORY_CAPACITY) {
                lifecycleHistory.removeFirst();
            }
            lifecycleHistory.addLast(new BotLifecycleTransition(
                    runtime.handle.botId(),
                    generation,
                    previous,
                    next,
                    server.getTickCount()));
        }
        return true;
    }

    private boolean isAuthoritativeInstance(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer player,
            boolean requireAlive) {
        return player != null
                && handlesByBot.get(runtime.handle.botId())
                        == runtime.handle
                && player.runtimeHandle() == runtime.handle
                && (!requireAlive || !player.isDeadOrDying())
                && server.getPlayerList()
                                .getPlayer(runtime.handle.botId())
                        == player
                && player.serverLevel()
                                .getPlayerByUUID(runtime.handle.botId())
                        == player
                && hasOnlyExactUuidBody(
                        runtime.handle.botId(), player)
                && isListenerAuthority(player);
    }

    private boolean isStagedCleanupAuthority(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer player) {
        BotServerPlayer predecessor =
                runtime.stagedCleanupPredecessor;
        boolean boundDisconnectTarget =
                isBoundListenerDisconnectTarget(
                        runtime, player);
        boolean requestedRetirementTarget =
                isRequestedRetirementCleanupTarget(
                        runtime, player);
        return player != null
                && predecessor != null
                && (runtime.state
                                == BotLifecycleState.ACTIVE
                        || boundDisconnectTarget
                        || requestedRetirementTarget)
                && handlesByBot.get(
                                runtime.handle.botId())
                        == runtime.handle
                && player.runtimeHandle()
                        == runtime.handle
                && predecessor.runtimeHandle()
                        == runtime.handle
                && runtime.handle.player()
                                .orElse(null)
                        == predecessor
                && predecessor.getUUID()
                        .equals(
                                runtime.handle.botId())
                && player.getUUID()
                        .equals(
                                runtime.handle.botId())
                && predecessor.connection != null
                && predecessor.connection
                        == player.connection
                && predecessor.connection.player
                        == player
                && server.getPlayerList()
                                .getPlayer(
                                        runtime.handle
                                                .botId())
                        == player
                && player.serverLevel()
                                .getPlayerByUUID(
                                        runtime.handle
                                                .botId())
                        == player
                && !isPresentInAnyLevel(
                        predecessor)
                && (boundDisconnectTarget
                        || requestedRetirementTarget
                        || (isListenerAuthority(player)
                                && isBotConnectionOpen(
                                        player)));
    }

    private boolean stageDisconnectCleanupTarget(
            RuntimeEntry runtime,
            BotServerPlayer player,
            long generation) {
        BotServerPlayer predecessor =
                runtime.handle.player().orElse(null);
        if (generation <= 0L
                || predecessor == null
                || predecessor == player
                || handlesByBot.get(
                                runtime.handle.botId())
                        != runtime.handle
                || runtime.handle.generation()
                        != generation
                || player.runtimeHandle()
                        != runtime.handle
                || predecessor.runtimeHandle()
                        != runtime.handle
                || !player.getUUID()
                        .equals(runtime.handle.botId())
                || predecessor.connection == null
                || predecessor.connection
                        != player.connection
                || predecessor.connection.player
                        != player
                || server.getPlayerList()
                                .getPlayer(
                                        runtime.handle
                                                .botId())
                        != player
                || player.serverLevel()
                                .getPlayerByUUID(
                                        runtime.handle
                                                .botId())
                        != player
                || isPresentInAnyLevel(predecessor)
                || !isBoundListenerDisconnectTarget(
                        runtime, player)) {
            return false;
        }
        runtime.replacementHandoffInProgress =
                true;
        runtime.stagedCleanupGeneration = generation;
        runtime.stagedCleanupPredecessor =
                predecessor;
        runtime.stagedCleanupPlayer = player;
        return isStagedCleanupAuthority(
                runtime, player);
    }

    private static boolean bindListenerDisconnect(
            RuntimeEntry runtime,
            BotServerPlayer player,
            ServerGamePacketListenerImpl listener,
            Connection connection,
            long generation) {
        if (generation < 0L) {
            return false;
        }
        if (runtime.disconnectingPlayer == null) {
            runtime.disconnectingPlayer = player;
            runtime.disconnectingListener = listener;
            runtime.disconnectingConnection =
                    connection;
            runtime.disconnectingGeneration =
                    generation;
            return true;
        }
        return runtime.disconnectingPlayer == player
                && runtime.disconnectingListener
                        == listener
                && runtime.disconnectingConnection
                        == connection
                && runtime.disconnectingGeneration
                        == generation;
    }

    private static boolean
            isPreparedListenerDisconnect(
                    RuntimeEntry runtime,
                    BotServerPlayer player,
                    ServerGamePacketListenerImpl listener,
                    Connection connection,
                    long generation) {
        return runtime.disconnectPreparationComplete
                && runtime.disconnectingPlayer == player
                && runtime.disconnectingListener
                        == listener
                && runtime.disconnectingConnection
                        == connection
                && runtime.disconnectingGeneration
                        == generation;
    }

    private boolean prepareListenerDisconnect(
            RuntimeEntry runtime,
            BotServerPlayer player,
            long generation,
            GenerationRetirement retirement) {
        if (runtime.directDisconnectRetirement != null
                || generation <= 0L
                || runtimes.get(
                                runtime.handle.botId())
                        != runtime
                || handlesByBot.get(
                                runtime.handle.botId())
                        != runtime.handle
                || player.runtimeHandle()
                        != runtime.handle
                || !player.getUUID()
                        .equals(runtime.handle.botId())
                || !(player.connection
                        instanceof BotGamePacketListener
                                listener)
                || listener.player != player
                || server.getPlayerList()
                                .getPlayer(
                                        runtime.handle
                                                .botId())
                        != player
                || player.serverLevel()
                                .getPlayerByUUID(
                                        runtime.handle
                                                .botId())
                        != player
                || !isExactDisconnectGenerationTarget(
                        runtime, player, generation)
                || !isBotConnectionOpen(player)
                || !bindListenerDisconnect(
                        runtime,
                        player,
                        listener,
                        listener.getConnection(),
                        generation)) {
            return false;
        }
        recordDisconnectPreparation(
                runtime, generation, retirement);
        return true;
    }

    private void captureRequestedDisconnectRetirement(
            RuntimeEntry runtime,
            long generation,
            GenerationRetirement retirement) {
        if (runtime.directDisconnectRetirement != null) {
            return;
        }
        BotServerPlayer staged =
                runtime.stagedCleanupPlayer;
        if (listenerDisconnectRequested(staged)
                && prepareListenerDisconnect(
                        runtime,
                        staged,
                        generation,
                        retirement)) {
            return;
        }
        BotServerPlayer current =
                runtime.handle.player().orElse(null);
        if (listenerDisconnectRequested(current)) {
            prepareListenerDisconnect(
                    runtime,
                    current,
                    generation,
                    retirement);
        }
    }

    private boolean
            isExactDisconnectGenerationTarget(
                    RuntimeEntry runtime,
                    BotServerPlayer player,
                    long generation) {
        if (runtime.handle.generation()
                != generation) {
            return false;
        }
        BotServerPlayer current =
                runtime.handle.player().orElse(null);
        if (current == player) {
            return true;
        }
        return runtime
                                .replacementHandoffInProgress
                        && runtime.stagedCleanupGeneration
                                == generation
                        && runtime.stagedCleanupPlayer
                                == player
                        && runtime.stagedCleanupPredecessor
                                == current
                        && current != null
                        && current.connection
                                == player.connection
                        && current.runtimeHandle()
                                == runtime.handle
                        && !isPresentInAnyLevel(
                                current);
    }

    private boolean isRespawnDisconnectTransition(
            RuntimeEntry runtime,
            BotServerPlayer predecessor,
            ServerGamePacketListenerImpl listener,
            @Nullable ServerPlayer listedPlayer) {
        if (!(listedPlayer
                instanceof BotServerPlayer successor)) {
            return false;
        }
        return successor != predecessor
                && runtime.handle.player()
                                .orElse(null)
                        == predecessor
                && predecessor.runtimeHandle()
                        == runtime.handle
                && successor.runtimeHandle()
                        == runtime.handle
                && successor.getUUID()
                        .equals(runtime.handle.botId())
                && predecessor.connection
                        == listener
                && successor.connection
                        == listener
                && listener.player
                        == predecessor
                && successor.serverLevel()
                                .getPlayerByUUID(
                                        runtime.handle
                                                .botId())
                        == successor
                && !isPresentInAnyLevel(
                        predecessor);
    }

    private void abortUnstableListenerDisconnect(
            RuntimeEntry runtime,
            BotServerPlayer listenerPlayer,
            @Nullable ServerPlayer listedPlayer,
            String reason) {
        runtime.handoffAborted = true;
        runtime.respawnAtTick = -1;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnSuppressed = true;
        RuntimeException failure = null;
        try {
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        GenerationRetirement retirement =
                retireGenerationBestEffort(
                        runtime,
                        runtime.handle.generation(),
                        InventoryCloseReason
                                .BOT_UNLOADED);
        failure = appendFailure(
                failure,
                retirement.failure());
        BotServerPlayer listedBot =
                listedPlayer
                                instanceof BotServerPlayer candidate
                        && candidate.runtimeHandle()
                                == runtime.handle
                        ? candidate
                        : null;
        failure = appendFailure(
                failure,
                finalizeRuntimeTeardownWithoutSave(
                        runtime,
                        listenerPlayer,
                        listedBot));
        BotPlayer.LOGGER.error(
                "{}; BotPlayer {} ({}) was failed closed without invoking vanilla disconnect (generation safely closed: {})",
                reason,
                runtime.handle.name(),
                runtime.handle.botId(),
                retirement.safelyClosed(),
                failure);
    }

    private static void recordDisconnectPreparation(
            RuntimeEntry runtime,
            long generation,
            GenerationRetirement retirement) {
        if (runtime.directDisconnectRetirement != null) {
            return;
        }
        if (runtime.disconnectingPlayer == null
                || runtime.disconnectingGeneration
                        != generation) {
            return;
        }
        if (runtime.disconnectPreparationComplete) {
            runtime.disconnectPreparationSafelyClosed &=
                    retirement.safelyClosed();
            runtime.disconnectPreparationFailure =
                    appendFailure(
                            runtime.disconnectPreparationFailure,
                            retirement.failure());
            return;
        }
        runtime.disconnectPreparationComplete =
                true;
        runtime.disconnectPreparationSafelyClosed =
                retirement.safelyClosed();
        runtime.disconnectPreparationFailure =
                retirement.failure();
    }

    private boolean
            isBoundListenerDisconnectTarget(
                    RuntimeEntry runtime,
                    @Nullable BotServerPlayer player) {
        return player != null
                && !runtime.disconnectPreparationComplete
                && handlesByBot.get(
                                runtime.handle.botId())
                        == runtime.handle
                && player.runtimeHandle()
                        == runtime.handle
                && player.getUUID()
                        .equals(runtime.handle.botId())
                && runtime.disconnectingPlayer == player
                && runtime.disconnectingListener
                        == player.connection
                && runtime.disconnectingConnection
                        == player.connection
                                .getConnection()
                && runtime.disconnectingGeneration
                        == runtime.handle.generation()
                && player.connection.player == player
                && server.getPlayerList()
                                .getPlayer(
                                        runtime.handle
                                                .botId())
                        == player
                && player.serverLevel()
                                .getPlayerByUUID(
                                        runtime.handle
                                                .botId())
                        == player
                && isBotConnectionOpen(player);
    }

    private boolean
            isRequestedRetirementCleanupTarget(
                    RuntimeEntry runtime,
                    @Nullable BotServerPlayer player) {
        return runtime.generationRetirementInProgress
                && player != null
                && player.runtimeHandle()
                        == runtime.handle
                && player.connection != null
                && player.connection.player == player
                && player.connection
                                instanceof BotGamePacketListener
                                        botListener
                && botListener.disconnectRequested()
                && server.getPlayerList()
                                .getPlayer(
                                        runtime.handle
                                                .botId())
                        == player
                && player.serverLevel()
                                .getPlayerByUUID(
                                        runtime.handle
                                                .botId())
                        == player
                && isBotConnectionOpen(player);
    }

    private static boolean
            hasRequestedListenerDisconnect(
                    RuntimeEntry runtime) {
        return listenerDisconnectRequested(
                        runtime.handle
                                .player()
                                .orElse(null))
                || listenerDisconnectRequested(
                        runtime.stagedCleanupPlayer)
                || listenerDisconnectRequested(
                        runtime.respawnCandidate);
    }

    private static boolean listenerDisconnectRequested(
            @Nullable BotServerPlayer player) {
        return player != null
                && player.connection
                                instanceof BotGamePacketListener
                                        botListener
                && botListener.disconnectRequested();
    }

    private boolean canCommitReplacementHandoff(
            RuntimeEntry runtime,
            BotServerPlayer predecessor,
            BotServerPlayer replacement,
            long oldGeneration) {
        return runtimes.get(
                                runtime.handle.botId())
                        == runtime
                && runtime.state
                        == BotLifecycleState.ACTIVE
                && runtime
                        .replacementHandoffInProgress
                && !runtime.handoffAborted
                && runtime.handle.generation()
                        == oldGeneration
                && runtime.handle.player()
                                .orElse(null)
                        == predecessor
                && runtime.stagedCleanupGeneration
                        == oldGeneration
                && runtime.stagedCleanupPredecessor
                        == predecessor
                && runtime.stagedCleanupPlayer
                        == replacement
                && isStagedCleanupAuthority(
                        runtime, replacement);
    }

    private boolean canActivateReplacement(
            RuntimeEntry runtime,
            BotServerPlayer replacement) {
        return runtimes.get(
                                runtime.handle.botId())
                        == runtime
                && runtime.state
                        == BotLifecycleState.ACTIVE
                && runtime
                        .replacementHandoffInProgress
                && !runtime.handoffAborted
                && runtime.handle.player()
                                .orElse(null)
                        == replacement
                && isAuthoritativeInstance(
                        runtime, replacement, true)
                && isBotConnectionOpen(replacement);
    }

    private void abortStagedReplacementHandoff(
            RuntimeEntry runtime,
            BotServerPlayer replacement,
            long oldGeneration,
            GenerationRetirement retirement,
            String reason) {
        if (runtimes.get(
                        runtime.handle.botId())
                != runtime) {
            return;
        }
        runtime.handoffAborted = true;
        runtime.respawnAtTick = -1;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnCandidate = null;
        runtime.respawnSuppressed = true;
        RuntimeException failure =
                retirement.failure();
        try {
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }

        GenerationRetirement stickyRetirement =
                new GenerationRetirement(
                        retirement.safelyClosed()
                                && failure == null,
                        failure);
        if (prepareListenerDisconnect(
                runtime,
                replacement,
                oldGeneration,
                stickyRetirement)) {
            try {
                MinecraftPlayerInputAdapter.clear(
                        replacement);
                replacement.connection.disconnect(
                        Component.literal(reason));
            } catch (RuntimeException exception) {
                RuntimeException disconnectFailure =
                        appendFailure(
                                failure, exception);
                recordDisconnectPreparation(
                        runtime,
                        oldGeneration,
                        new GenerationRetirement(
                                false,
                                disconnectFailure));
                if (!listenerDisconnectRequested(
                        replacement)) {
                    disconnectFailure =
                            appendFailure(
                                    disconnectFailure,
                                    finalizeRuntimeTeardownWithoutSave(
                                            runtime,
                                            replacement,
                                            null));
                }
                failure = disconnectFailure;
            }
        } else {
            failure = appendFailure(
                    failure,
                    new IllegalStateException(
                            "Could not bind the exact staged replacement to its old-generation disconnect receipt"));
            failure = appendFailure(
                    failure,
                    finalizeRuntimeTeardownWithoutSave(
                            runtime,
                            replacement,
                            null));
        }
        if (failure != null) {
            BotPlayer.LOGGER.error(
                    "{} for BotPlayer {} ({})",
                    reason,
                    runtime.handle.name(),
                    runtime.handle.botId(),
                    failure);
        } else if (!retirement.safelyClosed()) {
            BotPlayer.LOGGER.error(
                    "{} for BotPlayer {} ({}) without a safe old-generation receipt",
                    reason,
                    runtime.handle.name(),
                    runtime.handle.botId());
        }
    }

    private void abortReplacementHandoff(
            RuntimeEntry runtime,
            BotServerPlayer replacement,
            @Nullable RuntimeException priorFailure,
            String reason) {
        if (runtimes.get(
                        runtime.handle.botId())
                != runtime) {
            return;
        }
        RuntimeException failure = priorFailure;
        runtime.handoffAborted = true;
        try {
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        runtime.stagedCleanupGeneration = -1L;
        runtime.stagedCleanupPredecessor = null;
        runtime.stagedCleanupPlayer = null;
        try {
            if (replacement.runtimeHandle()
                            == runtime.handle
                    && replacement.getUUID()
                            .equals(
                                    runtime.handle.botId())
                    && runtime.handle.player()
                                    .orElse(null)
                            != replacement) {
                runtime.handle.attach(replacement);
            }
            MinecraftPlayerInputAdapter.clear(
                    replacement);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        runtime.replacementHandoffInProgress =
                false;
        if (runtime.handle.player()
                        .orElse(null)
                == replacement) {
            try {
                disconnect(
                        runtime,
                        Component.literal(reason));
            } catch (RuntimeException exception) {
                failure = appendFailure(
                        failure, exception);
            }
        }
        if (runtime.disconnectingPlayer == null
                && !hasRequestedListenerDisconnect(
                        runtime)
                && runtimes.get(
                        runtime.handle.botId())
                == runtime) {
            failure = appendFailure(
                    failure,
                    finalizeRuntimeTeardown(
                            runtime, replacement));
        }
        if (failure != null) {
            BotPlayer.LOGGER.error(
                    "{} for BotPlayer {} ({})",
                    reason,
                    runtime.handle.name(),
                    runtime.handle.botId(),
                    failure);
        } else {
            BotPlayer.LOGGER.error(
                    "{} for BotPlayer {} ({})",
                    reason,
                    runtime.handle.name(),
                    runtime.handle.botId());
        }
    }

    private static boolean isBotConnectionOpen(
            BotServerPlayer player) {
        return player.connection != null
                && player.connection.getConnection()
                        instanceof BotConnection connection
                && connection.snapshot().open();
    }

    private static boolean
            isPendingReplacementDisconnect(
                    RuntimeEntry runtime,
                    BotServerPlayer player) {
        BotServerPlayer predecessor =
                runtime.stagedCleanupPredecessor;
        return runtime
                        .replacementHandoffInProgress
                && runtime.stagedCleanupGeneration
                        == runtime.handle.generation()
                && runtime.stagedCleanupPlayer
                        == player
                && predecessor != null
                && runtime.handle.player()
                                .orElse(null)
                        == predecessor
                && player.runtimeHandle()
                        == runtime.handle
                && predecessor.runtimeHandle()
                        == runtime.handle
                && player.getUUID()
                        .equals(
                                runtime.handle.botId())
                && predecessor.connection != null
                && predecessor.connection
                        == player.connection;
    }

    private static boolean
            isUnstagedReplacementDisconnect(
                    RuntimeEntry runtime,
                    BotServerPlayer player) {
        BotServerPlayer predecessor =
                runtime.handle.player().orElse(null);
        return !runtime
                        .replacementHandoffInProgress
                && predecessor != null
                && predecessor != player
                && player.runtimeHandle()
                        == runtime.handle
                && predecessor.runtimeHandle()
                        == runtime.handle
                && player.getUUID()
                        .equals(
                                runtime.handle.botId())
                && predecessor.connection != null
                && predecessor.connection
                        == player.connection
                && predecessor.connection.player
                        == player;
    }

    private boolean isPresentInAnyLevel(
            BotServerPlayer player) {
        for (ServerLevel level :
                server.getAllLevels()) {
            if (level.getPlayerByUUID(player.getUUID())
                            == player
                    || level.getEntity(player.getUUID())
                            == player) {
                return true;
            }
            for (ServerPlayer candidate :
                    level.players()) {
                if (candidate == player) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从 PlayerList 列表/UUID 槽以及每个维度的 player/entity 槽按对象身份收集。
     * 单个 getter 可能在重复 UUID 半安装窗口只返回其中一个 body，不能单独作为
     * 持久化或恢复提交的唯一性证明。
     */
    private Set<Entity> exactUuidBodies(UUID botId) {
        Set<Entity> bodies = Collections.newSetFromMap(
                new IdentityHashMap<>());
        addExactUuidBody(
                bodies,
                server.getPlayerList().getPlayer(botId),
                botId);
        for (ServerPlayer player :
                server.getPlayerList().getPlayers()) {
            addExactUuidBody(bodies, player, botId);
        }
        for (ServerLevel level : server.getAllLevels()) {
            addExactUuidBody(
                    bodies,
                    level.getPlayerByUUID(botId),
                    botId);
            addExactUuidBody(
                    bodies,
                    level.getEntity(botId),
                    botId);
            for (ServerPlayer player : level.players()) {
                addExactUuidBody(bodies, player, botId);
            }
        }
        return bodies;
    }

    private boolean hasOnlyExactUuidBody(
            UUID botId, Entity expected) {
        Set<Entity> bodies = exactUuidBodies(botId);
        return bodies.size() == 1
                && bodies.contains(expected);
    }

    private static void addExactUuidBody(
            Set<Entity> bodies,
            @Nullable Entity candidate,
            UUID botId) {
        if (candidate != null
                && candidate.getUUID().equals(botId)) {
            bodies.add(candidate);
        }
    }

    private static boolean isListenerAuthority(
            BotServerPlayer player) {
        return player.connection != null
                && player.connection.player == player
                && (!(player.connection
                                instanceof BotGamePacketListener
                                        botListener)
                        || botListener
                                .acceptsRuntimeAuthority());
    }

    private static BotActionTarget target(
            BotActionTargetStatus status,
            @Nullable BotRuntimeHandle handle,
            @Nullable BotServerPlayer player) {
        return new BotActionTarget(
                status,
                handle == null ? 0 : handle.generation(),
                Optional.ofNullable(player));
    }

    private static void requireActionIdentity(
            UUID botId, long expectedGeneration) {
        Objects.requireNonNull(botId, "botId");
        if (botId.equals(new UUID(0L, 0L))) {
            throw new IllegalArgumentException(
                    "botId must not be zero");
        }
        if (expectedGeneration <= 0) {
            throw new IllegalArgumentException(
                    "expectedGeneration must be positive");
        }
    }

    private static @Nullable RuntimeException appendFailure(
            @Nullable RuntimeException current,
            @Nullable RuntimeException next) {
        if (next == null) {
            return current;
        }
        if (current == null) {
            return next;
        }
        if (current != next) {
            current.addSuppressed(next);
        }
        return current;
    }

    @Nullable
    private RuntimeEntry findByName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return runtimes.values().stream()
                .filter(runtime ->
                        runtime.handle.name().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private boolean isNameInUse(String requestedName) {
        return server.getPlayerList().getPlayers().stream()
                .anyMatch(player -> player.getGameProfile().getName().equalsIgnoreCase(requestedName));
    }

    private boolean isExactOwner(RuntimeEntry runtime, ServerPlayer requester) {
        return roster.findById(runtime.handle.botId())
                .flatMap(BotProfile::ownerId)
                .filter(requester.getUUID()::equals)
                .isPresent();
    }

    /**
     * Closes one exact current correlation and best-effort notifies only the owner captured by
     * that gate. It never reconstructs a request from a broad bot id or a client-provided nonce.
     */
    private void closeAiProposalRequestExact(AiRequestDispatchReceipt receipt) {
        AiRequestDispatchReceipt checked = Objects.requireNonNull(receipt, "receipt");
        Optional<AiProposalRequestEnvelope> envelope = aiProposalSessionGate.closeExact(checked);
        aiReviewOnlyTickets.close(checked).ifPresent(this::closePhysicalAttemptForTicket);
        closePhysicalAttemptForReceipt(checked);
        envelope.ifPresent(this::sendAiRequestCancellation);
    }

    /**
     * Closes the server gate first, then best-effort notifies the exact owner client so it can
     * cancel local HTTP work. The gate supplies the correlation; the final physical close still
     * uses the stored exact identity rather than treating the bot id as a request credential.
     */
    private void closeAiProposalRequestForBot(UUID botId) {
        UUID checkedBotId = Objects.requireNonNull(botId, "botId");
        aiProposalSessionGate.closeBot(checkedBotId).ifPresent(envelope -> {
            AiRequestDispatchReceipt receipt = AiRequestDispatchReceipt.fromEnvelope(envelope);
            aiReviewOnlyTickets.close(receipt).ifPresent(this::closePhysicalAttemptForTicket);
            closePhysicalAttemptForReceipt(receipt);
            sendAiRequestCancellation(envelope);
        });
        /* Handles only a defensive gate/ticket divergence; no request-id reconstruction occurs. */
        aiReviewOnlyTickets.closeBot(checkedBotId).ifPresent(this::closePhysicalAttemptForTicket);
        closePhysicalAttemptForBot(checkedBotId);
    }

    /** Sends shutdown cancellations and tombstones every still-open physical client permission. */
    private void closeAllAiProposalRequests() {
        aiProposalSessionGate.closeAll().forEach(envelope -> {
            AiRequestDispatchReceipt receipt = AiRequestDispatchReceipt.fromEnvelope(envelope);
            aiReviewOnlyTickets.close(receipt).ifPresent(this::closePhysicalAttemptForTicket);
            closePhysicalAttemptForReceipt(receipt);
            sendAiRequestCancellation(envelope);
        });
        aiReviewOnlyTickets.closeAll().forEach(this::closePhysicalAttemptForTicket);
        List.copyOf(aiReviewOnlyPhysicalAttemptsByBot.values())
                .forEach(this::closePhysicalAttemptExact);
        List.copyOf(aiReviewOnlyPhysicalAttemptOwners.values())
                .forEach(AiReviewOnlyPhysicalAttemptOwner::close);
        aiReviewOnlyPhysicalAttemptOwners.clear();
        aiReviewOnlyPhysicalAttemptsByBot.clear();
    }

    /** Uses a ticket-bound identity only; a ticket without B1 state owns no physical cleanup. */
    private void closePhysicalAttemptForTicket(AiReviewOnlyTicket ticket) {
        Objects.requireNonNull(ticket, "ticket").physicalAttemptIdentity()
                .ifPresent(this::closePhysicalAttemptExact);
    }

    /** Closes the stored identity only when this exact safe receipt still names the active bot. */
    private void closePhysicalAttemptForReceipt(AiRequestDispatchReceipt receipt) {
        AiRequestDispatchReceipt checked = Objects.requireNonNull(receipt, "receipt");
        AiPhysicalAttemptIdentity identity = aiReviewOnlyPhysicalAttemptsByBot.get(
                checked.botId());
        if (identity != null && checked.equals(identity.dispatchReceipt())) {
            closePhysicalAttemptExact(identity);
        }
    }

    /** Defensive bot teardown still consumes a concrete server-stored identity, never client data. */
    private void closePhysicalAttemptForBot(UUID botId) {
        AiPhysicalAttemptIdentity identity = aiReviewOnlyPhysicalAttemptsByBot.get(
                Objects.requireNonNull(botId, "botId"));
        if (identity != null) {
            closePhysicalAttemptExact(identity);
        }
    }

    /**
     * Closes a physical attempt only when the owner index and coordinator still agree on its full
     * identity. An internal mismatch remains fail-closed and blocks replacement rather than
     * silently dropping a possibly live reservation.
     */
    private void closePhysicalAttemptExact(AiPhysicalAttemptIdentity identity) {
        AiPhysicalAttemptIdentity checked = Objects.requireNonNull(identity, "identity");
        AiReviewOnlyPhysicalAttemptOwner owner = aiReviewOnlyPhysicalAttemptOwners.get(
                checked.ownerId());
        if (owner == null) {
            aiReviewOnlyPhysicalAttemptsByBot.remove(
                    checked.dispatchReceipt().botId(), checked);
            return;
        }
        Optional<AiPhysicalAttemptCloseResult> closed = owner.closeExact(checked);
        if (closed.isPresent()
                && closed.orElseThrow().status()
                        != AiPhysicalAttemptCloseStatus.IDENTITY_MISMATCH) {
            aiReviewOnlyPhysicalAttemptsByBot.remove(
                    checked.dispatchReceipt().botId(), checked);
            return;
        }
        if (closed.isEmpty()
                && owner.findIdentity(checked.dispatchReceipt()).isEmpty()) {
            /* The trusted owner reaper already removed this exact index. */
            aiReviewOnlyPhysicalAttemptsByBot.remove(
                    checked.dispatchReceipt().botId(), checked);
        }
    }

    /**
     * Reaps wall-clock reservation/grant deadlines that may precede the tick-based proposal TTL.
     * A clock/invariant failure is fail-closed for only that real owner; it never affects another
     * owner's review session.
     */
    private void expireAiPhysicalAttempts() {
        for (UUID ownerId : List.copyOf(aiReviewOnlyPhysicalAttemptOwners.keySet())) {
            AiReviewOnlyPhysicalAttemptOwner owner = aiReviewOnlyPhysicalAttemptOwners.get(ownerId);
            if (owner == null) {
                continue;
            }
            try {
                owner.expireDueAttemptIdentities().forEach(identity ->
                        closeAiProposalRequestExact(identity.dispatchReceipt()));
            } catch (RuntimeException exception) {
                closePhysicalAttemptsForOwner(ownerId);
            }
        }
    }

    /** Exact logout/failure teardown for one owner, including any map/gate defensive residue. */
    private void closePhysicalAttemptsForOwner(UUID ownerId) {
        UUID checkedOwnerId = Objects.requireNonNull(ownerId, "ownerId");
        List<AiPhysicalAttemptIdentity> identities = aiReviewOnlyPhysicalAttemptsByBot.values()
                .stream()
                .filter(identity -> checkedOwnerId.equals(identity.ownerId()))
                .toList();
        identities.forEach(identity -> {
            closeAiProposalRequestExact(identity.dispatchReceipt());
            closePhysicalAttemptExact(identity);
        });
        AiReviewOnlyPhysicalAttemptOwner owner = aiReviewOnlyPhysicalAttemptOwners.remove(
                checkedOwnerId);
        if (owner != null) {
            owner.close();
        }
        identities.forEach(identity -> aiReviewOnlyPhysicalAttemptsByBot.remove(
                identity.dispatchReceipt().botId(), identity));
    }

    /** Drops a ended agent binding's ledger only after its exact active attempt has already closed. */
    private void closePhysicalAttemptScope(UUID botId, UUID agentId) {
        UUID ownerId = roster.findById(Objects.requireNonNull(botId, "botId"))
                .flatMap(BotProfile::ownerId)
                .orElse(null);
        if (ownerId == null) {
            return;
        }
        AiReviewOnlyPhysicalAttemptOwner owner = aiReviewOnlyPhysicalAttemptOwners.get(ownerId);
        if (owner != null) {
            owner.closeScope(botId, Objects.requireNonNull(agentId, "agentId"));
        }
    }

    private void sendAiRequestCancellation(AiProposalRequestEnvelope envelope) {
        ServerPlayer owner = server.getPlayerList().getPlayer(envelope.ownerId());
        if (owner == null
                || owner instanceof BotServerPlayer
                || !envelope.ownerId().equals(owner.getUUID())) {
            return;
        }
        try {
            PacketDistributor.sendToPlayer(owner, new AiRequestCancellationPayload(
                    roster.serverInstanceId(),
                    envelope.botId(),
                    envelope.ownerId(),
                    envelope.agentId(),
                    envelope.generation(),
                    envelope.requestId(),
                    envelope.nonce(),
                    envelope.revision()));
        } catch (RuntimeException ignored) {
            /* The gate is already closed; a transport failure must not retain client authority. */
        }
    }

    private void clearAgentBinding(UUID botId) {
        closeAiProposalRequestForBot(botId);
        UUID agentId = activeAgentByBot.get(botId);
        if (agentId != null) {
            closePhysicalAttemptScope(botId, agentId);
            activeAgentByBot.remove(botId, agentId);
            botByActiveAgent.remove(agentId, botId);
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Bot lifecycle mutation must run on the server thread");
        }
    }

    private static final class PendingNoSaveTeardown {
        private final UUID botId;
        private final long retiredGeneration;
        private final BotRuntimeHandle handle;
        private final long expectedPostGeneration;
        private final LinkedHashSet<BotServerPlayer> exactBodies =
                new LinkedHashSet<>();
        private final LinkedHashSet<ServerGamePacketListenerImpl>
                exactListeners = new LinkedHashSet<>();
        @Nullable
        private ServerGamePacketListenerImpl
                boundDisconnectListener;
        @Nullable
        private Connection boundDisconnectConnection;

        private PendingNoSaveTeardown(
                UUID botId,
                long retiredGeneration,
                BotRuntimeHandle handle,
                long expectedPostGeneration) {
            this.botId = botId;
            this.retiredGeneration = retiredGeneration;
            this.handle = handle;
            this.expectedPostGeneration =
                    expectedPostGeneration;
        }

        private void captureRuntimeBindings(
                RuntimeEntry runtime) {
            if (boundDisconnectListener == null
                    && runtime.disconnectingListener
                            != null) {
                boundDisconnectListener =
                        runtime.disconnectingListener;
                exactListeners.add(
                        boundDisconnectListener);
            }
            if (boundDisconnectConnection == null
                    && runtime.disconnectingConnection
                            != null) {
                boundDisconnectConnection =
                        runtime.disconnectingConnection;
            }
        }
    }

    private static final class PendingDirectDisconnectRetirement {
        private final BotServerPlayer player;
        private final ServerGamePacketListenerImpl listener;
        private final Connection connection;
        private final long generation;
        private GenerationRetirementSession session;
        @Nullable
        private GenerationRetirementTicket lastTicket;
        @Nullable
        private GenerationRetirementReceipt lastReceipt;
        @Nullable
        private RuntimeException failure;
        private long lastAdvanceTick = Long.MIN_VALUE;
        private boolean beginAttempted;
        private boolean survivalCloseAttempted;

        private PendingDirectDisconnectRetirement(
                BotServerPlayer player,
                ServerGamePacketListenerImpl listener,
                Connection connection,
                long generation,
                GenerationRetirementSession session) {
            this.player = Objects.requireNonNull(
                    player, "player");
            this.listener = Objects.requireNonNull(
                    listener, "listener");
            this.connection = Objects.requireNonNull(
                    connection, "connection");
            if (generation <= 0L) {
                throw new IllegalArgumentException(
                        "generation must be positive");
            }
            this.generation = generation;
            this.session = Objects.requireNonNull(
                    session, "session");
        }

        private boolean matchesBinding(
                BotServerPlayer candidatePlayer,
                ServerGamePacketListenerImpl candidateListener,
                Connection candidateConnection,
                long candidateGeneration) {
            return player == candidatePlayer
                    && listener == candidateListener
                    && connection == candidateConnection
                    && generation == candidateGeneration;
        }

        private GenerationRetirementStatus status() {
            return failure == null
                    ? session.status()
                    : GenerationRetirementStatus.UNSAFE;
        }
    }

    private static final class PendingDeathRetirement {
        private final BotServerPlayer player;
        private final ServerGamePacketListenerImpl listener;
        private final Connection connection;
        private final long generation;
        private GenerationRetirementSession session;
        @Nullable
        private GenerationRetirementTicket lastTicket;
        @Nullable
        private GenerationRetirementReceipt lastReceipt;
        @Nullable
        private RuntimeException failure;
        private long lastAdvanceTick = Long.MIN_VALUE;
        private boolean beginAttempted;
        private boolean survivalCloseAttempted;
        private DeathPersistenceRetry persistenceRetry;
        private boolean persistenceInProgress;
        private boolean deadPlayerDataCommitted;
        @Nullable
        private final VanillaDeathTicket
                vanillaConsumedTicket;

        private PendingDeathRetirement(
                BotServerPlayer player,
                ServerGamePacketListenerImpl listener,
                Connection connection,
                long generation,
                GenerationRetirementSession session,
                @Nullable VanillaDeathTicket
                        vanillaConsumedTicket) {
            this.player = Objects.requireNonNull(
                    player, "player");
            this.listener = Objects.requireNonNull(
                    listener, "listener");
            this.connection = Objects.requireNonNull(
                    connection, "connection");
            if (generation <= 0L) {
                throw new IllegalArgumentException(
                        "generation must be positive");
            }
            this.generation = generation;
            this.session = Objects.requireNonNull(
                    session, "session");
            if (vanillaConsumedTicket != null
                    && !vanillaConsumedTicket.botId()
                            .equals(player.getUUID())) {
                throw new IllegalArgumentException(
                        "vanilla-death ticket does not match the retirement binding");
            }
            this.vanillaConsumedTicket =
                    vanillaConsumedTicket;
            this.persistenceRetry = DeathPersistenceRetry.open(
                    session.startedTick(),
                    session.deadlineTick());
        }

        private boolean matchesBinding(
                BotServerPlayer candidatePlayer,
                ServerGamePacketListenerImpl candidateListener,
                Connection candidateConnection,
                long candidateGeneration) {
            return player == candidatePlayer
                    && listener == candidateListener
                    && connection == candidateConnection
                    && generation == candidateGeneration;
        }

        private GenerationRetirementStatus status() {
            return failure == null
                    ? session.status()
                    : GenerationRetirementStatus.UNSAFE;
        }
    }

    /**
     * 新 generation 只执行 suffix，但每次后续安全落盘都必须恢复为原完整批准计划的
     * provenance；这个小记录不含旧 run token，且只以当前新 runId 为 map key。
     */
    private record RecoveredSkillCheckpointLineage(
            SkillCheckpoint prior,
            SkillCheckpointRestartPlan restartPlan) {
        private RecoveredSkillCheckpointLineage {
            Objects.requireNonNull(prior, "prior");
            Objects.requireNonNull(restartPlan, "restartPlan");
            if (!prior.plan().equals(restartPlan.sourcePlan())) {
                throw new IllegalArgumentException(
                        "checkpoint lineage source plan must match prior record");
            }
        }
    }

    /** 已清理 reservation、但尚未通过 L0 clear gate 的单个通用 P5A run。 */
    private record SafetyPausedSkillRun(
            UUID runId,
            long generation,
            UUID incidentId) {
        private SafetyPausedSkillRun {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(incidentId, "incidentId");
            if (generation <= 0L) {
                throw new IllegalArgumentException(
                        "safety-paused run generation must be positive");
            }
        }
    }

    private static final class RuntimeEntry {
        private final BotRuntimeHandle handle;
        private BotLifecycleState state;
        private int respawnAtTick = -1;
        private int respawnFinalizeDeadlineTick = -1;
        private int respawnAttempts;
        private boolean respawnSuppressed;
        private boolean replacementHandoffInProgress;
        private boolean handoffAborted;
        private boolean generationRetirementInProgress;
        private boolean disconnectPreparationComplete;
        private boolean disconnectPreparationSafelyClosed;
        private long disconnectingGeneration = -1L;
        private long rememberedRetirementGeneration =
                -1L;
        private boolean rememberedRetirementSafelyClosed;
        private long stagedCleanupGeneration = -1L;
        /** 仅 normal shutdown 的 exact listener 退休可保留已核验的 P5A checkpoint。 */
        private long preserveSkillCheckpointGeneration = -1L;
        @Nullable
        private SkillCheckpointRecoverySource pendingSkillCheckpointRecovery;
        private long nextSkillCheckpointRecoveryAttemptTick;
        @Nullable
        private String lastSkillCheckpointRecoveryOutcome;
        @Nullable
        private BotServerPlayer disconnectingPlayer;
        @Nullable
        private ServerGamePacketListenerImpl
                disconnectingListener;
        @Nullable
        private Connection disconnectingConnection;
        @Nullable
        private RuntimeException
                disconnectPreparationFailure;
        @Nullable
        private RuntimeException
                rememberedRetirementFailure;
        @Nullable
        private BotServerPlayer respawnCandidate;
        @Nullable
        private BotServerPlayer stagedCleanupPredecessor;
        @Nullable
        private BotServerPlayer stagedCleanupPlayer;
        @Nullable
        private PendingNoSaveTeardown
                pendingNoSaveTeardown;
        @Nullable
        private PendingDirectDisconnectRetirement
                directDisconnectRetirement;
        @Nullable
        private PendingDeathRetirement deathRetirement;
        @Nullable
        private GenerationRetirementReceipt
                completedDeathRetirement;
        @Nullable
        private VanillaDeathTicket
                deathPersistenceTicket;
        @Nullable
        private DeathPersistenceRetry
                successorPersistenceRetry;
        private boolean successorPersistenceInProgress;
        private boolean successorPlayerDataCommitted;
        private boolean deathRetirementFailClosedInProgress;

        private RuntimeEntry(BotRuntimeHandle handle, BotLifecycleState state) {
            this.handle = handle;
            this.state = state;
        }
    }

    private record GenerationRetirement(
            boolean safelyClosed,
            @Nullable RuntimeException failure) {}
}
