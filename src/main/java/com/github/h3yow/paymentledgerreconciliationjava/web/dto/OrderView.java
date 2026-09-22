package com.github.h3yow.paymentledgerreconciliationjava.web.dto;

/** 支付单只读视图。 */
public record OrderView(String bizId, String amount, String status, String failReason,
                        long createdAt, long updatedAt) {
}
