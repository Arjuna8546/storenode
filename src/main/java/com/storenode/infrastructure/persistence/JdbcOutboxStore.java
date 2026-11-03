package com.storenode.infrastructure.persistence;

import com.storenode.application.port.EventCodec;
import com.storenode.application.port.OutboxStore;
import com.storenode.domain.event.OrderPlaced;
import com.storenode.domain.shared.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcOutboxStore implements OutboxStore {

    private final JdbcClient jdbc;
    private final EventCodec codec;

    public JdbcOutboxStore(JdbcClient jdbc, EventCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Override
    public void append(OrderPlaced event, Instant now) {
        jdbc.sql("""
                        insert into outbox_event (id, aggregate_id, event_type, payload, status, attempts,
                                                  next_attempt_at, created_at)
                        values (:id, :aggregateId, :type, cast(:payload as jsonb), 'PENDING', 0, :now, :now)""")
                .param("id", Ids.newId())
                .param("aggregateId", event.orderId())
                .param("type", OrderPlaced.TYPE)
                .param("payload", codec.encode(event))
                .param("now", utc(now))
                .update();
    }

    // "for update skip locked": lock the row we pick, and if another dispatcher already holds a row, skip it.
    // Two dispatchers can then work side by side without waiting for each other or taking the same event.
    @Override
    public Optional<PendingEvent> lockNextDue(Instant now) {
        return jdbc.sql("""
                        select id, event_type, payload::text as payload, attempts
                        from outbox_event
                        where status = 'PENDING' and next_attempt_at <= :now
                        order by next_attempt_at, created_at
                        limit 1
                        for update skip locked""")
                .param("now", utc(now))
                .query((rs, rowNum) -> new PendingEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("payload"), rs.getInt("attempts")))
                .optional();
    }

    @Override
    public void markPublished(UUID eventId, Instant now) {
        jdbc.sql("update outbox_event set status = 'PUBLISHED', published_at = :now, last_error = null where id = :id")
                .param("now", utc(now))
                .param("id", eventId)
                .update();
    }

    @Override
    public void markFailed(UUID eventId, int attempts, String error, Instant nextAttemptAt, boolean giveUp) {
        jdbc.sql("""
                        update outbox_event
                        set status = :status, attempts = :attempts, last_error = :error, next_attempt_at = :next
                        where id = :id""")
                .param("status", giveUp ? "FAILED" : "PENDING")
                .param("attempts", attempts)
                .param("error", error)
                .param("next", utc(nextAttemptAt))
                .param("id", eventId)
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
