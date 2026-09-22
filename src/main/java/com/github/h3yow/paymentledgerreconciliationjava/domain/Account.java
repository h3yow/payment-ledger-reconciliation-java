package com.github.h3yow.paymentledgerreconciliationjava.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 分类账账户。
 * <p>余额口径：{@code balance = 借方累计发生额 − 贷方累计发生额}（scale=2 的净额）。
 * 资产类账户正常余额在借方（非负），负债类账户正常余额在贷方（非正）。
 * 复式记账下全部账户余额之和恒为 0，可据此随时做守恒校验。
 * <p>余额变更方法为包可见，且只允许在 {@code LedgerService} 持锁期间调用，
 * 配合“先改后验、失败冲正”保证不会留下只更新部分余额的半成品。
 */
public final class Account {

    private final String code;
    private final AccountType type;
    private final String name;
    private BigDecimal balance;

    public Account(String code, AccountType type, String name, BigDecimal openingBalance) {
        this.code = Objects.requireNonNull(code, "账户号不能为空");
        this.type = Objects.requireNonNull(type, "账户类型不能为空");
        this.name = Objects.requireNonNull(name, "账户名不能为空");
        this.balance = Objects.requireNonNull(openingBalance, "期初余额不能为空")
                .setScale(Money.SCALE, java.math.RoundingMode.UNNECESSARY);
    }

    public String getCode() {
        return code;
    }

    public AccountType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    /**
     * 记一笔借方发生额：净额增加。
     * 约定：只允许 LedgerService 在持有过账锁、处于凭证临界区时调用（含失败冲正）。
     */
    public void applyDebit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    /**
     * 记一笔贷方发生额：净额减少。
     * 约定：只允许 LedgerService 在持有过账锁、处于凭证临界区时调用（含失败冲正）。
     */
    public void applyCredit(BigDecimal amount) {
        balance = balance.subtract(amount);
    }
}
