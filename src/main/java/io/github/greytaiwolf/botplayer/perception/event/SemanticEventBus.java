package io.github.greytaiwolf.botplayer.perception.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 服务器运行期内的有界语义事件总线。
 *
 * <p>权威事件与每 bot 的感知事件使用不同存储和不同发布入口，防止把全服审计误当作 bot
 * 知识。该类由服务器主线程持有，不提供跨线程 Minecraft 对象访问。
 */
public final class SemanticEventBus {
    public static final int MAX_AUTHORITY_CAPACITY = 65_536;
    public static final int MAX_PER_BOT_CAPACITY = 8_192;

    private final int authorityCapacity;
    private final int perBotCapacity;
    private final UUID sessionId;
    private final NavigableMap<Long, AuthorityEvent> authorityEvents =
            new TreeMap<>();
    private final NavigableMap<Long, AuthorityEvent> spatialAuthorityEvents =
            new TreeMap<>();
    private final NavigableMap<Long, AuthorityEvent> routedSoundAudits =
            new TreeMap<>();
    private final Map<PerceptionRuntimeKey, PerceivedStreams> perceivedByRuntime =
            new LinkedHashMap<>();
    private long nextAuthoritySeq = 1;
    private long highestEvictedAuthoritySeq;
    private long worldRevision;

    public SemanticEventBus(int authorityCapacity, int perBotCapacity) {
        this(authorityCapacity, perBotCapacity, UUID.randomUUID());
    }

