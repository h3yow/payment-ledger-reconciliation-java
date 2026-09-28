package com.github.h3yow.paymentledgerreconciliationjava.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 手续费费率值对象，<b>万分比（bp, basis points）口径</b>：费率 {@code r}（单位 bp）表示
 * 手续费 = 本金 × r / 10000。例如 30.00 bp = 0.30%，本金 100.00 的手续费为 0.03。
 *
 * <p>精度与范围契约（受理、过账、对账全链路统一）：
 * <ul>
 *   <li>费率本身固定 2 位小数（{@code 12.35 bp} 是最小刻度的百分之一），
 *       外部传入超过 2 位小数一律拒绝（与金额同口径，绝不隐式吞精度）；</li>
 *   <li>合法区间 {@code [0, 10000)}：0 表示免手续费；10000 bp（100%）及以上非法，
 *       因为扣完手续费不可能留下正净额；</li>
 *   <li>手续费的<b>原始精确值</b>由 {@link #rawFee(BigDecimal)} 给出（不做任何舍入），
 *       “到分”动作只在受理侧按写明的固定规则（{@link Money#round2}，HALF_UP）执行一次，
 *       尾差去向随快照保留（见 {@link FeeSnapshot}）。</li>
 * </ul>
 */
public final class FeeRate {

    /** 万分比分母：手续费 = 本金 × bp / {@value}。 */
    public static final BigDecimal BASIS_DIVISOR = new BigDecimal(10000);

    /** 费率小数位数（与记账金额一致，到 0.01 bp）。 */
    public static final int RATE_SCALE = 2;

    /** 费率上限（不含）：10000 bp = 100%。 */
    public static final BigDecimal MAX_EXCLUSIVE = new BigDecimal(10000);

    private final BigDecimal basisPoints;

    private FeeRate(BigDecimal basisPoints) {
        this.basisPoints = basisPoints;
    }

    /**
     * 由十进制字符串构造费率：非空、可解析、最多 2 位小数、区间 [0, 10000)。
     * 任一不满足抛 IllegalArgumentException，由服务层映射为“费率非法”的参数拒绝。
     */
    public static FeeRate of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("费率不能为空");
        }
        final BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("费率格式非法: " + raw);
        }
        if (parsed.stripTrailingZeros().scale() > RATE_SCALE) {
            throw new IllegalArgumentException("费率真实精度超过 " + RATE_SCALE
                    + " 位小数，拒绝隐式舍入: " + raw);
        }
        return of(parsed);
    }

    /**
     * 由 BigDecimal 构造费率（内部计算/策略读取用）：规范到 2 位小数并校验范围。
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static FeeRate of(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("费率不能为空");
        }
        BigDecimal normalized = value.setScale(RATE_SCALE, RoundingMode.UNNECESSARY);
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException("费率不能为负: " + normalized.toPlainString());
        }
        if (normalized.compareTo(MAX_EXCLUSIVE) >= 0) {
            throw new IllegalArgumentException("费率必须小于 10000 bp（100%）: "
                    + normalized.toPlainString());
        }
        return new FeeRate(normalized);
    }

    /** 费率值（bp，scale=2）。 */
    @com.fasterxml.jackson.annotation.JsonValue
    public BigDecimal basisPoints() {
        return basisPoints;
    }

    /**
     * 按本金计算手续费的<b>原始精确值</b>（不做舍入）。
     * 本金 scale=2、费率 scale=2，乘积 scale=4，再除以 10000（10⁴），
     * 结果 scale=8 且数学上精确（整除 10 的幂），不产生任何浮点误差。
     *
     * @param principalAmount 本金（scale=2 的记账金额）
     */
    public BigDecimal rawFee(BigDecimal principalAmount) {
        Objects.requireNonNull(principalAmount, "本金不能为空");
        return principalAmount.multiply(basisPoints)
                .divide(BASIS_DIVISOR, RoundingMode.UNNECESSARY);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FeeRate other)) {
            return false;
        }
        return basisPoints.compareTo(other.basisPoints) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(basisPoints.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return basisPoints.toPlainString() + "bp";
    }
}
