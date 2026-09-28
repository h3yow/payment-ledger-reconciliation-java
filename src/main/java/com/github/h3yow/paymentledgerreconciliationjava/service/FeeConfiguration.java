package com.github.h3yow.paymentledgerreconciliationjava.service;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 手续费费率配置（本地可变配置，等价于“可改的本地配置方式”）。
 *
 * <p>口径：
 * <ul>
 *   <li>费率单位为万分比（basis points）：{@code 60} 表示万分之六十 = 0.60%；
 *       合法区间 {@code [0, 10000)}（不允许 100% 及以上，否则净额不为正）；</li>
 *   <li>{@code enabled=false} 时新受理的支付默认不带手续费（支付请求显式携带费率时仍可逐单指定）；</li>
 *   <li>默认手续费收款账户为 {@link LedgerService#ACCT_FEE}，可随时改到其它已开户账户；</li>
 *   <li>{@link #snapshot()} 返回不可变整体快照；受理一笔支付时在临界区取一次快照，
 *       之后即使 {@link #update} 调整费率，已受理支付的手续费/净额/对账结论都不变。</li>
 * </ul>
 * 读写均在 synchronized 内完成，保证读到的费率与收款账户来自同一次配置。
 */
@Component
public class FeeConfiguration {

    /** 费率合法上界（不含）：10000bp = 100%。 */
    public static final int MAX_RATE_BP_EXCLUSIVE = 10_000;

    /** 一笔配置的不可变快照。 */
    public record Snapshot(boolean enabled, Integer feeRateBp, String feeAccountNo) {
    }

    private boolean enabled;
    private int feeRateBp;
    private String feeAccountNo;

    public FeeConfiguration() {
        this.enabled = false;
        this.feeRateBp = 0;
        this.feeAccountNo = LedgerService.ACCT_FEE;
    }

    /** 当前配置的整体快照（原子一致）。 */
    public synchronized Snapshot snapshot() {
        return new Snapshot(enabled, feeRateBp, feeAccountNo);
    }

    /**
     * 更新配置。
     *
     * @param enabled      是否对未显式带费率的支付默认计手续费
     * @param feeRateBp    万分比费率，启用时必须在 [0,10000)
     * @param feeAccountNo 手续费收款账户，必填非空白；账户存在性由受理侧前置校验
     */
    public synchronized Snapshot update(boolean enabled, Integer feeRateBp, String feeAccountNo) {
        if (feeAccountNo == null || feeAccountNo.isBlank()) {
            throw new InvalidParameterException("手续费收款账户不能为空");
        }
        if (feeRateBp != null && (feeRateBp < 0 || feeRateBp >= MAX_RATE_BP_EXCLUSIVE)) {
            throw new InvalidParameterException(
                    "费率非法：万分比必须在 [0," + MAX_RATE_BP_EXCLUSIVE + ") 之间，实际=" + feeRateBp);
        }
        if (enabled && feeRateBp == null) {
            throw new InvalidParameterException("启用手续费时必须提供费率（万分比）");
        }
        this.enabled = enabled;
        if (feeRateBp != null) {
            this.feeRateBp = feeRateBp;
        }
        this.feeAccountNo = feeAccountNo.trim();
        return snapshot();
    }

    /** 便捷关闭：之后新受理的默认支付不带手续费。 */
    public synchronized Snapshot disable() {
        this.enabled = false;
        return snapshot();
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized int getFeeRateBp() {
        return feeRateBp;
    }

    public synchronized String getFeeAccountNo() {
        return Objects.requireNonNull(feeAccountNo);
    }
}
