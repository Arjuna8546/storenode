package com.storenode.web.dto;

import com.storenode.application.ProductAvailability;
import com.storenode.domain.catalog.Product;

import java.util.UUID;

public record ProductResponse(UUID id, String sku, String name, String unitPrice, String currency,
                              String taxRate, boolean requiresPreparation, int stock) {

    public static ProductResponse from(ProductAvailability availability) {
        Product product = availability.product();
        return new ProductResponse(product.id(), product.sku(), product.name(),
                product.unitPrice().toPlainString(), product.unitPrice().currency().getCurrencyCode(),
                product.taxRate().value().toPlainString(), product.requiresPreparation(), availability.stock());
    }
}
