package io.github.greytaiwolf.botplayer.ai;

/** P6 调度层的请求状态；不包含 prompt、响应或异常正文。 */
public enum AiSchedulerRequestState {
    QUEUED,
    IN_FLIGHT,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REJECTED
}
