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
    /** 受理当时锁定的手续费清算快照；null 表示无手续费的老口径支付。随指令不可变。 */
    private final FeeSnapshot feeSnapshot;
    private PaymentStatus status;
    private Instant finishedAt;
    private String failReason;

    public PaymentInstruction(String id, String businessId, String payerAccountNo,
                              String payeeAccountNo, Money amount, Instant createdAt) {
        this(id, businessId, payerAccountNo, payeeAccountNo, amount, createdAt, null);
    }

    public PaymentInstruction(String id, String businessId, String payerAccountNo,
                              String payeeAccountNo, Money amount, Instant createdAt,
                              FeeSnapshot feeSnapshot) {
        this.id = Objects.requireNonNull(id);
        this.businessId = Objects.requireNonNull(businessId);
        this.payerAccountNo = Objects.requireNonNull(payerAccountNo);
        this.payeeAccountNo = payeeAccountNo;
        this.amount = Objects.requireNonNull(amount);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.feeSnapshot = feeSnapshot;
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

    /** 手续费清算快照（费率、手续费账户、手续费、净额、原始费额、尾差）；无手续费时为 null。 */
    public FeeSnapshot getFeeSnapshot() {
        return feeSnapshot;
    }

    /** 到分净额：有手续费时取扣费后净额，否则净额即本金。 */
    public Money getNetAmount() {
        return feeSnapshot == null ? amount : feeSnapshot.netAmount();
    }

    /** 到分手续费：无手续费支付为 0.00。 */
    public Money getFee() {
        return feeSnapshot == null ? Money.of(java.math.BigDecimal.ZERO) : feeSnapshot.fee();
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
