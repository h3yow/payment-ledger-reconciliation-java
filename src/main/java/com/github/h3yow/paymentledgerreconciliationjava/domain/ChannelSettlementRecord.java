package com.github.h3yow.paymentledgerreconciliationjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 渠道结算数据（本地模拟，不接入任何外部渠道）。businessId 与本地支付指令对齐。
 */
public record ChannelSettlementRecord(
        String businessId,
        BigDecimal amount,
        ChannelStatus status,
        Instant settledAt
) {
}
