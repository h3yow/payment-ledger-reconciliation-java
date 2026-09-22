package com.github.h3yow.paymentledgerreconciliationjava.domain;

/**
 * 支付指令单：以 bizId（业务标识）作为幂等键。
 * 仓库层对外只返回 {@link #snapshot()} 拷贝，实体变更必须走服务层状态机。
 */
public class PaymentOrder {

    private String bizId;
    private Money amount;
    private PaymentStatus status;
    private String failReason;
    private long createdAt;
    private long updatedAt;

    public PaymentOrder() {
    }

    /** 返回字段拷贝（Money 为值对象，安全共享）。 */
    public PaymentOrder snapshot() {
        PaymentOrder copy = new PaymentOrder();
        copy.bizId = this.bizId;
        copy.amount = this.amount;
        copy.status = this.status;
        copy.failReason = this.failReason;
        copy.createdAt = this.createdAt;
        copy.updatedAt = this.updatedAt;
        return copy;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    public Money getAmount() {
        return amount;
    }

    public void setAmount(Money amount) {
        this.amount = amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
