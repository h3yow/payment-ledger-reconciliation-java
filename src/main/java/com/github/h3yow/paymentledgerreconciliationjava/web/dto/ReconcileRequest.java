package com.github.h3yow.paymentledgerreconciliationjava.web.dto;

import java.util.List;

/** 对账请求体（纯类型）。 */
public record ReconcileRequest(String batchId, List<ChannelRecordData> records) {
}
