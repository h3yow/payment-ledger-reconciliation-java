package com.github.h3yow.paymentledgerreconciliationjava.service;

/**
 * 支付受理命令（服务层输入）。
 *
 * @param businessId     业务幂等键，必填
 * @param payerAccountNo 付款方账户号，必填
 * @param payeeAccountNo 收款方账户号，必填
 * @param amountText     金额字符串（十进制，最多 2 位小数，必须为正）
 * @param feeRateText    可选：手续费费率（万分比 bp 字符串，最多 2 位小数，[0,10000)）；
 *                       为空表示不随请求携带费率，受理侧回落到 {@link FeePolicyService} 默认策略
 * @param feeAccountNo   可选：手续费收款账户号；携带费率时必填，回落默认策略时可空
 */
public record PaymentCommand(
        String businessId,
        String payerAccountNo,
        String payeeAccountNo,
        String amountText,
        String feeRateText,
        String feeAccountNo
) {

    /** 不带手续费信息的受理命令（老口径，与扩展前完全一致）。 */
    public PaymentCommand(String businessId, String payerAccountNo,
                          String payeeAccountNo, String amountText) {
        this(businessId, payerAccountNo, payeeAccountNo, amountText, null, null);
    }
}
