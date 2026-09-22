package com.github.h3yow.paymentledgerreconciliationjava.exception;

/** 参数非法。 */
public class InvalidParameterException extends PaymentException {
    public InvalidParameterException(String message) {
        super("INVALID_PARAMETER", message);
    }
}
