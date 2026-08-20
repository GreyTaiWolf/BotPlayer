package io.github.greytaiwolf.botplayer.ai.transport;

import java.util.Objects;
import java.util.Optional;

/**
 * Exact result of opening or explicitly replacing one client-sponsored request binding.
 *
 * <p>A rejected admission exposes no request, nonce, owner, prompt, Scheduler handle, or client
 * work. A successful replacement returns the exact displaced immutable binding so a later
 * lifecycle stage can cancel only that old request id.
 */
public record AiClientSponsoredRequestOpenResult(
        AiClientSponsoredRequestOpenStatus status,
        Optional<AiClientSponsoredRequest> opened,
        Optional<AiClientSponsoredRequest> replaced) {
    public AiClientSponsoredRequestOpenResult {
        status = Objects.requireNonNull(status, "status");
        opened = Objects.requireNonNull(opened, "opened");
        replaced = Objects.requireNonNull(replaced, "replaced");
        if ((status == AiClientSponsoredRequestOpenStatus.OPENED) != opened.isPresent()) {
            throw new IllegalArgumentException(
                    "only an opened result may carry a request binding");
        }
        if (status != AiClientSponsoredRequestOpenStatus.OPENED
                && replaced.isPresent()) {
            throw new IllegalArgumentException(
                    "a rejected admission cannot displace a request binding");
        }
        if (replaced.isPresent()
                && opened.orElseThrow().dispatch().requestId().equals(
                replaced.orElseThrow().dispatch().requestId())) {
            throw new IllegalArgumentException("replacement must use a new request id");
        }
    }

    public static AiClientSponsoredRequestOpenResult opened(
            AiClientSponsoredRequest binding) {
        return opened(binding, Optional.empty());
    }

    public static AiClientSponsoredRequestOpenResult opened(
            AiClientSponsoredRequest binding,
            Optional<AiClientSponsoredRequest> replaced) {
        return new AiClientSponsoredRequestOpenResult(
                AiClientSponsoredRequestOpenStatus.OPENED,
                Optional.of(Objects.requireNonNull(binding, "binding")),
                Objects.requireNonNull(replaced, "replaced"));
    }

    public static AiClientSponsoredRequestOpenResult rejected(
            AiClientSponsoredRequestOpenStatus status) {
        AiClientSponsoredRequestOpenStatus checked = Objects.requireNonNull(status, "status");
        if (checked == AiClientSponsoredRequestOpenStatus.OPENED) {
            throw new IllegalArgumentException("opened status needs a request binding");
        }
        return new AiClientSponsoredRequestOpenResult(
                checked, Optional.empty(), Optional.empty());
    }

    /** Deliberately does not render a binding, which could expand in a future implementation. */
    @Override
    public String toString() {
        return "AiClientSponsoredRequestOpenResult[status=" + status
                + ", opened=" + opened.isPresent()
                + ", replaced=" + replaced.isPresent() + "]";
    }
}
