package com.github.h3yow.paymentledgerreconciliationjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 渠道结算数据（本地模拟，不接入任何外部渠道）。businessId 与本地支付指令对齐。
 *
 * <p>{@code fee}（渠道手续费）与 {@code netAmount}（渠道净额）为可选字段：
 * 老的对账请求两者都为 null，对账口径与扩展前完全一致（只比总额与状态）；
 * 二者要么都给、要么都不给（只给其一按参数非法拒绝）。给出时参与手续费/净额的分项核对。
 */
public record ChannelSettlementRecord(
        String businessId,
        BigDecimal amount,
        ChannelStatus status,
        Instant settledAt,
        BigDecimal fee,
        BigDecimal netAmount
) {
    /** 兼容老请求：不带渠道手续费/净额。 */
    public ChannelSettlementRecord(String businessId, BigDecimal amount,
                                   ChannelStatus status, Instant settledAt) {
        this(businessId, amount, status, settledAt, null, null);
    }
}
