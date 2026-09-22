package com.github.h3yow.paymentledgerreconciliationjava.exception;

/** 重复请求。 */
public class DuplicateRequestException extends PaymentException {
    public DuplicateRequestException(String message) {
        super("DUPLICATE_REQUEST", message);
    }
}
