package io.github.greytaiwolf.botplayer.ai.transport;

import java.util.Objects;
import java.util.Optional;

/**
 * 显式替换 gate 会话时返回的新旧精确关联。
 *
 * <p>客户端赞助请求可能已在 owner 客户端执行本地 HTTP，也可能已进入 Scheduler。因此替换不能
 * 静默丢弃旧 envelope；协调器必须先用 {@link #replaced()} 为旧关联发送精确取消并清理同一
 * requestId，随后才可分发 {@link #opened()}。
 */
public record AiProposalOpenResult(
        AiProposalRequestEnvelope opened,
        Optional<AiProposalRequestEnvelope> replaced) {
    public AiProposalOpenResult {
        opened = Objects.requireNonNull(opened, "opened");
        replaced = Objects.requireNonNull(replaced, "replaced");
        if (replaced.isPresent()
                && opened.requestId().equals(replaced.orElseThrow().requestId())) {
            throw new IllegalArgumentException("replacement must use a new request id");
        }
    }
}
