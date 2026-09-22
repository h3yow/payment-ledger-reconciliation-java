package com.github.h3yow.paymentledgerreconciliationjava.service;

/** 参数非法（金额为空/非正/精度超限、必填字段缺失等），拒绝原因码 INVALID_PARAMETER。 */
public class InvalidParameterException extends RuntimeException {
    public InvalidParameterException(String message) {
        super(message);
    }
}