    public SemanticEventBus(
            int authorityCapacity, int perBotCapacity, UUID sessionId) {
        if (authorityCapacity < 1 || authorityCapacity > MAX_AUTHORITY_CAPACITY) {
            throw new IllegalArgumentException(
                    "authorityCapacity must be between 1 and "
                            + MAX_AUTHORITY_CAPACITY);
        }
        if (perBotCapacity < 1 || perBotCapacity > MAX_PER_BOT_CAPACITY) {
            throw new IllegalArgumentException(
                    "perBotCapacity must be between 1 and "
                            + MAX_PER_BOT_CAPACITY);
        }
        this.authorityCapacity = authorityCapacity;
        this.perBotCapacity = perBotCapacity;
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public AuthorityEvent publishAuthority(SemanticEventDraft draft) {
        Objects.requireNonNull(draft, "draft");
        if (draft.changesWorld()) {
            worldRevision = Math.incrementExact(worldRevision);
        }
        AuthorityEvent event = new AuthorityEvent(
                sessionId, nextAuthoritySeq++, worldRevision, draft);
        authorityEvents.put(event.eventSeq(), event);
        if (draft.channels().contains(PerceptionChannel.VISUAL)
                || draft.channels().contains(PerceptionChannel.AUDIBLE)) {
            spatialAuthorityEvents.put(event.eventSeq(), event);
            if (spatialAuthorityEvents.size() > authorityCapacity) {
                spatialAuthorityEvents.pollFirstEntry();
            }
        }
        if (authorityEvents.size() > authorityCapacity) {
            Map.Entry<Long, AuthorityEvent> evicted =
                    authorityEvents.pollFirstEntry();
            if (evicted != null) {
                highestEvictedAuthoritySeq = Math.max(
                        highestEvictedAuthoritySeq,
                        evicted.getKey());
            }
        }
        return event;
    }

    /**
     * 保存已经由 vanilla 包路由决定接收者的声音审计，但不把它放进所有 bot
     * 都要扫描的空间权威流。
     */
    public AuthorityEvent publishRoutedSoundAudit(
            SemanticEventDraft draft) {
        Objects.requireNonNull(draft, "draft");
        if (draft.type() != SemanticEventType.SOUND_PLAYED
                || draft.source()
                        != SemanticEventSource.VANILLA_CLIENTBOUND
                || draft.changesWorld()) {
            throw new IllegalArgumentException(
                    "routed sound audit requires a non-world vanilla sound");
        }
        AuthorityEvent event = new AuthorityEvent(
                sessionId,
                nextAuthoritySeq++,
                worldRevision,
                draft);
        routedSoundAudits.put(event.eventSeq(), event);
        if (routedSoundAudits.size() > authorityCapacity) {
            routedSoundAudits.pollFirstEntry();
        }
        return event;
    }

    public List<AuthorityEvent> recentRoutedSoundAudits(int limit) {
        if (limit < 0 || limit > authorityCapacity) {
            throw new IllegalArgumentException(
                    "limit must be between 0 and " + authorityCapacity);
        }
        if (limit == 0) {
            return List.of();
        }
        List<AuthorityEvent> descending =
                take(routedSoundAudits.descendingMap(), limit);
        List<AuthorityEvent> chronological =
                new ArrayList<>(descending);
        java.util.Collections.reverse(chronological);
        return List.copyOf(chronological);
    }

    /**
     * 从普通权威审计环尾部读取有界窗口，不作为 bot 空间投影来源。
     */
    public List<AuthorityEvent> recentAuthority(int limit) {
        if (limit < 0 || limit > authorityCapacity) {
            throw new IllegalArgumentException(
                    "limit must be between 0 and " + authorityCapacity);
        }
        if (limit == 0) {
            return List.of();
        }
        List<AuthorityEvent> descending =
                take(authorityEvents.descendingMap(), limit);
        List<AuthorityEvent> chronological =
                new ArrayList<>(descending);
        java.util.Collections.reverse(chronological);
        return List.copyOf(chronological);
    }

    /**
     * 从独立空间投影环读取尾部，SELF/DIRECT 洪泛不会挤出可视或可听事件。
     */
    public List<AuthorityEvent> recentSpatialAuthority(int limit) {
        if (limit < 0 || limit > authorityCapacity) {
            throw new IllegalArgumentException(
                    "limit must be between 0 and " + authorityCapacity);
        }
        if (limit == 0) {
            return List.of();
        }
        List<AuthorityEvent> descending =
                take(spatialAuthorityEvents.descendingMap(), limit);
        List<AuthorityEvent> chronological =
                new ArrayList<>(descending);
        java.util.Collections.reverse(chronological);
        return List.copyOf(chronological);
    }

    public PerceivedEvent publishPerceived(
            UUID botId,
            long botGeneration,
            AuthorityEvent authority,
            PerceptionProjection projection) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(projection, "projection");
        if (!sessionId.equals(authority.sessionId())) {
            throw new IllegalArgumentException(
                    "authority event belongs to a different runtime session");
        }
        if (!authority.event().channels().contains(projection.channel())) {
            throw new IllegalArgumentException(
                    "perception channel is not allowed by the authority event");
        }
        PerceptionRuntimeKey runtimeKey =
                new PerceptionRuntimeKey(botId, botGeneration);
        PerceivedStreams retained = perceivedByRuntime.computeIfAbsent(
                runtimeKey, ignored -> new PerceivedStreams());
        PerceivedEvent existing =
                retained.find(authority.event().eventId());
        if (existing != null) {
            return existing;
        }
        PerceivedEvent event = new PerceivedEvent(
                retained.nextSequence(),
                botId,
                botGeneration,
                authority.event().eventId(),
                projection.observedAtTick(),
                projection.type(),
                projection.outcome(),
                projection.position(),
                projection.actors(),
                projection.objects(),
                projection.delta(),
                projection.source(),
                projection.channel(),
                projection.confidence(),
                projection.tags());
        retained.append(event, perBotCapacity);
        return event;
    }

    public EventReadWindow<AuthorityEvent> authoritySince(long afterSeq, int limit) {
        requireReadArguments(afterSeq, limit);
        long oldest = authorityEvents.isEmpty()
                ? 0L
                : authorityEvents.firstKey();
        long newest = authorityEvents.isEmpty()
                ? currentAuthoritySeq()
                : authorityEvents.lastKey();
        List<AuthorityEvent> selected = take(
                authorityEvents.tailMap(afterSeq, false), limit);
        return new EventReadWindow<>(
                selected,
                afterSeq < highestEvictedAuthoritySeq,
                oldest,
                newest);
    }

