package com.github.h3yow.paymentledgerreconciliationjava.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 一笔支付在<b>受理当时</b>锁定的手续费清算快照，随支付指令一起不可变保存。
 * 后续费率策略无论如何调整，都不得回改本快照，因此历史支付的手续费、净额、
 * 以及已经出具的对账结论保持可复现。
 *
 * <p>到分固定规则（全链路唯一的费用舍入口径）：
 * <ol>
 *   <li>{@code rawFee = 本金 × 费率bp / 10000}，数学精确值（{@link FeeRate#rawFee}），
 *       最多 8 位小数，不做任何舍入；</li>
 *   <li>{@code fee（到分）= round2(rawFee)}，统一 HALF_UP；</li>
 *   <li>{@code netAmount（到分）= 本金 − fee}，<b>舍入尾差由收款方净额承担</b>：
 *       手续费进位时收款方少拿，舍去时收款方多拿；</li>
 *   <li>{@code roundingDifference = fee − rawFee}（带符号、保留原始精度的解释性元数据，
 *       不参与记账）：为正表示手续费进位（净额相应少收），为负表示舍去（净额相应多收），
 *       为 0 表示无尾差。到分口径下恒等式 {@code 本金 = 净额 + 手续费} 精确成立。</li>
 * </ol>
 *
 * @param rate               受理当时生效并锁定的费率（bp）
 * @param feeAccountCode     手续费收款账户（结算方收入账户）
 * @param fee                到分手续费（记账金额，scale=2）
 * @param netAmount          到分净额（收款方实际待结算金额，必须为正；scale=2）
 * @param rawFee             手续费原始精确值（未到分，用于解释尾差）
 * @param roundingDifference 到分尾差（= fee − rawFee，带符号、保留原始精度；尾差去向可解释，不参与记账）
 */
public record FeeSnapshot(
        FeeRate rate,
        String feeAccountCode,
        Money fee,
        Money netAmount,
        BigDecimal rawFee,
        BigDecimal roundingDifference
) {

    public FeeSnapshot {
        Objects.requireNonNull(rate, "费率快照不能为空");
        Objects.requireNonNull(feeAccountCode, "手续费账户不能为空");
        Objects.requireNonNull(fee, "手续费不能为空");
        Objects.requireNonNull(netAmount, "净额不能为空");
        Objects.requireNonNull(rawFee, "手续费原始值不能为空");
        Objects.requireNonNull(roundingDifference, "尾差不能为空");
    }

    /**
     * 按写明的固定规则，由本金与受理时费率计算完整快照。
     *
     * @param principal 本金（scale=2，正数）
     * @param rate      受理当时费率
     * @param feeAccount 手续费收款账户号
     * @throws IllegalArgumentException 净额不为正（费率过高导致扣费后无正净额）
     */
    public static FeeSnapshot calculate(Money principal, FeeRate rate, String feeAccount) {
        Objects.requireNonNull(principal, "本金不能为空");
        Objects.requireNonNull(rate, "费率不能为空");
        if (feeAccount == null || feeAccount.isBlank()) {
            throw new IllegalArgumentException("手续费收款账户不能为空");
        }
        BigDecimal raw = rate.rawFee(principal.amount());
        BigDecimal feeCents = Money.round2(raw);
        BigDecimal netCents = principal.amount().subtract(feeCents);
        if (netCents.signum() <= 0) {
            // “算出来的净额不合理”：扣费后必须还剩正的待结算净额，否则在记账前拒绝
            throw new IllegalArgumentException(String.format(
                    "扣费后净额不为正，拒绝受理: 本金=%s 费率=%s 手续费=%s 净额=%s",
                    principal, rate, feeCents.toPlainString(), netCents.toPlainString()));
        }
        BigDecimal diff = feeCents.subtract(raw);
        return new FeeSnapshot(rate, feeAccount.trim(),
                Money.of(feeCents), Money.of(netCents), raw, diff);
    }
}
