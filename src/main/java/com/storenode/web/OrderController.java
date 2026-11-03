package com.storenode.web;

import com.storenode.application.OrderService;
import com.storenode.domain.order.Order;
import com.storenode.web.dto.OrderResponse;
import com.storenode.web.dto.PlaceOrderRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> place(@Valid @RequestBody PlaceOrderRequest request,
                                               @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
                                               @RequestHeader(name = "X-Terminal-Id", required = false) String terminalId) {
        Order order = orders.placeOrder(request.toCommand(idempotencyKey, terminalId));
        return ResponseEntity.created(URI.create("/api/v1/orders/" + order.id())).body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return OrderResponse.from(orders.getOrder(id));
    }
}
