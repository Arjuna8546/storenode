package com.storenode.web.dto;

import com.storenode.domain.order.Order;
import com.storenode.domain.order.OrderLine;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An order as the API shows it. All money and rates are strings, never floating point numbers. */
public record OrderResponse(UUID id, String orderNumber, String status, Instant placedAt, String terminalId,
                            String currency, List<LineResponse> lines, String subtotal, String taxTotal,
                            String total, PaymentResponse payment) {

    public record LineResponse(int lineNo, UUID productId, String sku, String name, int quantity, String unitPrice,
                               String taxRate, String netAmount, String taxAmount, String lineTotal) {

        static LineResponse from(OrderLine line) {
            return new LineResponse(line.lineNo(), line.productId(), line.sku(), line.name(),
                    line.quantity().value(), line.unitPrice().toPlainString(), line.taxRate().value().toPlainString(),
                    line.netAmount().toPlainString(), line.taxAmount().toPlainString(), line.lineTotal().toPlainString());
        }
    }

    public record PaymentResponse(String method, String tendered, String changeDue) {
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.id(), order.number().format(), order.status().name(), order.placedAt(),
                order.terminalId(), order.total().currency().getCurrencyCode(),
                order.lines().stream().map(LineResponse::from).toList(),
                order.subtotal().toPlainString(), order.taxTotal().toPlainString(), order.total().toPlainString(),
                new PaymentResponse(order.payment().method().name(), order.payment().tendered().toPlainString(),
                        order.changeDue().toPlainString()));
    }
}
