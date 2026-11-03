package com.storenode.web.dto;

import com.storenode.domain.kitchen.KitchenTicket;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record KitchenTicketResponse(UUID id, UUID orderId, String orderNumber, String status, Instant createdAt,
                                    List<ItemResponse> items) {

    public record ItemResponse(String sku, String name, int quantity) {
    }

    public static KitchenTicketResponse from(KitchenTicket ticket) {
        return new KitchenTicketResponse(ticket.id(), ticket.orderId(), ticket.orderNumber(), ticket.status().name(),
                ticket.createdAt(),
                ticket.items().stream().map(i -> new ItemResponse(i.sku(), i.name(), i.quantity())).toList());
    }
}
