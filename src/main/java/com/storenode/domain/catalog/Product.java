package com.storenode.domain.catalog;


import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.TaxRate;

import java.util.Objects;
import java.util.UUID;

public record Product(UUID id, String sku, String name, Money unitPrice, TaxRate taxRate,
                      boolean requiresPreparation) {

    public Product {
        Objects.requireNonNull(id, "id");
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku is required");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(taxRate, "taxRate");
        if (unitPrice.isNegative()) {
            throw new IllegalArgumentException("unit price cannot be negative: " + unitPrice);
        }
    }
}

