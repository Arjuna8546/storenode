package com.storenode.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.UUID;

@ConfigurationProperties(prefix = "storenode")
public record StoreNodeProperties(Branch branch, @DefaultValue Outbox outbox) {

    public record Branch(UUID id, String code, String zoneId) {
    }

    /** How the outbox is delivered. (enabled and poll-interval-ms are read directly by OutboxPoller.) */
    public record Outbox(@DefaultValue("20") int batchSize, @DefaultValue("10") int maxAttempts) {
    }
}
