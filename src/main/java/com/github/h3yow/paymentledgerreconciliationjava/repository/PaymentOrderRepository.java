package com.github.h3yow.paymentledgerreconciliationjava.repository;

import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentOrder;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentStatus;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 支付单内存仓库。
 *
 * <p>并发语义：
 * <ul>
 *   <li>{@link #insertIfAbsent} 基于 {@code putIfAbsent} 原子判重——同一 bizId 的并发提交，
 *       只有一个线程插入成功；</li>
 *   <li>{@link #compareAndUpdateStatus} 基于 {@code compute} 在桶锁内完成"读-判-写"，
 *       避免两个线程把同一笔单同时改成不同终态。</li>
 * </ul>
 * 全程不接入任何外部存储，进程内即可复现并发竞态。
 */
@Repository
public class PaymentOrderRepository {

    private final ConcurrentMap<String, PaymentOrder> orders = new ConcurrentHashMap<>();

    /** 返回快照；不存在返回 null。 */
    public PaymentOrder findByBizId(String bizId) {
        PaymentOrder o = orders.get(bizId);
        return o == null ? null : o.snapshot();
    }

    /**
     * 仅当 bizId 不存在时插入。
     *
     * @return true=本次插入成功（赢得受理竞争）；false=已存在（重复/并发重复提交）
     */
    public boolean insertIfAbsent(PaymentOrder order) {
        return orders.putIfAbsent(order.getBizId(), order) == null;
    }

    /**
     * 状态机原子推进：仅当当前状态等于 expected 时改为 target。
     *
     * @return true=推进成功；false=状态冲突（不存在或已被其他线程推进）
     */
    public boolean compareAndUpdateStatus(String bizId, PaymentStatus expected,
                                          PaymentStatus target, String failReason) {
        boolean[] updated = {false};
        // 原地变更（不替换 map value）：在 compute 的桶锁内完成"读-判-写"，
        // 这样并发迭代 findAll 时不会同时观察到新旧两个 value（弱一致迭代器下的重复）。
        orders.computeIfPresent(bizId, (key, current) -> {
            if (current.getStatus() == expected) {
                current.setStatus(target);
                current.setFailReason(failReason);
                current.setUpdatedAt(System.currentTimeMillis());
                updated[0] = true;
            }
            return current;
        });
        return updated[0];
    }

    public List<PaymentOrder> findAll() {
        List<PaymentOrder> all = new ArrayList<>(orders.size());
        for (PaymentOrder o : orders.values()) {
            all.add(o.snapshot());
        }
        return all;
    }
}
