package io.github.greytaiwolf.botplayer.skill.menu;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One native menu-click dispatch boundary shared by Minecraft adapters.
 *
 * <p>The caller must already have issued exactly one click, leaving the
 * transaction in {@link MenuTransactionState#ACK}. A dispatcher exception may
 * occur before a native mutation or after a partial mutation; in either case
 * this boundary attempts the supplied authoritative reread exactly once and
 * never lets that observation become an ACK. It instead records a terminal
 * {@link MenuTransactionFailure#CLICK_DISPATCH_FAILED} when the transaction
 * is still in ACK. If re-entrant code has already advanced or cancelled the
 * transaction, the caller receives {@link Disposition#UNSAFE_REENTRANT} and
 * must use its existing fail-closed cleanup path.
 */
public final class MenuClickDispatchBoundary {
    private MenuClickDispatchBoundary() {
    }

    /**
     * Invokes one native dispatch and obtains its one post-dispatch snapshot.
     *
     * <p>A {@code null} snapshot is a valid observation of an invalid/changed
     * native menu and is returned only for the caller's ordinary ACK validator.
     * A snapshot-reader exception is never retried: after a native dispatch it
     * becomes the same terminal failure boundary as a dispatch exception.
     */
    public static Result dispatch(
            MenuTransaction transaction,
            long currentTick,
            Runnable nativeDispatch,
            Supplier<MenuSnapshot> observeAfter) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(nativeDispatch, "nativeDispatch");
        Objects.requireNonNull(observeAfter, "observeAfter");
        if (transaction.state() != MenuTransactionState.ACK) {
            throw new IllegalStateException(
                    "native menu dispatch requires one issued click");
        }
        try {
            nativeDispatch.run();
        } catch (RuntimeException exception) {
            return failAfterException(
                    transaction,
                    currentTick,
                    observeAfterOnce(observeAfter));
        }
        final MenuSnapshot after;
        try {
            after = observeAfter.get();
        } catch (RuntimeException exception) {
            return failAfterException(transaction, currentTick, null);
        }
        return transaction.state() == MenuTransactionState.ACK
                ? Result.acknowledge(after)
                : Result.unsafeReentrant(after);
    }

    private static MenuSnapshot observeAfterOnce(
            Supplier<MenuSnapshot> observeAfter) {
        try {
            return observeAfter.get();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Result failAfterException(
            MenuTransaction transaction,
            long currentTick,
            MenuSnapshot observedAfter) {
        if (transaction.state() != MenuTransactionState.ACK) {
            return Result.unsafeReentrant(observedAfter);
        }
        try {
            transaction.failAfterClickDispatchException(
                    observedAfter, currentTick);
            return Result.failed(observedAfter);
        } catch (RuntimeException exception) {
            return Result.unsafeReentrant(observedAfter);
        }
    }

    /** The post-dispatch state that the adapter must handle. */
    public enum Disposition {
        /** Native dispatch returned and the caller may validate the snapshot as an ACK. */
        ACKNOWLEDGE,
        /** Dispatch/reread failed and the transaction is terminally failed. */
        FAILED,
        /** Re-entrant code moved the transaction while the boundary was active. */
        UNSAFE_REENTRANT
    }

    /**
     * A bounded result that exposes only the optional immutable observation;
     * it never transports the throwable or live native menu objects.
     */
    public record Result(
            Disposition disposition,
            Optional<MenuSnapshot> observedAfter) {
        public Result {
            disposition = Objects.requireNonNull(disposition, "disposition");
            observedAfter = Objects.requireNonNull(
                    observedAfter, "observedAfter");
        }

        public boolean mayAcknowledge() {
            return disposition == Disposition.ACKNOWLEDGE;
        }

        private static Result acknowledge(MenuSnapshot after) {
            return new Result(
                    Disposition.ACKNOWLEDGE, Optional.ofNullable(after));
        }

        private static Result failed(MenuSnapshot observedAfter) {
            return new Result(
                    Disposition.FAILED,
                    Optional.ofNullable(observedAfter));
        }

        private static Result unsafeReentrant(MenuSnapshot observedAfter) {
            return new Result(
                    Disposition.UNSAFE_REENTRANT,
                    Optional.ofNullable(observedAfter));
        }
    }
}
