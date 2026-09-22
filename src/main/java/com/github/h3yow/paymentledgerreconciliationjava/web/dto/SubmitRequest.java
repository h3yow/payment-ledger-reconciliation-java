package com.github.h3yow.paymentledgerreconciliationjava.web.dto;

/** 支付受理请求体（纯类型）。 */
public record SubmitRequest(String bizId, String amount) {
}
