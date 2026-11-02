package com.storenode.web.dto;

import com.storenode.application.OrderService;
import com.storenode.domain.order.Payment;
import com.storenode.domain.order.PaymentMethod;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.Quantity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

public record PlaceOrderRequest(@NotEmpty @Valid List<LineRequest> lines, @NotNull @Valid PaymentRequest payment) {

    public record LineRequest(@NotNull UUID productId, @Min(1) @Max(Quantity.MAX) int quantity) {
    }

    public record PaymentRequest(@NotNull PaymentMethod method, @NotBlank String amount, @NotBlank String currency) {
    }

    public OrderService.PlaceOrderCommand toCommand(String terminalId) {
        List<OrderService.PlaceOrderCommand.Item> items = lines.stream()
                .map(line -> new OrderService.PlaceOrderCommand.Item(line.productId(), new Quantity(line.quantity())))
                .toList();
        Payment domainPayment = new Payment(payment.method(), Money.of(payment.amount(), payment.currency()));
        return new OrderService.PlaceOrderCommand(terminalId, items, domainPayment);
    }
}

