package com.github.h3yow.paymentledgerreconciliationjava.repository;

import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationBatch;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 对账批次内存仓储。批次结论不可变记录；save 以 batchId 做 CAS 去重。 */
@Repository
public class InMemoryReconciliationBatchRepository implements ReconciliationBatchRepository {

    private final ConcurrentMap<String, ReconciliationBatch> byBatchId = new ConcurrentHashMap<>();

    @Override
    public boolean save(ReconciliationBatch batch) {
        return byBatchId.putIfAbsent(batch.batchId(), batch) == null;
    }

    @Override
    public Optional<ReconciliationBatch> findByBatchId(String batchId) {
        return Optional.ofNullable(byBatchId.get(batchId));
    }

    @Override
    public List<ReconciliationBatch> findAll() {
        return byBatchId.values().stream()
                .sorted(Comparator.comparing(ReconciliationBatch::ranAt))
                .toList();
    }
}