    public EventReadWindow<PerceivedEvent> perceivedSince(
            UUID botId, long botGeneration, long afterSeq, int limit) {
        Objects.requireNonNull(botId, "botId");
        if (botGeneration <= 0) {
            throw new IllegalArgumentException("botGeneration must be positive");
        }
        requireReadArguments(afterSeq, limit);
        PerceivedStreams retained = perceivedByRuntime.get(
                new PerceptionRuntimeKey(botId, botGeneration));
        if (retained == null) {
            return new EventReadWindow<>(List.of(), false, 0L, 0L);
        }
        return retained.read(afterSeq, limit);
    }

    public List<PerceivedEvent> recentPerceived(
            UUID botId, long botGeneration, int limit) {
        if (limit < 0 || limit > perBotCapacity) {
            throw new IllegalArgumentException(
                    "limit must be between 0 and " + perBotCapacity);
        }
        if (limit == 0) {
            return List.of();
        }
        PerceivedStreams retained = perceivedByRuntime.get(
                new PerceptionRuntimeKey(botId, botGeneration));
        return retained == null ? List.of() : retained.recent(limit);
    }

    /**
     * 返回不含声音的近期语义事件，避免声音洪泛挤出动作与世界变化证据。
     */
    public List<PerceivedEvent> recentSemanticEvents(
            UUID botId, long botGeneration, int limit) {
        requirePerceivedLimit(limit);
        if (limit == 0) {
            return List.of();
        }
        PerceivedStreams retained = perceivedByRuntime.get(
                new PerceptionRuntimeKey(botId, botGeneration));
        return retained == null
                ? List.of()
                : retained.recentSemantic(limit);
    }

    /**
     * 返回独立声音环中的近期事件。
     */
    public List<PerceivedEvent> recentSounds(
            UUID botId, long botGeneration, int limit) {
        requirePerceivedLimit(limit);
        if (limit == 0) {
            return List.of();
        }
        PerceivedStreams retained = perceivedByRuntime.get(
                new PerceptionRuntimeKey(botId, botGeneration));
        return retained == null
                ? List.of()
                : retained.recentSounds(limit);
    }

    public long currentAuthoritySeq() {
        return nextAuthoritySeq - 1;
    }

    public long currentPerceivedSeq(UUID botId, long botGeneration) {
        Objects.requireNonNull(botId, "botId");
        if (botGeneration <= 0) {
            throw new IllegalArgumentException("botGeneration must be positive");
        }
        PerceivedStreams retained = perceivedByRuntime.get(
                new PerceptionRuntimeKey(botId, botGeneration));
        return retained == null ? 0L : retained.currentSequence();
    }

