package com.storenode.application;

import com.storenode.application.port.EventCodec;
import com.storenode.application.port.OutboxStore;
import com.storenode.application.port.OutboxStore.PendingEvent;
import com.storenode.application.port.TransactionRunner;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Delivers the events waiting in the outbox. It runs on its own schedule and never inside a checkout.
 *
 * Each event is handled in one transaction: the kitchen ticket is created and the event is marked
 * published together. If anything fails, both are undone and the event simply stays pending, so it is
 * tried again later. A crash at any moment has the same effect. That is "at least once" delivery;
 * the kitchen store ignores a second ticket for the same order, so a repeat does no harm.
 */
public class OutboxDispatcher {

    private static final System.Logger LOG = System.getLogger(OutboxDispatcher.class.getName());
    private static final Duration LONGEST_WAIT = Duration.ofMinutes(5);

    private final OutboxStore outbox;
    private final EventCodec codec;
    private final KitchenService kitchen;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxDispatcher(OutboxStore outbox, EventCodec codec, KitchenService kitchen, TransactionRunner transactions,
                            Clock clock, int batchSize, int maxAttempts) {
        this.outbox = outbox;
        this.codec = codec;
        this.kitchen = kitchen;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /** Handles up to one batch of due events. @return how many were handled (delivered or failed) */
    public int dispatchBatch() {
        int handled = 0;
        while (handled < batchSize && dispatchNext()) {
            handled++;
        }
        return handled;
    }

    /** @return false when nothing is due */
    private boolean dispatchNext() {
        AtomicReference<PendingEvent> current = new AtomicReference<>();
        try {
            return transactions.inTransaction(() -> {
                Optional<PendingEvent> next = outbox.lockNextDue(clock.instant());
                if (next.isEmpty()) {
                    return false;
                }
                PendingEvent event = next.get();
                current.set(event);
                kitchen.onOrderPlaced(codec.decode(event.eventType(), event.payload()));
                outbox.markPublished(event.id(), clock.instant());
                return true;
            });
        } catch (RuntimeException e) {
            PendingEvent failed = current.get();
            if (failed == null) {
                throw e;   // we could not even read the outbox; the poller logs it and tries on the next tick
            }
            recordFailure(failed, e);
            return true;
        }
    }

    // The failed attempt was rolled back, so this runs in a new transaction of its own.
    private void recordFailure(PendingEvent event, RuntimeException error) {
        int attempts = event.attempts() + 1;
        boolean giveUp = attempts >= maxAttempts;
        transactions.inTransaction(() -> {
            outbox.markFailed(event.id(), attempts, describe(error), clock.instant().plus(backoff(attempts)), giveUp);
            return null;
        });
        LOG.log(giveUp ? System.Logger.Level.ERROR : System.Logger.Level.WARNING,
                "Outbox event {0} failed (attempt {1}){2}: {3}", event.id(), attempts,
                giveUp ? ", giving up" : "", describe(error));
    }

    /** How long to wait before the next try: 1s, 2s, 4s, 8s ... but never more than 5 minutes. */
    static Duration backoff(int attempts) {
        Duration wait = Duration.ofSeconds(1L << Math.min(attempts - 1, 20));
        return wait.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : wait;
    }

    private static String describe(RuntimeException error) {
        String text = error.getClass().getSimpleName() + ": " + error.getMessage();
        return text.length() > 1000 ? text.substring(0, 1000) : text;
    }
}
