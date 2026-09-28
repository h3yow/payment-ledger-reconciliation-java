package com.github.h3yow.paymentledgerreconciliationjava.domain;

/**
 * 账户类型：
 * ASSET 资产类（正常余额在借方）；
 * LIABILITY 负债类（正常余额在贷方）；
 * REVENUE 收入类（手续费收入，正常余额在贷方，余额表现为非正净额）。
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    REVENUE
}
