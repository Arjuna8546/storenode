package com.storenode.domain.exception;

import java.util.UUID;

public class ProductNotFoundException extends DomainException {
    public ProductNotFoundException(UUID productId) {
        super("PRODUCT_NOT_FOUND", "No product with id " + productId);
    }
}
