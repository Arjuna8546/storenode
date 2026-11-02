package com.storenode.domain.shared;

import com.storenode.domain.exception.CurrencyMismatchException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public static final int STORAGE_SCALE = 4;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Not a monetary currency: " + currency);
        }
        try {
            amount = amount.setScale(STORAGE_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount has more than " + STORAGE_SCALE + " decimal places: " + amount);
        }
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money times(int multiplier) {
        return new Money(amount.multiply(BigDecimal.valueOf(multiplier)), currency);
    }

    /** amount x rate, rounded to the currency's minor unit (HALF_EVEN). This is how tax is calculated. */
    public Money multiplyByRate(BigDecimal rate) {
        return new Money(amount.multiply(rate).setScale(minorUnitDigits(), RoundingMode.HALF_EVEN), currency);
    }

    public Money roundToMinorUnit() {
        return new Money(amount.setScale(minorUnitDigits(), RoundingMode.HALF_EVEN), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean hasSameCurrencyAs(Money other) {
        return currency.equals(other.currency);
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    /** The amount as people see it and as we send it in JSON: "12.60" for INR, "1250" for JPY. */
    public String toPlainString() {
        return amount.setScale(minorUnitDigits(), RoundingMode.HALF_EVEN).toPlainString();
    }

    @Override
    public String toString() {
        return toPlainString() + " " + currency.getCurrencyCode();
    }

    private int minorUnitDigits() {
        return currency.getDefaultFractionDigits();
    }

    private void requireSameCurrency(Money other) {
        if (!hasSameCurrencyAs(other)) {
            throw new CurrencyMismatchException(currency.getCurrencyCode(), other.currency.getCurrencyCode());
        }
    }
}

