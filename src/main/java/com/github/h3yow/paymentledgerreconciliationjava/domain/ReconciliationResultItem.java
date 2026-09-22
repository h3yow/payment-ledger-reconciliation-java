package com.github.h3yow.paymentledgerreconciliationjava.domain;

/**
 * 单条对账结论。每条记录（含一致项）都落一条结论，差异不得静默忽略。
 * basis 记录判定依据，保证结论可解释、可复现。
 */
public record ReconciliationResultItem(
        String businessId,
        DifferenceType differenceType,
        String detail,
        String basis
) {
}
