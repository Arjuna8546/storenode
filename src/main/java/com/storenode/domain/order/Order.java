package com.storenode.domain.order;

import com.storenode.domain.shared.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Order(UUID id, UUID branchId, OrderNumber number, String terminalId, OrderStatus status,
                    List<OrderLine> lines, Money subtotal, Money taxTotal, Money total, Payment payment,
                    Money changeDue, Instant placedAt) {

    public Order {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(placedAt, "placedAt");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        lines = List.copyOf(lines);

        check(subtotal.plus(taxTotal).equals(total), "Total must equal subtotal + tax");
        switch (payment.method()) {
            case CASH -> check(changeDue.equals(payment.tendered().minus(total)),
                    "Change must equal the cash handed over minus the total");
            case CARD -> check(payment.tendered().equals(total) && changeDue.isZero(),
                    "A card is charged exactly the total and gives no change");
        }
    }

    public LocalDate businessDate() {
        return number.businessDate();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}


