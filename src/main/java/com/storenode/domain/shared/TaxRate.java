package com.storenode.domain.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;


public record TaxRate(BigDecimal value) {

    public TaxRate {
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Tax rate must be between 0 and 1, got " + value);
        }
        try {
            value = value.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Tax rate has more than 4 decimal places: " + value);
        }
    }

    public static TaxRate of(String value) {
        return new TaxRate(new BigDecimal(value));
    }


    public Money taxOn(Money net) {
        return net.multiplyByRate(value);
    }
}

// tax ratte is fraction like 0.005 between 1 to 0 , at most 4 decimal
