package com.github.h3yow.paymentledgerreconciliationjava.repository;

import com.github.h3yow.paymentledgerreconciliationjava.domain.Account;

import java.util.List;
import java.util.Optional;

/** 账户仓储（本地内存实现）。 */
public interface AccountRepository {

    Optional<Account> findByCode(String code);

    Account getByCode(String code);

    List<Account> findAll();
}
