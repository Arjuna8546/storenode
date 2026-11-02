package com.storenode.domain.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderNumberTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    @Test
    void formatsAsBranchDateAndFourDigitSequence() {
        assertThat(new OrderNumber("AIR01", DAY, 1).format()).isEqualTo("ORD-AIR01-20261102-0001");
        assertThat(new OrderNumber("AIR01", DAY, 42).format()).isEqualTo("ORD-AIR01-20261102-0042");
    }

    @Test
    void growsPastFourDigitsInsteadOfFailing() {
        assertThat(new OrderNumber("AIR01", DAY, 12345).format()).isEqualTo("ORD-AIR01-20261102-12345");
    }

    @Test
    void theSequenceStartsAtOne() {
        assertThatThrownBy(() -> new OrderNumber("AIR01", DAY, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "air01", "AIR-01", "TOOLONGBRANCH"})
    void branchCodesAreShortCapitalLettersAndDigits(String code) {
        assertThatThrownBy(() -> new OrderNumber(code, DAY, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
