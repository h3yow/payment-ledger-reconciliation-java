package com.github.h3yow.paymentledgerreconciliationjava.web;

/**
 * 查询某笔支付的手续费结算快照时，该支付不含手续费要素（受理时未计手续费）。
 * 映射为 404 + FEE_SETTLEMENT_NOT_FOUND，与“支付本身不存在”（400 INVALID_PARAMETER）可区分。
 */
public class FeeSettlementNotFoundException extends RuntimeException {
    public FeeSettlementNotFoundException(String message) {
        super(message);
    }
}
