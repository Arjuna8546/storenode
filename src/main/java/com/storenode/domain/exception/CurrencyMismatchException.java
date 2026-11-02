package com.storenode.domain.exception;

public class CurrencyMismatchException extends DomainException {

    public CurrencyMismatchException(String expected, String actual) {
        super("CURRENCY_MISMATCH", "Cannot combine " + expected + " with " + actual);
    }
}