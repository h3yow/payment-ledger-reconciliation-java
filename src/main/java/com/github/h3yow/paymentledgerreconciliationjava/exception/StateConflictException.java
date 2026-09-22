package com.github.h3yow.paymentledgerreconciliationjava.exception;

/** 状态冲突。 */
public class StateConflictException extends PaymentException {
    public StateConflictException(String message) {
        super("STATE_CONFLICT", message);
    }
}
