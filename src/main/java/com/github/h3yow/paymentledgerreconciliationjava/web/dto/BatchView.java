package com.github.h3yow.paymentledgerreconciliationjava.web.dto;

import java.util.List;

/** 对账批次视图。 */
public record BatchView(String batchId, long createdAt, int total, List<ResultView> results) {
}
