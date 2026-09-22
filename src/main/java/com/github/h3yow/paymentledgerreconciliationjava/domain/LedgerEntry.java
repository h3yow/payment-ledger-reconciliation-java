package com.github.h3yow.paymentledgerreconciliationjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 账本分录（单行）。同一张凭证 voucherNo 下全部分录借贷合计必须相等。
 * 借/贷方金额分别保存，正常分录一方为零。
 */
public record LedgerEntry(
        String id,
        String voucherNo,
        String accountCode,
        BigDecimal debit,
        BigDecimal credit,
        String summary,
        Instant createdAt
) {
}
