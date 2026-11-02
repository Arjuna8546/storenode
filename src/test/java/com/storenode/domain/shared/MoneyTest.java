package com.storenode.domain.shared;

import com.storenode.domain.exception.CurrencyMismatchException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

class MoneyTest {

    @Test
    void additionIsExactWhereDoubleIsNot() {
        assertThat(0.1 + 0.2).isNotEqualTo(0.3);

        assertThat(Money.of("0.10", "INR").plus(Money.of("0.20", "INR"))).isEqualTo(Money.of("0.30", "INR"));
    }

    @Test
    void equalAmountsAreEqualWhateverScaleTheyWereWrittenWith() {
        assertThat(Money.of("2.5", "INR")).isEqualTo(Money.of("2.5000", "INR"));
    }

    @Test
    void refusesToMixCurrencies() {
        Money rupees = Money.of("10", "INR");
        Money dollars = Money.of("10", "USD");

        assertThatThrownBy(() -> rupees.plus(dollars)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> rupees.minus(dollars)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> rupees.compareTo(dollars)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void refusesPrecisionItCannotStore() {
        assertThatThrownBy(() -> Money.of("1.23456", "INR")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesUnknownCurrencies() {
        assertThatThrownBy(() -> Money.of("1", "RUPEES")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1", "XXX")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundsHalfEvenToTheCurrencysMinorUnit() {
        assertThat(Money.of("2.345", "INR").roundToMinorUnit()).isEqualTo(Money.of("2.34", "INR"));
        assertThat(Money.of("2.355", "INR").roundToMinorUnit()).isEqualTo(Money.of("2.36", "INR"));
        assertThat(Money.of("1250.5", "JPY").roundToMinorUnit()).isEqualTo(Money.of("1250", "JPY"));
        assertThat(Money.of("1.2345", "KWD").roundToMinorUnit()).isEqualTo(Money.of("1.234", "KWD"));
    }

    @Test
    void multiplyingByARateRoundsOnlyOnceAtTheEnd() {
        assertThat(Money.of("1.99", "INR").multiplyByRate(new BigDecimal("0.12"))).isEqualTo(Money.of("0.24", "INR"));
    }

    @Test
    void printsInTheCurrencysMinorUnits() {
        assertThat(Money.of("12.6", "INR").toPlainString()).isEqualTo("12.60");
        assertThat(Money.of("1250", "JPY").toPlainString()).isEqualTo("1250");
        assertThat(Money.of("1.5", "KWD").toPlainString()).isEqualTo("1.500");
    }
}

