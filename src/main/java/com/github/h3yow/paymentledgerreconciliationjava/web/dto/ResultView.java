package com.github.h3yow.paymentledgerreconciliationjava.web.dto;

/** 单条对账结论视图（含判定依据 reason）。 */
public record ResultView(String bizId, String type, String reason,
                         String localStatus, String localAmount,
                         String channelStatus, String channelAmount) {
}
