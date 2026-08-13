package io.github.greytaiwolf.botplayer.technique.runtime;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Pure request for a child Action or short navigation operation.  The concrete
 * Minecraft command stays behind the dispatcher port.
 */
public record TechniqueChildRequest(String operationKey,
        Set<ActionChannel> channels) {
    public TechniqueChildRequest {
        operationKey = TechniqueText.operation(operationKey);
        Objects.requireNonNull(channels, "channels");
        if (channels.isEmpty()
                || channels.size()
                        > TechniqueDescriptorLimits.MAX_CHILD_CHANNELS) {
            throw new IllegalArgumentException(
                    "technique child requires 1-"
                            + TechniqueDescriptorLimits.MAX_CHILD_CHANNELS
                            + " action channels");
        }
        EnumSet<ActionChannel> copied = EnumSet.noneOf(ActionChannel.class);
        channels.forEach(channel -> copied.add(Objects.requireNonNull(
                channel, "channel")));
        channels = Collections.unmodifiableSet(copied);
    }

    /** Avoids leaking descriptor constants across the request surface. */
    private static final class TechniqueDescriptorLimits {
        private static final int MAX_CHILD_CHANNELS = 3;
    }
}
