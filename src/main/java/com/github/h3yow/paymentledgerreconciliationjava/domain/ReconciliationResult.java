package com.github.h3yow.paymentledgerreconciliationjava.domain;

/**
 * 单条对账结论：一个 bizId 一条，分类互斥，结论与判定依据持久化、可复现。
 */
public class ReconciliationResult {

    private long id;
    private String reconcileBatchId;
    private String bizId;
    private DifferenceType type;
    private String reason;
    private String localStatus;
    private Money localAmount;
    private String channelStatus;
    private Money channelAmount;
    private long createdAt;

    public ReconciliationResult() {
    }

    public ReconciliationResult snapshot() {
        ReconciliationResult copy = new ReconciliationResult();
        copy.id = this.id;
        copy.reconcileBatchId = this.reconcileBatchId;
        copy.bizId = this.bizId;
        copy.type = this.type;
        copy.reason = this.reason;
        copy.localStatus = this.localStatus;
        copy.localAmount = this.localAmount;
        copy.channelStatus = this.channelStatus;
        copy.channelAmount = this.channelAmount;
        copy.createdAt = this.createdAt;
        return copy;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getReconcileBatchId() {
        return reconcileBatchId;
    }

    public void setReconcileBatchId(String reconcileBatchId) {
        this.reconcileBatchId = reconcileBatchId;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    public DifferenceType getType() {
        return type;
    }

    public void setType(DifferenceType type) {
        this.type = type;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getLocalStatus() {
        return localStatus;
    }

    public void setLocalStatus(String localStatus) {
        this.localStatus = localStatus;
    }

    public Money getLocalAmount() {
        return localAmount;
    }

    public void setLocalAmount(Money localAmount) {
        this.localAmount = localAmount;
    }

    public String getChannelStatus() {
        return channelStatus;
    }

    public void setChannelStatus(String channelStatus) {
        this.channelStatus = channelStatus;
    }

    public Money getChannelAmount() {
        return channelAmount;
    }

    public void setChannelAmount(Money channelAmount) {
        this.channelAmount = channelAmount;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
