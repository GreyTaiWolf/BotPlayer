package io.github.greytaiwolf.botplayer.gametest;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P5/P5A GameTest 的测试身份命名空间。
 *
 * <p>roster profile 是生产持久状态，测试不能通过给生产 {@code roster} 增加删除入口来“清理”。
 * 此类只为新的临时、多 Bot 和 soak 场景分配受限、可追溯的 Minecraft 名字；既有 P5 恢复
 * 场景仍显式保留固定身份。需要跨两次服务器启动复用身份的新测试显式传入同一个 namespace。
 * 两种 scope 都只允许 fixture 卸载活动 body，绝不删除 roster 或 playerdata。
 */
final class P5TestIdentityScope {
    static final int MAX_PLAYER_NAME_LENGTH = 16;
    static final int NAMESPACE_LENGTH = 7;
    static final int ROLE_LENGTH = 5;
    private static final String PREFIX = "p5_";
    private static final char[] BASE36 =
            "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final long NAMESPACE_SPACE = 78_364_164_096L; // 36^7
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicLong FRESH_SEQUENCE = new AtomicLong();
    private static final Set<String> ISSUED_FRESH_NAMESPACES =
            ConcurrentHashMap.newKeySet();

    private final String namespace;
    private final String scenario;

    private P5TestIdentityScope(String namespace, String scenario) {
        this.namespace = validateNamespace(namespace);
        this.scenario = validateScenario(scenario);
    }

    /**
     * 为单次 GameTest 创建新的名字空间。它不承诺跨服务器重启保持身份；重启测试必须使用
     * {@link #persistent(String, String)} 并由外层临时世界 runner 保留 namespace。
     */
    static P5TestIdentityScope fresh(String scenario) {
        for (int attempt = 0; attempt < 128; attempt++) {
            long sequence = FRESH_SEQUENCE.getAndIncrement();
            long random = RANDOM.nextLong();
            long mixed = random
                    ^ Long.rotateLeft(sequence, 17)
                    ^ (sequence * 0x9E3779B97F4A7C15L);
            String namespace = encodeBase36(
                    Math.floorMod(mixed, NAMESPACE_SPACE));
            if (ISSUED_FRESH_NAMESPACES.add(namespace)) {
                return new P5TestIdentityScope(namespace, scenario);
            }
        }
        throw new IllegalStateException(
                "Could not allocate an unused P5 GameTest identity namespace");
    }

    /**
     * 创建可跨独立服务器进程重放的身份范围。
     *
     * <p>{@code namespace} 必须由外层测试 runner 为每个临时世界分配一次，并在两次启动中原样
     * 传回。复用 namespace 是有意复用 roster/profile，而不是删除它们。
     */
    static P5TestIdentityScope persistent(String namespace, String scenario) {
        P5TestIdentityScope scope = new P5TestIdentityScope(namespace, scenario);
        /*
         * 同一 persistent namespace 可以在两次启动中反复构造；这里只把它加入 fresh 分配器的
         * 排除集，避免同一 JVM 的临时场景偶然复用它。
         */
        ISSUED_FRESH_NAMESPACES.add(scope.namespace());
        return scope;
    }

    String namespace() {
        return namespace;
    }

    String scenario() {
        return scenario;
    }

    /**
     * 返回同一 scope 中稳定、不同 role 间不重叠的 Bot 名称。
     */
    String botName(String role) {
        String normalizedRole = validateRole(role);
        String name = PREFIX + namespace + "_" + normalizedRole;
        if (name.length() > MAX_PLAYER_NAME_LENGTH) {
            throw new IllegalStateException("P5 GameTest identity exceeds Minecraft name limit");
        }
        return name;
    }

    private static String validateNamespace(String value) {
        String normalized = normalize(value, "namespace");
        if (normalized.length() != NAMESPACE_LENGTH) {
            throw new IllegalArgumentException(
                    "P5 GameTest namespace must contain exactly "
                            + NAMESPACE_LENGTH + " lowercase base36 characters");
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (base36Index(normalized.charAt(index)) < 0) {
                throw new IllegalArgumentException(
                        "P5 GameTest namespace must contain only lowercase base36 characters");
            }
        }
        return normalized;
    }

    private static String validateScenario(String value) {
        String normalized = normalize(value, "scenario");
        if (normalized.length() > 80) {
            throw new IllegalArgumentException("P5 GameTest scenario is too long");
        }
        return normalized;
    }

    private static String validateRole(String value) {
        String normalized = normalize(value, "role");
        if (normalized.length() > ROLE_LENGTH) {
            throw new IllegalArgumentException(
                    "P5 GameTest role must contain at most "
                            + ROLE_LENGTH + " lowercase base36 characters");
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (base36Index(normalized.charAt(index)) < 0) {
                throw new IllegalArgumentException(
                        "P5 GameTest role must contain only lowercase base36 characters");
            }
        }
        return normalized;
    }

    private static String normalize(String value, String field) {
        String normalized = Objects.requireNonNull(value, field)
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("P5 GameTest " + field + " must not be blank");
        }
        return normalized;
    }

    private static String encodeBase36(long value) {
        char[] encoded = new char[NAMESPACE_LENGTH];
        long remaining = value;
        for (int index = NAMESPACE_LENGTH - 1; index >= 0; index--) {
            encoded[index] = BASE36[(int) (remaining % BASE36.length)];
            remaining /= BASE36.length;
        }
        return new String(encoded);
    }

    private static int base36Index(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'z') {
            return 10 + value - 'a';
        }
        return -1;
    }
}
