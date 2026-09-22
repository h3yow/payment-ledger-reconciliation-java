package com.github.h3yow.paymentledgerreconciliationjava.repository;

import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentInstruction;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 支付指令内存仓储。
 * businessId 唯一性由 {@link ConcurrentMap#putIfAbsent} 的原子 CAS 语义保证，
 * 并发提交同一业务键时只有一个线程写入成功，是受理幂等的最后防线。
 */
@Repository
public class InMemoryPaymentRepository implements PaymentRepository {

    private final ConcurrentMap<String, PaymentInstruction> byBusinessId = new ConcurrentHashMap<>();

    @Override
    public Optional<PaymentInstruction> insertIfAbsent(PaymentInstruction instruction) {
        // putIfAbsent：写入成功返回 null（键不存在）；键已存在返回旧值
        PaymentInstruction existing = byBusinessId.putIfAbsent(
                instruction.getBusinessId(), instruction);
        return Optional.ofNullable(existing);
    }

    @Override
    public Optional<PaymentInstruction> findByBusinessId(String businessId) {
        return Optional.ofNullable(byBusinessId.get(businessId));
    }

    @Override
    public List<PaymentInstruction> findAllByBusinessIds(List<String> businessIds) {
        return businessIds.stream()
                .map(byBusinessId::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public List<PaymentInstruction> findAll() {
        return byBusinessId.values().stream()
                .sorted(Comparator.comparing(PaymentInstruction::getCreatedAt))
                .toList();
    }
}
