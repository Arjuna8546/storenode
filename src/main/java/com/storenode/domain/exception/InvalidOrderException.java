package com.storenode.domain.exception;

public class InvalidOrderException extends DomainException {
    public InvalidOrderException(String message) {
        super("INVALID_ORDER", message);
    }
}