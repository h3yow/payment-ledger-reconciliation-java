package com.github.h3yow.paymentledgerreconciliationjava.domain;

/**
 * 对账差异分类，五类结论互斥、可区分：
 * MATCHED 一致；
 * AMOUNT_MISMATCH 金额不符（仅老口径：双方均无手续费/净额信息时的总额比较）；
 * STATUS_MISMATCH 状态不一致；
 * LOCAL_MISSING 渠道有单、本地无流水；
 * CHANNEL_MISSING 本地有流水、渠道缺单；
 * NET_MISMATCH 扣费后净额对不上（与手续费差异分开识别）；
 * FEE_MISMATCH 手续费对不上（与净额差异分开识别）。
 */
public enum DifferenceType {
    MATCHED,
    AMOUNT_MISMATCH,
    STATUS_MISMATCH,
    LOCAL_MISSING,
    CHANNEL_MISSING,
    NET_MISMATCH,
    FEE_MISMATCH
}
