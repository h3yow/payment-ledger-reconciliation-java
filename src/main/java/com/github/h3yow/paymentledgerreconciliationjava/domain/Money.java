package com.github.h3yow.paymentledgerreconciliationjava.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 金额值对象。统一口径：
 * <ul>
 *   <li>记账精度：小数点后 2 位（分），内部 BigDecimal scale 固定为 2；</li>
 *   <li>舍入规则：HALF_UP（四舍五入，逢五进位），仅在显式调用 {@link #round2(BigDecimal)}
 *       （如费用拆分等计算场景）时使用；</li>
 *   <li>外部输入（支付金额）若超过 2 位小数，直接判定为非法参数，绝不隐式截断或吞掉精度；</li>
 *   <li>金额本身允许为负的场景由业务层校验，本类型只保证精度与比较口径稳定。</li>
 * </ul>
 */
public final class Money {

    /** 记账小数位数。 */
    public static final int SCALE = 2;

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount;
    }

    /**
     * 由十进制字符串构造金额。要求：非空、可解析、小数位不超过 2 位；
     * 不满足时不做任何猜测，抛出 IllegalArgumentException 由服务层转为参数非法拒绝。
     */
    public static Money of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("金额不能为空");
        }
        final BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("金额格式非法: " + raw);
        }
        if (parsed.scale() > SCALE) {
            // 禁止隐式丢精度：3 位及以上小数必须由调用方显式按口径处理
            throw new IllegalArgumentException("金额精度超过 2 位小数，拒绝隐式舍入: " + raw);
        }
        return new Money(parsed.setScale(SCALE, RoundingMode.UNNECESSARY));
    }

    /**
     * 由 BigDecimal 构造金额：按统一口径 HALF_UP 规范到 2 位小数。
     * 用于系统内部计算结果（如分摊、费用），不用于未经确认的外部原始输入。
     */
    public static Money of(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("金额不能为空");
        }
        return new Money(round2(value));
    }

    /** 统一舍入口径：HALF_UP 到 2 位小数。 */
    public static BigDecimal round2(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("金额不能为空");
        }
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 外部传入金额（{@code BigDecimal} 形态，如渠道结算总额/手续费/净额）的精度契约：
     * 非空且小数位不超过 2 位才接受，并规范到 scale=2；超过 2 位小数一律拒绝，
     * <b>绝不隐式舍入/吞精度</b>。与 {@link #of(String)} 对支付金额的口径完全一致，
     * 保证受理、过账、对账全链路同一精度规则。
     */
    public static BigDecimal requireScale2(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (value.scale() > SCALE) {
            throw new IllegalArgumentException(
                    field + " 精度超过 2 位小数，拒绝隐式舍入: " + value.toPlainString());
        }
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** 是否为正金额。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isPositive() {
        return amount.signum() > 0;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("amount")
    public BigDecimal amount() {
        return amount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        // scale 相同，compareTo 与 equals 结论一致；用 compareTo 语义更稳妥
        return amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(amount.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
