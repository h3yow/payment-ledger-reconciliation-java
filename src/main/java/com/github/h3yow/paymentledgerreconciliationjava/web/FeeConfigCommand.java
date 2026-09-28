package com.github.h3yow.paymentledgerreconciliationjava.web;

/**
 * 修改本地手续费配置的请求体。
 *
 * @param enabled      是否对未显式带费率的新支付默认计手续费；null 视为 false
 * @param feeRateBp    万分比费率，[0,10000)；启用时必填
 * @param feeAccountNo 手续费收款账户；留空表示沿用当前配置账户
 */
public record FeeConfigCommand(
        Boolean enabled,
        Integer feeRateBp,
        String feeAccountNo
) {
}
