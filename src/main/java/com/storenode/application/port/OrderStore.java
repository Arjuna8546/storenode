package com.storenode.application.port;

import com.storenode.domain.order.Order;

import java.util.Optional;
import java.util.UUID;

public interface OrderStore {

    /**
     * Stores the order together with the key the terminal sent for this sale.
     *
     * @throws com.storenode.application.DuplicateRequestException if an order with that key already exists
     */
    void save(Order order, String idempotencyKey);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Optional<Order> findById(UUID id);
}
