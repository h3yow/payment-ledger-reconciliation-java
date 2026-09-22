package com.github.h3yow.paymentledgerreconciliationjava.repository;

import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentInstruction;

import java.util.List;
import java.util.Optional;

/** 支付指令仓储。businessId 唯一，提供唯一插入与按 id 集合查询。 */
public interface PaymentRepository {

    /** 不存在则插入并返回空；已存在返回现有指令（由服务层判定幂等/冲突）。 */
    Optional<PaymentInstruction> insertIfAbsent(PaymentInstruction instruction);

    Optional<PaymentInstruction> findByBusinessId(String businessId);

    List<PaymentInstruction> findAllByBusinessIds(List<String> businessIds);

    List<PaymentInstruction> findAll();
}
