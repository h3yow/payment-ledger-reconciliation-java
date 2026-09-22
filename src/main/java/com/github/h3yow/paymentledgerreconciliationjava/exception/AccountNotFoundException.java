package com.github.h3yow.paymentledgerreconciliationjava.exception;

/** 账户不存在。 */
public class AccountNotFoundException extends PaymentException {
    public AccountNotFoundException(String message) {
        super("ACCOUNT_NOT_FOUND", message);
    }
}
