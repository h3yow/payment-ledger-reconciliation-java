package com.github.h3yow.paymentledgerreconciliationjava.domain;

/**
 * 对账差异分类，结论互斥、可区分：
 * MATCHED 一致；
 * AMOUNT_MISMATCH 总额不符；
 * NET_MISMATCH 净额不符（总额一致但扣费后净额双方不一致）；
 * FEE_MISMATCH 手续费不符（总额一致但渠道手续费双方不一致）；
 * STATUS_MISMATCH 状态不一致；
 * LOCAL_MISSING 渠道有单、本地无流水；
 * CHANNEL_MISSING 本地有流水、渠道缺单。
 */
public enum DifferenceType {
    MATCHED,
    AMOUNT_MISMATCH,
    NET_MISMATCH,
    FEE_MISMATCH,
    STATUS_MISMATCH,
    LOCAL_MISSING,
    CHANNEL_MISSING
}
