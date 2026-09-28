package com.github.h3yow.paymentledgerreconciliationjava.domain;

import java.math.BigDecimal;

/**
 * 单笔支付的手续费结算快照（受理当时锁定，之后费率如何调整都不再影响本对象）。
 *
 * <ul>
 *   <li>{@code feeRateBp}：万分比费率（rate basis points），整数，例如 60 表示 0.60%（万分之六十）；</li>
 *   <li>{@code feeAccountNo}：手续费收款账户；</li>
 *   <li>{@code feeAmount}：实际入账手续费，scale 固定 2（分），HALF_UP 处理到分；</li>
 *   <li>{@code rawFeeAmount}：未做分位处理前的理论手续费（可能带 2 位以上小数），用于解释尾差；</li>
 *   <li>{@code roundingAdjustment}：尾差 = 入账手续费 − 理论手续费（HALF_UP 后非负），
 *       即“分位以外的小数去了哪里”；恒满足 {@code feeAmount = rawFeeAmount + roundingAdjustment}；</li>
 *   <li>{@code netAmount}：扣费后净额 = 支付总额 − 入账手续费，scale 固定 2。</li>
 * </ul>
 * 恒等式：{@code 支付总额 = netAmount + feeAmount}，因此凭证三行天然借贷平衡。
 */
public record FeeSettlement(
        int feeRateBp,
        String feeAccountNo,
        BigDecimal feeAmount,
        BigDecimal rawFeeAmount,
        BigDecimal roundingAdjustment,
        BigDecimal netAmount
) {
}
