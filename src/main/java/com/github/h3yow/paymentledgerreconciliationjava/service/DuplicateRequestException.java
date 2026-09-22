package com.github.h3yow.paymentledgerreconciliationjava.service;

/**
 * 幂等拒绝：同一业务标识此前已成功受理，重复请求被拒绝。
 * 拒绝原因码 DUPLICATE_REQUEST，并携带已有指令状态用于解释。
 */
public class DuplicateRequestException extends RuntimeException {
    private final String existingStatus;

    public DuplicateRequestException(String message, String existingStatus) {
        super(message);
        this.existingStatus = existingStatus;
    }

    public String getExistingStatus() {
        return existingStatus;
    }
}
