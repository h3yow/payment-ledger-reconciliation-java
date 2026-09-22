package com.github.h3yow.paymentledgerreconciliationjava.web.dto;

/** 对账入参中的单条渠道结算数据（纯类型，本地模拟）。 */
public record ChannelRecordData(String bizId, String amount, String status, String settleBatchId) {
}
