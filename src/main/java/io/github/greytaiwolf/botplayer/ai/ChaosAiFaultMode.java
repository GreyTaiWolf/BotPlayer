package io.github.greytaiwolf.botplayer.ai;

/**
 * Chaos Provider 可注入、但不泄露或伪造原始 Provider 负载的故障类型。
 */
public enum ChaosAiFaultMode {
    FAILURE(false, false),
    DELAYED_FAILURE(true, false),
    MALFORMED_RESPONSE(false, true),
    DELAYED_MALFORMED_RESPONSE(true, true);

    private final boolean delayed;
    private final boolean malformedResponse;

    ChaosAiFaultMode(boolean delayed, boolean malformedResponse) {
        this.delayed = delayed;
        this.malformedResponse = malformedResponse;
    }

    public boolean delayed() {
        return delayed;
    }

    public boolean malformedResponse() {
        return malformedResponse;
    }
}
