package io.github.greytaiwolf.botplayer.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiClientConnectionEpochTest {
    @Test
    void queuedIngressFromDisconnectedConnectionCannotMatchReconnect() {
        AiClientConnectionEpoch first = AiClientConnectionEpoch.initial().nextConnected();
        long oldIngress = first.ingressEpoch();

        AiClientConnectionEpoch disconnected = first.nextDisconnected();
        AiClientConnectionEpoch reconnected = disconnected.nextConnected();

        assertFalse(disconnected.acceptsIngress(oldIngress));
        assertFalse(reconnected.acceptsIngress(oldIngress));
        assertTrue(reconnected.acceptsIngress(reconnected.ingressEpoch()));
    }

    @Test
    void localFactoryReplacementInvalidatesAlreadyQueuedIngress() {
        AiClientConnectionEpoch connection = AiClientConnectionEpoch.initial().nextConnected();
        long oldIngress = connection.ingressEpoch();

        AiClientConnectionEpoch replaced = connection.nextForLocalFactoryChange();

        assertFalse(replaced.acceptsIngress(oldIngress));
        assertTrue(replaced.acceptsIngress(replaced.ingressEpoch()));
    }
}
