package com.storenode.infrastructure.scheduling;

import com.storenode.application.OutboxDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wakes up regularly and asks the dispatcher to deliver whatever is waiting in the outbox.
 * "fixedDelay" means: wait this long after the previous run has finished, so two runs never overlap.
 */
@Component
@ConditionalOnProperty(name = "storenode.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private static final System.Logger LOG = System.getLogger(OutboxPoller.class.getName());

    private final OutboxDispatcher dispatcher;

    public OutboxPoller(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${storenode.outbox.poll-interval-ms:1000}")
    public void poll() {
        try {
            dispatcher.dispatchBatch();
        } catch (RuntimeException e) {
            // e.g. the database is briefly unreachable. Nothing is lost: the events are still in the table.
            LOG.log(System.Logger.Level.WARNING, "Outbox poll failed, will try again: {0}", e.toString());
        }
    }
}
