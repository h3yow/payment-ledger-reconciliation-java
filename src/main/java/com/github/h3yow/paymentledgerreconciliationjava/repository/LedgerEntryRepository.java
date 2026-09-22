package com.github.h3yow.paymentledgerreconciliationjava.repository;

import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerEntry;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分录流水内存仓库。
 *
 * <p>三重索引：entryId（全局自增）、postingId（过账幂等键）、bizId（业务可回溯）。
 * 过账失败时由 {@code LedgerService} 调用 {@link #deleteByPostingId} 连同余额一起回滚，
 * 不留下任何半成品分录。
 */
@Repository
public class LedgerEntryRepository {

    private final AtomicLong sequence = new AtomicLong(0);
    private final ConcurrentMap<Long, LedgerEntry> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<LedgerEntry>> byPostingId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<LedgerEntry>> byBizId = new ConcurrentHashMap<>();

    /** 过账编号是否已存在（过账幂等）。 */
    public boolean existsByPostingId(String postingId) {
        return byPostingId.containsKey(postingId);
    }

    /** 写入一条分录，分配全局自增流水号。 */
    public long save(LedgerEntry entry) {
        long id = sequence.incrementAndGet();
        entry.setId(id);
        byId.put(id, entry);
        byPostingId.computeIfAbsent(entry.getPostingId(), k -> new ArrayList<>()).add(entry);
        byBizId.computeIfAbsent(entry.getBizId(), k -> new ArrayList<>()).add(entry);
        return id;
    }

    /** 回滚整笔过账的全部分录（过账中途失败时调用）。 */
    public void deleteByPostingId(String postingId) {
        List<LedgerEntry> removed = byPostingId.remove(postingId);
        if (removed == null) {
            return;
        }
        for (LedgerEntry e : removed) {
            byId.remove(e.getId());
            byBizId.computeIfPresent(e.getBizId(), (k, list) -> {
                list.removeIf(x -> x.getId() == e.getId());
                return list;
            });
        }
    }

    public List<LedgerEntry> findByPostingId(String postingId) {
        return copy(byPostingId.get(postingId));
    }

    public List<LedgerEntry> findByBizId(String bizId) {
        return copy(byBizId.get(bizId));
    }

    public List<LedgerEntry> findAll() {
        return new ArrayList<>(byId.values());
    }

    private static List<LedgerEntry> copy(List<LedgerEntry> source) {
        return source == null ? List.of() : new ArrayList<>(source);
    }
}
