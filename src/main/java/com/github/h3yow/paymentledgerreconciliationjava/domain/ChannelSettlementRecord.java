package com.github.h3yow.paymentledgerreconciliationjava.domain;

/**
 * 渠道结算数据（本地模拟，不接入任何外部服务）。
 * settleBatchId 用于区分渠道结算批次；对账批次是另一层概念。
 */
public class ChannelSettlementRecord {

    private String bizId;
    private Money amount;
    private ChannelStatus status;
    private String settleBatchId;
    private long settledAt;

    public ChannelSettlementRecord() {
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

    public ChannelStatus getStatus() {
        return status;
    }

    public void setStatus(ChannelStatus status) {
        this.status = status;
    }

    public String getSettleBatchId() {
        return settleBatchId;
    }

    public void setSettleBatchId(String settleBatchId) {
        this.settleBatchId = settleBatchId;
    }

    public long getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(long settledAt) {
        this.settledAt = settledAt;
    }
}
