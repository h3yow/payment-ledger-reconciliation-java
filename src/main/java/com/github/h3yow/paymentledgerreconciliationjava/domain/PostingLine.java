package com.github.h3yow.paymentledgerreconciliationjava.domain;

/**
 * 过账中的一条分录行：accountCode + 方向(debit=true 借/false 贷) + 金额。
 */
public class PostingLine {

    private String accountCode;
    private boolean debit;
    private Money amount;
    private String summary;

    public PostingLine() {
    }

    public PostingLine(String accountCode, boolean debit, Money amount, String summary) {
        this.accountCode = accountCode;
        this.debit = debit;
        this.amount = amount;
        this.summary = summary;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public void setAccountCode(String accountCode) {
        this.accountCode = accountCode;
    }

    public boolean isDebit() {
        return debit;
    }

    public void setDebit(boolean debit) {
        this.debit = debit;
    }

    public Money getAmount() {
        return amount;
    }

    public void setAmount(Money amount) {
        this.amount = amount;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }
}
