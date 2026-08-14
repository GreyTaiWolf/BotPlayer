package io.github.greytaiwolf.botplayer.ai;

/**
 * P6 请求调度器的全局、每 bot 与排队硬上限。
 *
 * <p>该策略只限制异步 Provider 请求，不触碰 Minecraft 对象或世界动作。每 bot 默认仅允许一个
 * 在途请求，避免同一对话/修订产生并行且难以归因的模型响应。</p>
 */
public record AiRequestSchedulerPolicy(
        int maximumGlobalInFlight,
        int maximumInFlightPerBot,
        int maximumQueuedRequests,
        int maximumQueuedRequestsPerBot) {
    public static final int MAX_GLOBAL_IN_FLIGHT = 128;
    public static final int MAX_QUEUED_REQUESTS = 1_024;

    public AiRequestSchedulerPolicy {
        if (maximumGlobalInFlight < 1
                || maximumGlobalInFlight > MAX_GLOBAL_IN_FLIGHT) {
            throw new IllegalArgumentException(
                    "maximumGlobalInFlight must be between 1 and "
                            + MAX_GLOBAL_IN_FLIGHT);
        }
        if (maximumInFlightPerBot < 1
                || maximumInFlightPerBot > maximumGlobalInFlight) {
            throw new IllegalArgumentException(
                    "maximumInFlightPerBot must be between 1 and maximumGlobalInFlight");
        }
        if (maximumQueuedRequests < 1
                || maximumQueuedRequests > MAX_QUEUED_REQUESTS) {
            throw new IllegalArgumentException(
                    "maximumQueuedRequests must be between 1 and "
                            + MAX_QUEUED_REQUESTS);
        }
        if (maximumQueuedRequestsPerBot < 1
                || maximumQueuedRequestsPerBot > maximumQueuedRequests) {
            throw new IllegalArgumentException(
                    "maximumQueuedRequestsPerBot must be between 1 and maximumQueuedRequests");
        }
    }

    public static AiRequestSchedulerPolicy defaults() {
        return new AiRequestSchedulerPolicy(4, 1, 128, 8);
    }
}
