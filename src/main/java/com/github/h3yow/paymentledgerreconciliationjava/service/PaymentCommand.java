package com.github.h3yow.paymentledgerreconciliationjava.service;

/**
 * 支付受理命令（服务层输入）。
 *
 * @param businessId     业务幂等键，必填
 * @param payerAccountNo 付款方账户号，必填
 * @param payeeAccountNo 收款方账户号，必填
 * @param amountText     金额字符串（十进制，最多 2 位小数，必须为正）
 * @param feeRateBp      可选：本笔手续费万分比费率（如 60 = 0.60%）；
 *                       非空时优先于本地默认配置；为空时按本地当前配置决定是否计手续费
 * @param feeAccountNo   可选：本笔手续费收款账户；随 feeRateBp 一起指定，缺省用本地配置账户
 */
public record PaymentCommand(
        String businessId,
        String payerAccountNo,
        String payeeAccountNo,
        String amountText,
        Integer feeRateBp,
        String feeAccountNo
) {
    /** 兼容老请求：不带手续费要素。 */
    public PaymentCommand(String businessId, String payerAccountNo,
                          String payeeAccountNo, String amountText) {
        this(businessId, payerAccountNo, payeeAccountNo, amountText, null, null);
    }
}
