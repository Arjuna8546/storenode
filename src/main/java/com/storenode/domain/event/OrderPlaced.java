package com.storenode.domain.event;

import com.storenode.domain.order.Order;
import com.storenode.domain.order.OrderLine;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Something that happened and that other parts of the system care about: an order with food to prepare was placed. */
public record OrderPlaced(UUID orderId, String orderNumber, Instant placedAt, List<Item> items) {

    /** The name stored next to the event, so the reader knows how to decode the payload. */
    public static final String TYPE = "OrderPlaced";

    /** One line that the kitchen has to prepare. */
    public record Item(String sku, String name, int quantity) {
    }

    public OrderPlaced {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(orderNumber, "orderNumber");
        Objects.requireNonNull(placedAt, "placedAt");
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("An OrderPlaced event needs at least one item to prepare");
        }
        items = List.copyOf(items);
    }

    /** The event for this order, or nothing when no line on it needs preparing. */
    public static Optional<OrderPlaced> from(Order order) {
        List<Item> toPrepare = order.lines().stream()
                .filter(OrderLine::requiresPreparation)
                .map(line -> new Item(line.sku(), line.name(), line.quantity().value()))
                .toList();
        if (toPrepare.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new OrderPlaced(order.id(), order.number().format(), order.placedAt(), toPrepare));
    }
}
