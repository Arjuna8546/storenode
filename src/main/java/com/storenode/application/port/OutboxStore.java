package com.storenode.application.port;

import com.storenode.domain.event.OrderPlaced;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The outbox: events saved in the database next to the data that produced them, delivered later. */
public interface OutboxStore {

    /** Must be called inside the same transaction that saves the order. */
    void append(OrderPlaced event, Instant now);

    /**
     * Locks the oldest pending event that is due, skipping events another dispatcher has locked.
     * The lock lasts until the surrounding transaction ends.
     */
    Optional<PendingEvent> lockNextDue(Instant now);

    void markPublished(UUID eventId, Instant now);

    /** Records a failed attempt. With giveUp the event is parked as FAILED and not retried any more. */
    void markFailed(UUID eventId, int attempts, String error, Instant nextAttemptAt, boolean giveUp);

    /** The stored form. The payload is decoded later, so an event that cannot be decoded can still be retried and parked. */
    record PendingEvent(UUID id, String eventType, String payload, int attempts) {
    }
}
