package com.github.h3yow.paymentledgerreconciliationjava.domain;

import java.util.List;

/**
 * 一次对账批次的汇总快照：批次号幂等，结果列表为该批次的完整结论。
 */
public class ReconciliationBatch {

    private String batchId;
    private List<ReconciliationResult> results;
    private long createdAt;

    public ReconciliationBatch() {
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public List<ReconciliationResult> getResults() {
        return results;
    }

    public void setResults(List<ReconciliationResult> results) {
        this.results = results;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
