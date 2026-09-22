package com.github.h3yow.paymentledgerreconciliationjava.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 支付指令。{@code businessId} 是业务幂等键，全局唯一。
 * <p>状态机：{@code ACCEPTED -> POSTED}（成功终态）；{@code ACCEPTED -> FAILED}（失败终态）。
 * 终态不可逆；FAILED 不允许用同一 businessId 重提，只能换业务键发起新指令。
 * 状态迁移方法包可见，仅由 PaymentService 调用，非法迁移直接拒绝（状态冲突可区分）。
 */
public final class PaymentInstruction {

    private final String id;
    private final String businessId;
    private final String payerAccountNo;
    private final String payeeAccountNo;
    private final Money amount;
    private final Instant createdAt;
    private PaymentStatus status;
    private Instant finishedAt;
    private String failReason;

    public PaymentInstruction(String id, String businessId, String payerAccountNo,
                              String payeeAccountNo, Money amount, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.businessId = Objects.requireNonNull(businessId);
        this.payerAccountNo = Objects.requireNonNull(payerAccountNo);
        this.payeeAccountNo = payeeAccountNo;
        this.amount = Objects.requireNonNull(amount);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.status = PaymentStatus.ACCEPTED;
    }

    public String getId() {
        return id;
    }

    public String getBusinessId() {
        return businessId;
    }

    public String getPayerAccountNo() {
        return payerAccountNo;
    }

    public String getPayeeAccountNo() {
        return payeeAccountNo;
    }

    public Money getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getFailReason() {
        return failReason;
    }

    /** 迁移到 POSTED；仅 PaymentService 可调用，且仅 ACCEPTED 可迁移。 */
    public void markPosted(Instant finishedAt) {
        if (status != PaymentStatus.ACCEPTED) {
            throw new IllegalStateException("指令已处于终态 " + status + "，不能过账成功: " + businessId);
        }
        this.status = PaymentStatus.POSTED;
        this.finishedAt = Objects.requireNonNull(finishedAt);
    }

    /** 迁移到 FAILED；仅 PaymentService 可调用，且仅 ACCEPTED 可迁移。 */
    public void markFailed(Instant finishedAt, String reason) {
        if (status != PaymentStatus.ACCEPTED) {
            throw new IllegalStateException("指令已处于终态 " + status + "，不能标记失败: " + businessId);
        }
        this.status = PaymentStatus.FAILED;
        this.finishedAt = Objects.requireNonNull(finishedAt);
        this.failReason = reason;
    }
}
