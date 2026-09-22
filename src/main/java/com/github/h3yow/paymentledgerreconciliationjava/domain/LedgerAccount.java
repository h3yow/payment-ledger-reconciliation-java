package com.github.h3yow.paymentledgerreconciliationjava.domain;

/**
 * 总账账户，余额按借/贷方向分别累计：
 * 资产类账户（如渠道应收清算款、备付金）正常表现为借方余额；
 * 负债类账户（如待清算客户资金、客户已清算资金）正常表现为贷方余额。
 *
 * <p>余额对象为不可变快照：任何过账都通过"构造新余额 + 发布新快照"生效，
 * 读到的永远是某个一致时刻的完整状态。
 */
public class LedgerAccount {

    private String accountCode;
    private String accountName;
    private Money debitBalance;
    private Money creditBalance;
    private long version;

    public LedgerAccount() {
    }

    public LedgerAccount(String accountCode, String accountName) {
        this.accountCode = accountCode;
        this.accountName = accountName;
        this.debitBalance = Money.zero();
        this.creditBalance = Money.zero();
    }

    public LedgerAccount snapshot() {
        LedgerAccount copy = new LedgerAccount();
        copy.accountCode = this.accountCode;
        copy.accountName = this.accountName;
        copy.debitBalance = this.debitBalance;
        copy.creditBalance = this.creditBalance;
        copy.version = this.version;
        return copy;
    }

    /** 有符号余额：借为正、贷为负，便于做守恒校验。 */
    public Money signedBalance() {
        return debitBalance.minus(creditBalance);
    }

    public String getAccountCode() {
        return accountCode;
    }

    public void setAccountCode(String accountCode) {
        this.accountCode = accountCode;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public Money getDebitBalance() {
        return debitBalance;
    }

    public void setDebitBalance(Money debitBalance) {
        this.debitBalance = debitBalance;
    }

    public Money getCreditBalance() {
        return creditBalance;
    }

    public void setCreditBalance(Money creditBalance) {
        this.creditBalance = creditBalance;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
