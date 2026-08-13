package io.github.greytaiwolf.botplayer.gametest;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 这些测试只验证 test fixture 的身份隔离契约，不接触生产 roster 或 playerdata。
 */
class P5TestIdentityScopeTest {
    @Test
    void persistentNamespaceReplaysExactlyAcrossTwoServerStarts() {
        P5TestIdentityScope first = P5TestIdentityScope.persistent(
                "a1b2c3d", "two-start-recovery");
        P5TestIdentityScope second = P5TestIdentityScope.persistent(
                "a1b2c3d", "two-start-recovery");

        Assertions.assertEquals(
                "p5_a1b2c3d_miner", first.botName("miner"));
        Assertions.assertEquals(first.botName("miner"), second.botName("miner"));
        Assertions.assertEquals(first.botName("smelt"), second.botName("smelt"));
        Assertions.assertNotEquals(first.botName("miner"), first.botName("smelt"));
    }

    @Test
    void freshScopesNeverReuseAProcessNamespace() {
        Set<String> namespaces = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < 64; index++) {
            P5TestIdentityScope scope = P5TestIdentityScope.fresh(
                    "multi-bot-soak");
            Assertions.assertTrue(
                    namespaces.add(scope.namespace()),
                    "fresh fixture reused its process namespace");
            Assertions.assertTrue(
                    names.add(scope.botName("bot")),
                    "fresh fixture reused a persistent roster name");
        }
    }

    @Test
    void namesAreMinecraftSafeAndRolesStaySeparated() {
        P5TestIdentityScope scope = P5TestIdentityScope.persistent(
                "9z8y7x6", "soak");
        Set<String> names = Set.of(
                scope.botName("lead"),
                scope.botName("mine"),
                scope.botName("smelt"),
                scope.botName("haul"));

        Assertions.assertEquals(4, names.size());
        for (String name : names) {
            Assertions.assertTrue(
                    name.length() <= P5TestIdentityScope.MAX_PLAYER_NAME_LENGTH,
                    () -> "Minecraft test name exceeds its length limit: " + name);
            Assertions.assertTrue(
                    name.matches("[a-z0-9_]{1,16}"),
                    () -> "unsafe Minecraft test name: " + name);
        }
    }

    @Test
    void rejectsAmbiguousNamespacesAndRolesBeforeAnyBotIsSpawned() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> P5TestIdentityScope.persistent(
                        "too-short", "two-start"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> P5TestIdentityScope.persistent(
                        "abcdef!", "two-start"));

        P5TestIdentityScope scope = P5TestIdentityScope.persistent(
                "abc1234", "two-start");
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> scope.botName("toolong"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> scope.botName("pvp_"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> scope.botName(" "));
    }
}
