package com.github.h3yow.paymentledgerreconciliationjava.repository;

import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelSettlementRecord;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 渠道结算数据内存仓库（本地模拟，不接入任何渠道/外部服务）。
 * 数据可由对账接口直接带入，也可预置在此供演示。
 */
@Repository
public class ChannelSettlementRecordRepository {

    private final ConcurrentMap<String, ChannelSettlementRecord> records = new ConcurrentHashMap<>();

    public ChannelSettlementRecord findByBizId(String bizId) {
        return records.get(bizId);
    }

    public void save(ChannelSettlementRecord record) {
        records.put(record.getBizId(), record);
    }

    public void clear() {
        records.clear();
    }

    public Collection<ChannelSettlementRecord> findAll() {
        return new ArrayList<>(records.values());
    }
}
