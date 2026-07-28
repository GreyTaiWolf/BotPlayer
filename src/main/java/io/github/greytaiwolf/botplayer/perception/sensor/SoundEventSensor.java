package io.github.greytaiwolf.botplayer.perception.sensor;

import io.github.greytaiwolf.botplayer.perception.BudgetKind;
import io.github.greytaiwolf.botplayer.perception.ObservationSnapshot;
import io.github.greytaiwolf.botplayer.perception.PerceptionBudget;
import io.github.greytaiwolf.botplayer.perception.SensorId;
import io.github.greytaiwolf.botplayer.perception.SensorSchedule;
import io.github.greytaiwolf.botplayer.perception.event.PerceivedEvent;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 只筛选上下文已经投影给当前 bot 的声音事件，不监听或查询全服事件源。
 */
public final class SoundEventSensor implements BotSensor {
    public static final int DEFAULT_MAX_EVENTS = 128;
    public static final SensorSchedule DEFAULT_SCHEDULE =
            new SensorSchedule(1, 2, 5);

    private final int maximumEvents;
    private final SensorSchedule schedule;

    public SoundEventSensor() {
        this(DEFAULT_MAX_EVENTS, DEFAULT_SCHEDULE);
    }

    public SoundEventSensor(
            int maximumEvents, SensorSchedule schedule) {
        if (maximumEvents < 1
                || maximumEvents > ObservationSnapshot.MAX_EVENTS) {
            throw new IllegalArgumentException(
                    "maximumEvents must be between 1 and "
                            + ObservationSnapshot.MAX_EVENTS);
        }
        this.maximumEvents = maximumEvents;
        this.schedule = Objects.requireNonNull(schedule, "schedule");
    }

    @Override
    public SensorId id() {
        return SensorId.SOUND_EVENT;
    }

    @Override
    public SensorSchedule schedule() {
        return schedule;
    }

    @Override
    public SensorCost estimatedCost() {
        return SensorCost.of(
                BudgetKind.EVENT_READ,
                SensorContext.MAX_RECENT_EVENTS);
    }

    @Override
    public SensorResult sample(
            SensorContext context, PerceptionBudget budget) {
        Objects.requireNonNull(context, "context").assertMainThread();
        Objects.requireNonNull(budget, "budget");
        List<PerceivedEvent> candidates =
                new ArrayList<>(context.recentEvents());
        candidates.sort(
                Comparator.comparingLong(
                                PerceivedEvent::perceivedSeq)
                        .thenComparing(event ->
                                event.authorityEventId().toString())
                        .reversed());

        List<PerceivedEvent> sounds = new ArrayList<>();
        boolean truncated = false;
        for (PerceivedEvent event : candidates) {
            if (!budget.tryConsume(BudgetKind.EVENT_READ)) {
                truncated = true;
                break;
            }
            if (event.type() != SemanticEventType.SOUND_PLAYED) {
                continue;
            }
            if (sounds.size() >= maximumEvents) {
                truncated = true;
                break;
            }
            sounds.add(event);
        }
        sounds.sort(Comparator.comparingLong(
                        PerceivedEvent::perceivedSeq)
                .thenComparing(event ->
                        event.authorityEventId().toString()));
        return new SensorResult.Sounds(sounds, truncated);
    }
}
