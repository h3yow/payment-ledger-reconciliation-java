package com.github.h3yow.paymentledgerreconciliationjava.service;

/**
 * 状态/内容冲突：业务标识已存在但请求内容不同，或对账批次号已被不同数据占用。
 * 拒绝原因码 STATUS_CONFLICT。
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
