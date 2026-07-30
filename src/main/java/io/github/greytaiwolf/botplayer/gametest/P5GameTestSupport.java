package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

/**
 * P5 的固定身份 fixture。固定名字会在持久 GameTest 世界中复用同一 roster profile。
 */
final class P5GameTestSupport {
    private static final Vec3 BOT_POSITION =
            new Vec3(4.5D, 1.0D, 4.5D);

    private P5GameTestSupport() {}

    static TestBot spawnFixedBot(
            GameTestHelper helper, String fixedName) {
        return P2GameTestSupport.spawnBotWithExactName(
                helper,
                null,
                fixedName,
                BOT_POSITION,
                0.0F);
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
        cleanup.add(() -> P2GameTestSupport.removeBot(
                bot, "P5 survival skill GameTest completed"));
    }
}
