package com.github.h3yow.paymentledgerreconciliationjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 对账批次结果。批次结果一旦生成即不可变，按 batchId 可重复取回，历史结论可复现。
 *
 * @param basisForReuse 生成该批次时的输入指纹；同 batchId 复用历史结论前比对，防止不同数据混用
 */
public record ReconciliationBatch(
        String batchId,
        Instant ranAt,
        List<ReconciliationResultItem> items,
        int totalCount,
        int matchedCount,
        int differenceCount,
        String basisForReuse
) {
}
