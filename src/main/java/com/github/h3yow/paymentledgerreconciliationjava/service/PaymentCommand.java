package com.github.h3yow.paymentledgerreconciliationjava.service;

/**
 * 支付受理命令（服务层输入）。
 *
 * @param businessId     业务幂等键，必填
 * @param payerAccountNo 付款方账户号，必填
 * @param payeeAccountNo 收款方账户号，必填
 * @param amountText     金额字符串（十进制，最多 2 位小数，必须为正）
 */
public record PaymentCommand(
        String businessId,
        String payerAccountNo,
        String payeeAccountNo,
        String amountText
) {
}
