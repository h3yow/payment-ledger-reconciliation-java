package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.github.h3yow.paymentledgerreconciliationjava.domain.Money;
import com.github.h3yow.paymentledgerreconciliationjava.exception.InvalidParameterException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 金额精度与舍入口径测试：关键输入与判定依据打印到日志。
 */
class MoneyTest {

    private static final Logger log = LoggerFactory.getLogger(MoneyTest.class);

    @Test
    void scaleFixedAtTwoAndScaleAgnosticEquality() {
        Money a = Money.of("10.5");
        Money b = Money.of(new BigDecimal("10.50"));
        log.info("[输入] 10.5 vs 10.50 -> value={}/{}", a, b);
        assertEquals(new BigDecimal("10.50"), a.value(), "内部固定 2 位小数");
        assertEquals(a, b, "等值判断不得受 scale 影响（compareTo 口径）");
        assertEquals("10.50", a.toString());
    }

    @Test
    void addSubtractKeepsScale() {
        Money sum = Money.of("0.01").plus(Money.of("99.99"));
        log.info("[输入] 0.01 + 99.99 -> {}", sum);
        assertEquals(Money.of("100.00"), sum);
        Money diff = Money.of("100.00").minus(Money.of("0.01"));
        assertEquals(Money.of("99.99"), diff);
    }

    @Test
    void moreThanTwoDecimalsRejectedNotSilentlyRounded() {
        InvalidParameterException ex = assertThrows(InvalidParameterException.class,
                () -> Money.of("10.555"));
        log.info("[拒绝依据] code={} message={}", ex.getCode(), ex.getMessage());
        assertEquals("INVALID_PARAMETER", ex.getCode());
        assertTrue(ex.getMessage().contains("2 位小数"));
    }

    @Test
    void blankNullAndNonNumericRejectedWithDistinctMessages() {
        InvalidParameterException e1 = assertThrows(InvalidParameterException.class, () -> Money.of(" "));
        InvalidParameterException e2 = assertThrows(InvalidParameterException.class, () -> Money.of("abc"));
        log.info("[拒绝依据] blank='{}' nonNumeric='{}'", e1.getMessage(), e2.getMessage());
        assertTrue(e1.getMessage().contains("不能为空"));
        assertTrue(e2.getMessage().contains("格式非法"));
        assertNotEquals(e1.getMessage(), e2.getMessage());
    }
}
