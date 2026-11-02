package com.storenode.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "storenode")
public record StoreNodeProperties(Branch branch) {

    public record Branch(UUID id, String code, String zoneId) {
    }
}

