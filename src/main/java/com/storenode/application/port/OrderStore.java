package com.storenode.application.port;

import com.storenode.domain.order.Order;

import java.util.Optional;
import java.util.UUID;

public interface OrderStore {

    void save(Order order);

    Optional<Order> findById(UUID id);
}
