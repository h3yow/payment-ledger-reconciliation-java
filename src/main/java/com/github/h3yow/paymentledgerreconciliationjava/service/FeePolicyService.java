package com.github.h3yow.paymentledgerreconciliationjava.service;

import com.github.h3yow.paymentledgerreconciliationjava.domain.FeeRate;

import java.util.Optional;

/**
 * 本地手续费费率策略（进程内、可在线调整、无外部依赖）。
 *
 * <p>作用：当一笔支付请求本身没有携带费率/手续费账户时，受理侧从这里取
 * “受理当时生效”的默认策略；请求显式携带费率时以请求为准。无论费率来自请求还是默认，
 * <b>实际使用的费率都会在受理瞬间复制进 {@code FeeSnapshot} 随支付锁定</b>，
 * 之后这里怎么调整都不影响已受理支付。
 *
 * <p>线程口径：默认值用 volatile 引用保存不可变对象，读取无锁、写入原子可见；
 * 配置更新只影响更新之后才受理的支付。
 */
@org.springframework.stereotype.Service
public class FeePolicyService {

    private volatile FeeRate defaultRate;
    private volatile String defaultFeeAccount;

    public FeePolicyService() {
    }

    /** 当前默认费率；未配置时为空，表示无费率的支付必须在请求里显式携带费率。 */
    public Optional<FeeRate> getDefaultRate() {
        return Optional.ofNullable(defaultRate);
    }

    /** 当前默认手续费收款账户；未配置时为空。 */
    public Optional<String> getDefaultFeeAccount() {
        return Optional.ofNullable(defaultFeeAccount);
    }

    /**
     * 更新默认策略（只影响之后受理的支付）。
     *
     * @param rateText   默认费率（bp，十进制字符串，满足 {@link FeeRate} 精度/范围契约）
     * @param feeAccount 默认手续费收款账户号（非空；账户是否存在在受理时校验）
     * @throws InvalidParameterException 费率非法或账户为空
     */
    public synchronized void updateDefault(String rateText, String feeAccount) {
        final FeeRate rate;
        try {
            rate = FeeRate.of(rateText);
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterException("默认费率非法: " + e.getMessage());
        }
        if (feeAccount == null || feeAccount.isBlank()) {
            throw new InvalidParameterException("默认手续费收款账户不能为空");
        }
        this.defaultRate = rate;
        this.defaultFeeAccount = feeAccount.trim();
    }

    /** 清空默认策略（回到“请求必须自带费率”的口径），只影响之后受理的支付。 */
    public synchronized void clearDefault() {
        this.defaultRate = null;
        this.defaultFeeAccount = null;
    }
}
