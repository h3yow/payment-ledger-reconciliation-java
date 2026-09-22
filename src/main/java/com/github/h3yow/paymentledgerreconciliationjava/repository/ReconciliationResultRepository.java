package com.github.h3yow.paymentledgerreconciliationjava.repository;

import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationBatch;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationResult;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对账批次/结论内存仓库。
 *
 * <p>批次号做幂等键：同一 batchId 重复发起对账被拒绝，历史结论原样保留、随时可复现查询；
 * 结论一经写入不可修改（append-only），杜绝"重复处理"或"结论被悄悄改写"。
 */
@Repository
public class ReconciliationResultRepository {

    private final AtomicLong resultSequence = new AtomicLong(0);
    private final ConcurrentMap<String, ReconciliationBatch> batches = new ConcurrentHashMap<>();

    public boolean existsBatch(String batchId) {
        return batches.containsKey(batchId);
    }

    /** 幂等插入批次；已存在返回 false。 */
    public boolean insertBatchIfAbsent(ReconciliationBatch batch) {
        return batches.putIfAbsent(batch.getBatchId(), batch) == null;
    }

    /** 分配流水号后归入批次结果。 */
    public long appendResult(ReconciliationResult result) {
        long id = resultSequence.incrementAndGet();
        result.setId(id);
        ReconciliationBatch batch = batches.get(result.getReconcileBatchId());
        if (batch != null) {
            batch.getResults().add(result);
        }
        return id;
    }

    public ReconciliationBatch findBatch(String batchId) {
        ReconciliationBatch batch = batches.get(batchId);
        if (batch == null) {
            return null;
        }
        ReconciliationBatch copy = new ReconciliationBatch();
        copy.setBatchId(batch.getBatchId());
        copy.setCreatedAt(batch.getCreatedAt());
        List<ReconciliationResult> results = new ArrayList<>();
        for (ReconciliationResult r : batch.getResults()) {
            results.add(r.snapshot());
        }
        copy.setResults(results);
        return copy;
    }

    public List<ReconciliationResult> findResultsByBatchId(String batchId) {
        ReconciliationBatch batch = batches.get(batchId);
        if (batch == null) {
            return List.of();
        }
        List<ReconciliationResult> results = new ArrayList<>();
        for (ReconciliationResult r : batch.getResults()) {
            results.add(r.snapshot());
        }
        return results;
    }

    public List<ReconciliationBatch> findAllBatches() {
        List<ReconciliationBatch> all = new ArrayList<>();
        for (String batchId : batches.keySet()) {
            all.add(findBatch(batchId));
        }
        return all;
    }
}
