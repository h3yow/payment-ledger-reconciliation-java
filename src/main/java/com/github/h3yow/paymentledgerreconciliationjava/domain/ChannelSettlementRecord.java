package com.github.h3yow.paymentledgerreconciliationjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 渠道结算数据（本地模拟，不接入任何外部渠道）。businessId 与本地支付指令对齐。
 *
 * @param amount      渠道结算总额（本金口径）
 * @param channelFee  渠道手续费；null 表示该渠道单未携带手续费信息（老口径）
 * @param channelNetAmount 渠道扣费后净额；null 表示该渠道单未携带净额信息（老口径）
 */
public record ChannelSettlementRecord(
        String businessId,
        BigDecimal amount,
        ChannelStatus status,
        Instant settledAt,
        BigDecimal channelFee,
        BigDecimal channelNetAmount
) {

    /** 老口径构造：不带渠道手续费/净额（两者视为未提供，对账时走原五类结论口径）。 */
    public ChannelSettlementRecord(String businessId, BigDecimal amount,
                                   ChannelStatus status, Instant settledAt) {
        this(businessId, amount, status, settledAt, null, null);
    }

    /** 该渠道单是否携带手续费/净额信息（两者同时出现才算完整的费用口径）。 */
    public boolean hasFeeDetail() {
        return channelFee != null && channelNetAmount != null;
    }
}
