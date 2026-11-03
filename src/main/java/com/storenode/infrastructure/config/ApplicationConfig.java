package com.storenode.infrastructure.config;

import com.storenode.application.BranchSettings;
import com.storenode.application.CatalogService;
import com.storenode.application.KitchenService;
import com.storenode.application.OrderService;
import com.storenode.application.OutboxDispatcher;
import com.storenode.application.port.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(StoreNodeProperties.class)
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BranchSettings branchSettings(StoreNodeProperties properties) {
        StoreNodeProperties.Branch branch = properties.branch();
        return new BranchSettings(branch.id(), branch.code(), ZoneId.of(branch.zoneId()));
    }

    @Bean
    CatalogService catalogService(ProductCatalog catalog) {
        return new CatalogService(catalog);
    }

    @Bean
    OrderService orderService(TransactionRunner transactions, ProductCatalog catalog, InventoryStore inventory,
                              FiscalSequence fiscalSequence, OrderStore orders, OutboxStore outbox,
                              BranchSettings branch, Clock clock) {
        return new OrderService(transactions, catalog, inventory, fiscalSequence, orders, outbox, branch, clock);
    }

    @Bean
    KitchenService kitchenService(KitchenTicketStore tickets, BranchSettings branch, Clock clock) {
        return new KitchenService(tickets, branch, clock);
    }

    @Bean
    OutboxDispatcher outboxDispatcher(OutboxStore outbox, EventCodec codec, KitchenService kitchen,
                                      TransactionRunner transactions, Clock clock, StoreNodeProperties properties) {
        StoreNodeProperties.Outbox settings = properties.outbox();
        return new OutboxDispatcher(outbox, codec, kitchen, transactions, clock, settings.batchSize(), settings.maxAttempts());
    }
}
