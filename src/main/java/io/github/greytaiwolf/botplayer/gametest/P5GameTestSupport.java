package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

/**
 * P5/P5A GameTest fixture。
 *
 * <p>生产 roster/profile 没有、也不应有仅为测试服务的删除后门。既有 P5 恢复场景继续使用
 * 显式固定身份，保证能够观测同一 roster/playerdata 的重放；新的多 Bot、soak 与临时故障场景
 * 应使用 {@link #isolatedFixture(GameTestHelper, String)}。需要跨两次服务器启动保留同一身份的
 * 新场景使用 {@link #persistentFixture(GameTestHelper, String, String)}，并由外层临时世界 runner
 * 复用其 namespace。所有 cleanup 都只卸载活动 body。
 */
final class P5GameTestSupport {
    private static final Vec3 BOT_POSITION =
            new Vec3(4.5D, 1.0D, 4.5D);

    private P5GameTestSupport() {}

    /**
     * 既有 P5 恢复场景的固定身份入口。不要把新的一次性/soak 场景接到这里，否则会把历史
     * playerdata 留给下一次 GameTest；它们应使用 {@link #isolatedFixture(GameTestHelper, String)}。
     */
    static TestBot spawnFixedBot(
            GameTestHelper helper, String fixedName) {
        return P2GameTestSupport.spawnBotWithExactName(
                helper,
                null,
                fixedName,
                BOT_POSITION,
                0.0F);
    }

    /**
     * 创建单次 GameTest 的隔离 fixture。每个 role 都得到不同的 roster identity，适合多 Bot
     * 并发、故障注入和 soak 前置场景。
     */
    static IsolatedFixture isolatedFixture(
            GameTestHelper helper, String scenario) {
        return new IsolatedFixture(
                helper, P5TestIdentityScope.fresh(scenario));
    }

    /**
     * 创建可跨两次服务器启动重放的 fixture。
     *
     * <p>调用者应为每个临时世界分配一个新的七位 base36 namespace，并在第一次和第二次启动中
     * 传入完全相同的值。此 API 不会也不能删除测试留下的 roster/profile；若改变 namespace，
     * 则会得到完全隔离的新身份。
     */
    static IsolatedFixture persistentFixture(
            GameTestHelper helper,
            String namespace,
            String scenario) {
        return new IsolatedFixture(
                helper,
                P5TestIdentityScope.persistent(namespace, scenario));
    }

    static P2GameTestSupport.Cleanup cleanup() {
        return new P2GameTestSupport.Cleanup();
    }

    static P2GameTestSupport.Cleanup cleanup(TestBot bot) {
        P2GameTestSupport.Cleanup cleanup = cleanup();
        trackBot(cleanup, bot);
        return cleanup;
    }

    static void trackBot(
            P2GameTestSupport.Cleanup cleanup, TestBot bot) {
        Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(bot, "bot");
        cleanup.add(() -> P2GameTestSupport.removeBot(
                bot, "P5 survival skill GameTest completed"));
    }

    /**
     * 持有同一测试场景的名字空间和活动 body cleanup。
     *
     * <p>该类刻意不公开 roster/profile 删除能力。每次 {@link #spawn(String)} 只创建一个正常
     * {@code BotServerPlayer}，cleanup 以反向顺序走正式 lifecycle remove；持久 profile 由
     * 生产系统保留，避免测试路径绕开身份协议。
     */
    static final class IsolatedFixture {
        private final GameTestHelper helper;
        private final P5TestIdentityScope identities;
        private final P2GameTestSupport.Cleanup cleanup;
        private final Set<String> spawnedNames = new LinkedHashSet<>();

        private IsolatedFixture(
                GameTestHelper helper,
                P5TestIdentityScope identities) {
            this.helper = Objects.requireNonNull(helper, "helper");
            this.identities = Objects.requireNonNull(identities, "identities");
            this.cleanup = P5GameTestSupport.cleanup();
        }

        String scenario() {
            return identities.scenario();
        }

        String namespace() {
            return identities.namespace();
        }

        String botName(String role) {
            return identities.botName(role);
        }

        TestBot spawn(String role) {
            String name = botName(role);
            P2GameTestSupport.require(
                    spawnedNames.add(name),
                    "P5 GameTest fixture attempted to spawn the same role twice: "
                            + role);
            TestBot bot = P2GameTestSupport.spawnBotWithExactName(
                    helper,
                    null,
                    name,
                    BOT_POSITION,
                    0.0F);
            trackBot(cleanup, bot);
            return bot;
        }

        P2GameTestSupport.Cleanup cleanup() {
            return cleanup;
        }
    }
}
