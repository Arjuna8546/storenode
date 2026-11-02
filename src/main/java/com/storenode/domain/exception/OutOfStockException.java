package com.storenode.domain.exception;

import java.util.UUID;

public class OutOfStockException extends DomainException {

    private final UUID productId;

    public OutOfStockException(UUID productId, String sku, int requested) {
        super("OUT_OF_STOCK", "Not enough stock for " + sku + " (requested " + requested + ")");
        this.productId = productId;
    }

    public UUID productId() {
        return productId;
    }
}
