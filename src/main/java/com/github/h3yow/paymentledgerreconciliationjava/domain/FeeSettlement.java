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
 *   <li>{@code roundingAdjustment}：尾差 = 入账手续费 − 理论手续费。HALF_UP 处理到分时
 *       进位为正、舍去为负、整除为 0（理论手续费在 (0.00,0.005) 区间被舍去时尾差为负，
 *       理论手续费为 0 时尾差为 0），即“分位以外的小数去了哪里”；
 *       恒满足 {@code feeAmount = rawFeeAmount + roundingAdjustment}；</li>
 *   <li>{@code netAmount}：扣费后净额 = 支付总额 − 入账手续费，scale 固定 2；
 *       入账手续费为 0.00 时净额即总额。</li>
 * </ul>
 * 恒等式：{@code 支付总额 = netAmount + feeAmount}，因此凭证（净额为正时两行或三行）天然借贷平衡。
 * 只要费率实际生效（请求显式带费率或本地默认配置启用）就会生成快照，
 * <b>包括入账手续费为 0.00 的情形</b>：此时费率、0.00 手续费与等于总额的净额仍随支付留痕可查，
 * 账务按净额=总额两行入账，不产生 0 金额分录行。
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
