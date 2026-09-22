package com.github.h3yow.paymentledgerreconciliationjava.domain;

/** 对账差异分类，五类结论互斥可区分。 */
public enum DifferenceType {
    MATCHED,
    AMOUNT_MISMATCH,
    STATUS_MISMATCH,
    LOCAL_MISSING,
    CHANNEL_MISSING
}
