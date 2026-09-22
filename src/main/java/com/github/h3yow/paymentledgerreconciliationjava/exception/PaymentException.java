package com.github.h3yow.paymentledgerreconciliationjava.exception;

/** 业务异常基类：携带可区分的错误码与原因。 */
public class PaymentException extends RuntimeException {

    private final String code;

    public PaymentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
