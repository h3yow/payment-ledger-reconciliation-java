package com.github.h3yow.paymentledgerreconciliationjava.domain;

import com.github.h3yow.paymentledgerreconciliationjava.exception.InvalidParameterException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 金额值对象。
 *
 * <p><b>精度与舍入口径（全系统统一）</b>：
 * <ul>
 *   <li>金额一律使用 {@link BigDecimal}，内部固定保留 2 位小数；</li>
 *   <li>入参超过 2 位小数直接判定为参数非法（拒绝隐式截断，避免不同入口舍入不一致）；</li>
 *   <li>仅当确有除法场景时按 HALF_UP（四舍五入）补位，当前清算/对账链路只有加减，不会产生新的小数位；</li>
 *   <li>等值判断按数值比较（compareTo），不依赖 BigDecimal 的 scale 敏感 equals。</li>
 * </ul>
 */
public final class Money {

    /** 金额小数位数：人民币"分"。 */
    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final BigDecimal value;

    private Money(BigDecimal value) {
        this.value = value.setScale(SC, ROUNDING);
    }

    /** 解析外部字符串金额；null/空白/非数字/超过 2 位小数均为参数非法，原因可区分。 */
    public static Money of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidParameterException("金额不能为空");
        }
        final BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidParameterException("金额格式非法: " + raw);
        }
        if (parsed.stripTrailingZeros().scale() > SCALE) {
            throw new InvalidParameterException("金额最多允许 " + SCALE + " 位小数: " + raw);
        }
        return new Money(parsed);
    }

    private static final int SC = SCALE;

    public static Money of(BigDecimal raw) {
        if (raw == null) {
            throw new InvalidParameterException("金额不能为空");
        }
        if (raw.stripTrailingZeros().scale() > SCALE) {
            throw new InvalidParameterException("金额最多允许 " + SCALE + " 位小数: " + raw);
        }
        return new Money(raw);
    }

    public static Money zero() {
        return new Money(BigDecimal.ZERO);
    }

    public BigDecimal value() {
        return value;
    }

    public Money plus(Money other) {
        return new Money(value.add(other.value));
    }

    public Money minus(Money other) {
        return new Money(value.subtract(other.value));
    }

    public Money negate() {
        return new Money(value.negate());
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isGreaterThan(Money other) {
        return value.compareTo(other.value) > 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money money)) {
            return false;
        }
        return value.compareTo(money.value) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
