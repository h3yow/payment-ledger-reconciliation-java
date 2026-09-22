package com.github.h3yow.paymentledgerreconciliationjava.repository;

import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationBatch;

import java.util.List;
import java.util.Optional;

/** 对账批次结果仓储，历史批次全部保留。 */
public interface ReconciliationBatchRepository {

    boolean save(ReconciliationBatch batch);

    Optional<ReconciliationBatch> findByBatchId(String batchId);

    List<ReconciliationBatch> findAll();
}
