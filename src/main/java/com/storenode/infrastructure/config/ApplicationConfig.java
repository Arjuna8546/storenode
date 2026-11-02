package com.storenode.infrastructure.config;

import com.storenode.application.BranchSettings;
import com.storenode.application.CatalogService;
import com.storenode.application.OrderService;
import com.storenode.application.port.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
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
                              FiscalSequence fiscalSequence, OrderStore orders, BranchSettings branch, Clock clock) {
        return new OrderService(transactions, catalog, inventory, fiscalSequence, orders, branch, clock);
    }
}