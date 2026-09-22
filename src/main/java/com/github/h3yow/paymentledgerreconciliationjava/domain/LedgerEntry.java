package com.github.h3yow.paymentledgerreconciliationjava.domain;

/**
 * 单笔分录流水（一条分录只记一个方向）。
 * samePostingId 的全部分录构成一笔过账，借贷合计必须相等。
 */
public class LedgerEntry {

    private long id;
    private String postingId;
    private String bizId;
    private String accountCode;
    private boolean debit;
    private Money amount;
    private String summary;
    private long createdAt;

    public LedgerEntry() {
    }

    public LedgerEntry snapshot() {
        LedgerEntry copy = new LedgerEntry();
        copy.id = this.id;
        copy.postingId = this.postingId;
        copy.bizId = this.bizId;
        copy.accountCode = this.accountCode;
        copy.debit = this.debit;
        copy.amount = this.amount;
        copy.summary = this.summary;
        copy.createdAt = this.createdAt;
        return copy;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getPostingId() {
        return postingId;
    }

    public void setPostingId(String postingId) {
        this.postingId = postingId;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
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

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
