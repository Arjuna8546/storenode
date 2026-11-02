package com.storenode.domain.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class ValueObjectTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1001})
    void quantityRefusesValuesOutsideTheAllowedRange(int value) {
        assertThatThrownBy(() -> new Quantity(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 1000");
    }

    @Test
    void quantityAcceptsTheBoundaries() {
        assertThat(new Quantity(1).value()).isEqualTo(1);
        assertThat(new Quantity(1000).value()).isEqualTo(1000);
    }

    @Test
    void addingQuantitiesStillRespectsTheLimit() {
        assertThat(new Quantity(2).plus(new Quantity(3))).isEqualTo(new Quantity(5));
        assertThatThrownBy(() -> new Quantity(600).plus(new Quantity(600)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.01", "0.12345"})
    void taxRateRefusesValuesOutsideZeroToOneOrWithTooManyDecimals(String value) {
        assertThatThrownBy(() -> TaxRate.of(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void taxIsRoundedToTheMinorUnit() {
        // 18% of 99.99 = 17.9982 -> 18.00
        assertThat(TaxRate.of("0.18").taxOn(Money.of("99.99", "INR"))).isEqualTo(Money.of("18.00", "INR"));
    }
}
