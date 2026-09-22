package com.github.h3yow.paymentledgerreconciliationjava.domain;

/**
 * 支付指令状态：
 * ACCEPTED 已受理（尚未过账）；POSTED 已过账（终态）；FAILED 受理后过账失败（终态）。
 */
public enum PaymentStatus {
    ACCEPTED,
    POSTED,
    FAILED
}
