package com.github.h3yow.paymentledgerreconciliationjava.repository;

import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerEntry;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 账本分录内存仓储。以凭证号为单位整体写入（一张凭证 = 一组平衡分录），
 * 用 computeIfAbsent 保证同一凭证号只会成功写入一次，构成过账幂等的持久防线。
 */
@Repository
public class InMemoryLedgerEntryRepository implements LedgerEntryRepository {

    private final ConcurrentMap<String, List<LedgerEntry>> byVoucher = new ConcurrentHashMap<>();

    @Override
    public boolean saveVoucher(String voucherNo, List<LedgerEntry> entries) {
        // 防御性拷贝 + 不可变视图，凭证落库后不可被外部修改，保证流水可回溯
        List<LedgerEntry> snapshot = List.copyOf(entries);
        List<LedgerEntry> previous = byVoucher.putIfAbsent(voucherNo, snapshot);
        return previous == null;
    }

    @Override
    public List<LedgerEntry> findByVoucherNo(String voucherNo) {
        return byVoucher.getOrDefault(voucherNo, List.of());
    }

    @Override
    public List<LedgerEntry> findAll() {
        List<LedgerEntry> all = new ArrayList<>();
        for (Map.Entry<String, List<LedgerEntry>> e : byVoucher.entrySet()) {
            all.addAll(e.getValue());
        }
        return List.copyOf(all);
    }
}
