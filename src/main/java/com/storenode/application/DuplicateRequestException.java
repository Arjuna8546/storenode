package com.storenode.application;

/**
 * Thrown by the order store when an order with the same idempotency key already exists.
 * It never reaches the customer: OrderService catches it and hands back the order that won.
 */
public class DuplicateRequestException extends RuntimeException {

    public DuplicateRequestException(String idempotencyKey) {
        super("An order for idempotency key '" + idempotencyKey + "' already exists");
    }
}
