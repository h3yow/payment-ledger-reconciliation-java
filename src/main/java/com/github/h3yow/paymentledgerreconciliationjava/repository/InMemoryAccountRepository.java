package com.github.h3yow.paymentledgerreconciliationjava.repository;

import com.github.h3yow.paymentledgerreconciliationjava.domain.Account;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 账户内存仓储。Map 本身的可见性由 ConcurrentHashMap 保证；
 * 账户对象内部余额的复合变更由 LedgerService 统一加过账锁串行化。
 */
@Repository
public class InMemoryAccountRepository implements AccountRepository {

    private final ConcurrentMap<String, Account> accounts = new ConcurrentHashMap<>();

    @Override
    public Optional<Account> findByCode(String code) {
        return Optional.ofNullable(accounts.get(code));
    }

    @Override
    public Account getByCode(String code) {
        Account account = accounts.get(code);
        if (account == null) {
            throw new IllegalArgumentException("账户不存在: " + code);
        }
        return account;
    }

    @Override
    public List<Account> findAll() {
        return List.copyOf(accounts.values());
    }

    /** 预置/注册账户（初始化用）；重复注册同号账户直接忽略，保证幂等启动。 */
    public void put(Account account) {
        accounts.putIfAbsent(account.getCode(), account);
    }
}
