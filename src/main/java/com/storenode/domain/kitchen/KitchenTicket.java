package com.storenode.domain.kitchen;

import com.storenode.domain.event.OrderPlaced;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the kitchen screen shows: the food to prepare for one order. */
public record KitchenTicket(UUID id, UUID branchId, UUID orderId, String orderNumber, List<Item> items,
                            Status status, Instant createdAt) {

    public enum Status { NEW, READY }

    public record Item(String sku, String name, int quantity) {
    }

    public KitchenTicket {
        items = List.copyOf(items);
    }

    public static KitchenTicket open(OrderPlaced event, UUID id, UUID branchId, Instant now) {
        List<Item> items = event.items().stream()
                .map(item -> new Item(item.sku(), item.name(), item.quantity()))
                .toList();
        return new KitchenTicket(id, branchId, event.orderId(), event.orderNumber(), items, Status.NEW, now);
    }
}