    public long worldRevision() {
        return worldRevision;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public void clearBot(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        perceivedByRuntime.keySet().removeIf(key -> key.botId().equals(botId));
    }

    public void clearGeneration(UUID botId, long botGeneration) {
        Objects.requireNonNull(botId, "botId");
        if (botGeneration <= 0) {
            throw new IllegalArgumentException("botGeneration must be positive");
        }
        perceivedByRuntime.remove(
                new PerceptionRuntimeKey(botId, botGeneration));
    }

    private static void requireReadArguments(long afterSeq, int limit) {
        if (afterSeq < 0) {
            throw new IllegalArgumentException("afterSeq must not be negative");
        }
        if (limit < 1 || limit > MAX_AUTHORITY_CAPACITY) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_AUTHORITY_CAPACITY);
        }
    }

    private void requirePerceivedLimit(int limit) {
        if (limit < 0 || limit > perBotCapacity) {
            throw new IllegalArgumentException(
                    "limit must be between 0 and " + perBotCapacity);
        }
    }

    private static <T> List<T> take(
            NavigableMap<Long, T> values, int limit) {
        List<T> selected = new ArrayList<>(Math.min(limit, values.size()));
        for (T value : values.values()) {
            if (selected.size() == limit) {
                break;
            }
            selected.add(value);
        }
        return List.copyOf(selected);
    }

    private record PerceptionRuntimeKey(UUID botId, long generation) {
        private PerceptionRuntimeKey {
            Objects.requireNonNull(botId, "botId");
            if (generation <= 0) {
                throw new IllegalArgumentException("generation must be positive");
            }
        }
    }

    private static final class PerceivedStreams {
        private final PerceivedRing semanticEvents =
                new PerceivedRing();
        private final PerceivedRing sounds = new PerceivedRing();
        private final Map<UUID, PerceivedEvent> byAuthorityId =
                new LinkedHashMap<>();
        private long nextSequence = 1L;

        private long nextSequence() {
            return nextSequence++;
        }

        private long currentSequence() {
            return nextSequence - 1L;
        }

        private void append(
                PerceivedEvent event, int capacity) {
            PerceivedRing target =
                    event.type() == SemanticEventType.SOUND_PLAYED
                            ? sounds
                            : semanticEvents;
            byAuthorityId.put(
                    event.authorityEventId(), event);
            PerceivedEvent evicted =
                    target.append(event, capacity);
            if (evicted != null) {
                byAuthorityId.remove(
                        evicted.authorityEventId(), evicted);
            }
        }

        private PerceivedEvent find(UUID authorityEventId) {
            return byAuthorityId.get(authorityEventId);
        }

        private EventReadWindow<PerceivedEvent> read(
                long afterSeq, int limit) {
            List<PerceivedEvent> merged = mergeChronological(
                    semanticEvents.after(afterSeq, limit),
                    sounds.after(afterSeq, limit),
                    limit,
                    false);
            long oldest = minimumPositive(
                    semanticEvents.oldestSequence(),
                    sounds.oldestSequence());
            return new EventReadWindow<>(
                    merged,
                    afterSeq < Math.max(
                            semanticEvents.evictedThroughSequence(),
                            sounds.evictedThroughSequence()),
                    oldest,
                    currentSequence());
        }

        private List<PerceivedEvent> recent(int limit) {
            return mergeChronological(
                    semanticEvents.recent(limit),
                    sounds.recent(limit),
                    limit,
                    true);
        }

        private List<PerceivedEvent> recentSemantic(int limit) {
            return semanticEvents.recent(limit);
        }

        private List<PerceivedEvent> recentSounds(int limit) {
            return sounds.recent(limit);
        }

        private static List<PerceivedEvent> mergeChronological(
                List<PerceivedEvent> first,
                List<PerceivedEvent> second,
                int limit,
                boolean keepNewest) {
            List<PerceivedEvent> merged =
                    new ArrayList<>(first.size() + second.size());
            merged.addAll(first);
            merged.addAll(second);
            merged.sort(java.util.Comparator.comparingLong(
                    PerceivedEvent::perceivedSeq));
            if (merged.size() <= limit) {
                return List.copyOf(merged);
            }
            int from = keepNewest ? merged.size() - limit : 0;
            return List.copyOf(
                    merged.subList(from, from + limit));
        }

        private static long minimumPositive(
                long first, long second) {
            if (first == 0L) {
                return second;
            }
            if (second == 0L) {
                return first;
            }
            return Math.min(first, second);
        }
    }

    private static final class PerceivedRing {
        private final NavigableMap<Long, PerceivedEvent> events =
                new TreeMap<>();
        private long evictedThroughSeq;

        private PerceivedEvent append(
                PerceivedEvent event, int capacity) {
            events.put(event.perceivedSeq(), event);
            if (events.size() > capacity) {
                Map.Entry<Long, PerceivedEvent> evicted =
                        events.pollFirstEntry();
                evictedThroughSeq =
                        Objects.requireNonNull(evicted, "evicted event").getKey();
                return evicted.getValue();
            }
            return null;
        }

        private List<PerceivedEvent> after(
                long afterSeq, int limit) {
            return take(events.tailMap(afterSeq, false), limit);
        }

        private List<PerceivedEvent> recent(int limit) {
            List<PerceivedEvent> descending =
                    take(events.descendingMap(), limit);
            List<PerceivedEvent> chronological =
                    new ArrayList<>(descending);
            java.util.Collections.reverse(chronological);
            return List.copyOf(chronological);
        }

        private long oldestSequence() {
            return events.isEmpty() ? 0L : events.firstKey();
        }

        private long evictedThroughSequence() {
            return evictedThroughSeq;
        }
    }
}
