package io.github.greytaiwolf.botplayer.action;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class CompletionDispatcher {
   static final int MAX_CAPACITY = 65536;
   private static final AtomicLong NEXT_THREAD_ID = new AtomicLong();
   private final int capacity;
   private final Semaphore submissionPermits;
   private final Semaphore cancellationPermits;
   private final ThreadPoolExecutor executor;
   private final AtomicBoolean shutdown = new AtomicBoolean();

   CompletionDispatcher(int var1) {
      if (var1 >= 2 && var1 <= 65536) {
         this.capacity = var1;
         int var2 = Math.max(1, var1 / 8);
         this.submissionPermits = new Semaphore(var1 - var2);
         this.cancellationPermits = new Semaphore(var2);
         this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(var1), var0 -> {
            Thread var1x = new Thread(var0, "botplayer-action-completion-" + NEXT_THREAD_ID.incrementAndGet());
            var1x.setDaemon(true);
            return var1x;
         }, new AbortPolicy());
      } else {
         throw new IllegalArgumentException("capacity must be between 2 and 65536");
      }
   }

   <T> CompletionDispatcher.Completion<T> tryReserveSubmission() {
      return this.tryReserve(this.submissionPermits);
   }

   <T> CompletionDispatcher.Completion<T> tryReserveCancellation() {
      CompletionDispatcher.Completion<T> var1 =
         this.tryReserve(this.cancellationPermits);
      return var1 != null ? var1 : this.tryReserve(this.submissionPermits);
   }

   void releaseUnusedReservation(CompletionDispatcher.Completion<?> var1) {
      Objects.requireNonNull(var1, "completion").releaseUnused();
   }

   <T> void dispatch(CompletionDispatcher.Completion<T> var1, T var2) {
      Objects.requireNonNull(var1, "completion");
      if (!var1.claim()) {
         throw new IllegalStateException("Completion was dispatched more than once");
      } else {
         try {
            this.executor.execute(() -> {
               try {
                  var1.future.complete(var2);
               } finally {
                  var1.releasePermit();
               }
            });
         } catch (RejectedExecutionException var4) {
            var1.releasePermit();
            throw new IllegalStateException("Reserved completion delivery was rejected", var4);
         }
      }
   }

   int pendingCount() {
      return this.capacity - this.submissionPermits.availablePermits() - this.cancellationPermits.availablePermits();
   }

   void shutdownAfterQueuedWork() {
      if (this.shutdown.compareAndSet(false, true)) {
         this.executor.shutdown();
      }
   }

   private <T> CompletionDispatcher.Completion<T> tryReserve(Semaphore var1) {
      if (this.shutdown.get() || !var1.tryAcquire()) {
         return null;
      } else if (this.shutdown.get()) {
         var1.release();
         return null;
      } else {
         return new CompletionDispatcher.Completion<>(var1);
      }
   }

   static final class Completion<T> {
      private final Semaphore permit;
      private final CompletableFuture<T> future = new CompletableFuture<>();
      private final AtomicBoolean claimed = new AtomicBoolean();

      private Completion(Semaphore var1) {
         this.permit = var1;
      }

      CompletableFuture<T> future() {
         return this.future;
      }

      private boolean claim() {
         return this.claimed.compareAndSet(false, true);
      }

      private void releaseUnused() {
         if (!this.claim()) {
            throw new IllegalStateException("Completion reservation was already consumed");
         } else {
            this.releasePermit();
         }
      }

      private void releasePermit() {
         this.permit.release();
      }
   }
}
