package com.storenode.domain.exception;

public class PaymentRejectedException extends DomainException {
    public PaymentRejectedException(String message) {
        super("PAYMENT_REJECTED", message);
    }
}