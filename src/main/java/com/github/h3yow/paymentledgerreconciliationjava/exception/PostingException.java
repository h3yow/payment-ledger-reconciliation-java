package com.github.h3yow.paymentledgerreconciliationjava.exception;

/** 过账失败。 */
public class PostingException extends PaymentException {
    public PostingException(String message) {
        super("POSTING_FAILED", message);
    }
}
