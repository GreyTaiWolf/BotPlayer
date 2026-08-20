package io.github.greytaiwolf.botplayer.ai.transport;

import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-thread-owned ledger for the active generic client-sponsored request bindings.
 *
 * <p>The ledger deliberately owns only immutable {@link AiClientSponsoredRequest} values. It
 * does not open gate envelopes, send packets, start a Provider, invoke an {@code AiRequestScheduler},
 * convert model output, or reach Skill, Action, Technique, or Minecraft code. A later lifecycle
 * coordinator must use the exact binding returned by a close method to perform each of those
 * independent cleanup operations.
 *
 * <p>Normal {@link #open(AiClientSponsoredRequest)} never replaces a same-bot binding. Replacement
 * is explicit and requires the exact old gate receipt returned from
 * {@link AiProposalSessionGate#openReplacing}. This prevents a delayed terminal callback from
 * closing a newer request merely because it belongs to the same bot.
 */
public final class AiClientSponsoredRequestLedger {
    private final Map<UUID, AiClientSponsoredRequest> requestsById =
            new LinkedHashMap<>();
    private final Map<UUID, UUID> requestIdByBot = new LinkedHashMap<>();

    /**
     * Records one previously gate-bound request without implicitly replacing a live same-bot request.
     */
    public void open(AiClientSponsoredRequest request) {
        add(Objects.requireNonNull(request, "request"));
    }

    /**
     * Atomically replaces the current same-bot binding only when the gate proved which old binding
     * it replaced.
     *
     * <p>When no binding is currently held, {@code gateReplaced} must be empty. When one is held,
     * the receipt must be present and exactly match it. In either mismatch case this ledger remains
     * unchanged and the caller must fail closed rather than guessing an old cancellation identity.
     *
     * @return the exact displaced binding, if any
     */
    public Optional<AiClientSponsoredRequest> replace(
            AiClientSponsoredRequest request,
            Optional<AiRequestDispatchReceipt> gateReplaced) {
        AiClientSponsoredRequest checked = Objects.requireNonNull(
                request, "request");
        Optional<AiRequestDispatchReceipt> checkedReplaced = Objects.requireNonNull(
                gateReplaced, "gateReplaced");
        UUID botId = checked.dispatch().botId();
        AiClientSponsoredRequest current = currentForBot(botId);
        if (current == null) {
            if (checkedReplaced.isPresent()) {
                throw new IllegalStateException(
                        "gate reported a replacement that the request ledger does not hold");
            }
            add(checked);
            return Optional.empty();
        }
        if (checkedReplaced.isEmpty() || !current.matches(
                checkedReplaced.orElseThrow())) {
            throw new IllegalStateException(
                    "gate replacement receipt does not match the active request binding");
        }
        if (current.dispatch().requestId().equals(checked.dispatch().requestId())) {
            throw new IllegalArgumentException(
                    "replacement must use a new request id");
        }
        if (requestsById.containsKey(checked.dispatch().requestId())) {
            throw new IllegalStateException(
                    "replacement request id is already reserved");
        }
        remove(current);
        add(checked);
        return Optional.of(current);
    }

    /**
     * Returns the active binding only when an untrusted C2S proposal has the complete immutable
     * correlation tuple.
     *
     * <p>This is only a cheap correlation precheck. The lifecycle must still ask the gate to
     * revalidate owner, agent, generation, TTL, schema, and Firewall before it does anything with
     * the payload.
     */
    public Optional<AiClientSponsoredRequest> findMatching(
            AiProposalPayload payload) {
        AiProposalPayload checked = Objects.requireNonNull(payload, "payload");
        AiClientSponsoredRequest current = requestsById.get(
                checked.requestId());
        return current != null && current.matches(checked)
                ? Optional.of(current)
                : Optional.empty();
    }

    /**
     * Removes only the binding represented by a complete exact terminal gate receipt.
     */
    public Optional<AiClientSponsoredRequest> closeExact(
            AiRequestDispatchReceipt receipt) {
        AiRequestDispatchReceipt checked = Objects.requireNonNull(receipt,
                "receipt");
        AiClientSponsoredRequest current = requestsById.get(
                checked.requestId());
        if (current == null || !current.matches(checked)) {
            return Optional.empty();
        }
        remove(current);
        return Optional.of(current);
    }

    /**
     * Closes every binding whose half-open server-tick TTL no longer contains {@code currentTick}.
     */
    public List<AiClientSponsoredRequest> closeExpiredThrough(long currentTick) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        List<AiClientSponsoredRequest> expired = requestsById.values().stream()
                .filter(request -> request.expiresAtOrBefore(currentTick))
                .toList();
        expired.forEach(this::remove);
        return List.copyOf(expired);
    }

    /**
     * Closes the one active binding for a retiring bot and returns its exact cancellation identity.
     */
    public Optional<AiClientSponsoredRequest> closeBot(UUID botId) {
        return Optional.ofNullable(currentForBot(requireNonZero(botId, "botId")))
                .map(request -> {
                    remove(request);
                    return request;
                });
    }

    /**
     * Drops all transient bindings for lifecycle shutdown. Callers receive the original objects so
     * they can cancel the exact client and scheduler work without reconstructing a broad identity.
     */
    public List<AiClientSponsoredRequest> closeAll() {
        List<AiClientSponsoredRequest> closed = List.copyOf(
                requestsById.values());
        requestsById.clear();
        requestIdByBot.clear();
        return closed;
    }

    public int activeRequestCount() {
        return requestsById.size();
    }

    /**
     * Package-private coordination view used only to preserve the same-bot/global-capacity
     * admission invariant before a gate may replace an older envelope.
     *
     * <p>The returned binding is still immutable and must only be closed through its complete
     * {@link AiRequestDispatchReceipt}; callers must not derive a broad cancellation from this
     * lookup.
     */
    Optional<AiClientSponsoredRequest> findActiveForBot(UUID botId) {
        return Optional.ofNullable(currentForBot(requireNonZero(botId, "botId")));
    }

    /** Package-private non-content admission check for the server-thread coordinator. */
    boolean hasActiveRequestForBot(UUID botId) {
        return findActiveForBot(botId).isPresent();
    }

    /** The diagnostic intentionally exposes no request correlation or client content. */
    @Override
    public String toString() {
        return "AiClientSponsoredRequestLedger[activeRequestCount="
                + requestsById.size() + "]";
    }

    private void add(AiClientSponsoredRequest request) {
        UUID botId = request.dispatch().botId();
        UUID requestId = request.dispatch().requestId();
        if (currentForBot(botId) != null) {
            throw new IllegalStateException(
                    "an active client-sponsored request already exists for this bot");
        }
        if (requestsById.containsKey(requestId)) {
            throw new IllegalStateException(
                    "client-sponsored request id is already reserved");
        }
        requestsById.put(requestId, request);
        requestIdByBot.put(botId, requestId);
    }

    private AiClientSponsoredRequest currentForBot(UUID botId) {
        UUID requestId = requestIdByBot.get(botId);
        if (requestId == null) {
            return null;
        }
        AiClientSponsoredRequest current = requestsById.get(requestId);
        if (current == null || !botId.equals(current.dispatch().botId())) {
            throw new IllegalStateException("client-sponsored request ledger index is inconsistent");
        }
        return current;
    }

    private void remove(AiClientSponsoredRequest request) {
        UUID botId = request.dispatch().botId();
        UUID requestId = request.dispatch().requestId();
        if (!requestsById.remove(requestId, request)
                || !requestIdByBot.remove(botId, requestId)) {
            throw new IllegalStateException("client-sponsored request ledger index is inconsistent");
        }
    }

    private static UUID requireNonZero(UUID value, String name) {
        UUID checked = Objects.requireNonNull(value, name);
        if (checked.getMostSignificantBits() == 0L
                && checked.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
        return checked;
    }
}
