package com.storenode.domain.order;

import com.storenode.domain.exception.InvalidOrderException;
import com.storenode.domain.shared.Money;

import java.util.Objects;

public record Payment(PaymentMethod method, Money tendered) {

    public Payment {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(tendered, "tendered");
        if (tendered.isNegative()) {
            throw new InvalidOrderException("A payment cannot be negative");
        }
    }
}
