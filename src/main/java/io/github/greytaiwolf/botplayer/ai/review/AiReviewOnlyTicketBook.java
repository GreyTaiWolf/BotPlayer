package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-thread-only bounded correlation book for P6-R1 review tickets.
 *
 * <p>It intentionally uses the complete {@link AiRequestDispatchReceipt} when consuming a ticket.
 * A request-id collision, stale revision, purpose change, or mismatched generation leaves the
 * ticket untouched and therefore cannot clear a different active review.
 */
public final class AiReviewOnlyTicketBook {
    private final Map<UUID, AiReviewOnlyTicket> ticketsByRequest = new LinkedHashMap<>();
    private final Map<UUID, UUID> requestByBot = new LinkedHashMap<>();

    /** Opens one ticket and returns the prior same-bot ticket, if a caller failed to close it first. */
    public Optional<AiReviewOnlyTicket> open(AiReviewOnlyTicket ticket) {
        AiReviewOnlyTicket checked = Objects.requireNonNull(ticket, "ticket");
        UUID botId = checked.dispatch().botId();
        UUID requestId = checked.dispatch().requestId();
        AiReviewOnlyTicket existingRequest = ticketsByRequest.get(requestId);
        if (existingRequest != null && !existingRequest.equals(checked)) {
            throw new IllegalStateException("review request id is already reserved");
        }
        UUID previousRequestId = requestByBot.put(botId, requestId);
        AiReviewOnlyTicket previous = previousRequestId == null
                ? null
                : ticketsByRequest.remove(previousRequestId);
        ticketsByRequest.put(requestId, checked);
        return Optional.ofNullable(previous);
    }

    /** Consumes a ticket only if every safe dispatch correlation component remains exact. */
    public Optional<AiReviewOnlyTicket> close(AiRequestDispatchReceipt receipt) {
        AiRequestDispatchReceipt checked = Objects.requireNonNull(receipt, "receipt");
        AiReviewOnlyTicket ticket = ticketsByRequest.get(checked.requestId());
        if (ticket == null || !ticket.dispatch().equals(checked)) {
            return Optional.empty();
        }
        ticketsByRequest.remove(checked.requestId(), ticket);
        requestByBot.remove(checked.botId(), checked.requestId());
        return Optional.of(ticket);
    }

    /** Returns one ticket only when its complete safe dispatch receipt is still exact and live. */
    public Optional<AiReviewOnlyTicket> findExact(AiRequestDispatchReceipt receipt) {
        AiRequestDispatchReceipt checked = Objects.requireNonNull(receipt, "receipt");
        AiReviewOnlyTicket ticket = ticketsByRequest.get(checked.requestId());
        return ticket != null && ticket.dispatch().equals(checked)
                ? Optional.of(ticket)
                : Optional.empty();
    }

    /** Removes any outstanding ticket for a retired/rebound bot. */
    public Optional<AiReviewOnlyTicket> closeBot(UUID botId) {
        UUID checkedBotId = Objects.requireNonNull(botId, "botId");
        UUID requestId = requestByBot.remove(checkedBotId);
        return requestId == null
                ? Optional.empty()
                : Optional.ofNullable(ticketsByRequest.remove(requestId));
    }

    /** Removes every ticket whose gate TTL is no longer open. */
    public List<AiReviewOnlyTicket> closeExpiredThrough(long currentTick) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        List<AiReviewOnlyTicket> expired = ticketsByRequest.values().stream()
                .filter(ticket -> currentTick >= ticket.dispatch().expiresAtTick())
                .toList();
        expired.forEach(ticket -> close(ticket.dispatch()));
        return List.copyOf(expired);
    }

    public List<AiReviewOnlyTicket> closeAll() {
        List<AiReviewOnlyTicket> closed = List.copyOf(ticketsByRequest.values());
        ticketsByRequest.clear();
        requestByBot.clear();
        return closed;
    }

    public int activeTicketCount() {
        return ticketsByRequest.size();
    }
}
