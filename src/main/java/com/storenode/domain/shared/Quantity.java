package com.storenode.domain.shared;

public record Quantity(int value) {

    public static final int MAX = 1000;

    public Quantity {
        if (value < 1 || value > MAX) {
            throw new IllegalArgumentException("Quantity must be between 1 and " + MAX + ", got " + value);
        }
    }

    public Quantity plus(Quantity other) {
        return new Quantity(value + other.value);
    }
}

