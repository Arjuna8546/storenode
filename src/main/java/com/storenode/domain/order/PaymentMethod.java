package com.storenode.domain.order;

public enum PaymentMethod {
    /** The amount handed over may exceed the total; the difference is given back as change. */
    CASH,
    /** Charged for exactly the order total. */
    CARD
}
