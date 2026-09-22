package com.github.h3yow.paymentledgerreconciliationjava.repository;

import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerAccount;
import com.github.h3yow.paymentledgerreconciliationjava.domain.Money;
import com.github.h3yow.paymentledgerreconciliationjava.exception.AccountNotFoundException;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 账户内存仓库。
 *
 * <p>{@link #applyDebit}/{@link #applyCredit} 用 {@code compute} 在桶锁内完成余额累加，
 * 保证同一账户的并发过账不会丢失更新；跨账户的"要么全部发生、要么全部不发生"
 * 由 {@code LedgerService} 的全局过账锁保证（本地单机模型，简单且结论可解释）。
 */
@Repository
public class LedgerAccountRepository {

    private final ConcurrentMap<String, LedgerAccount> accounts = new ConcurrentHashMap<>();

    /** 初始化标准账户；重复创建幂等。 */
    public void createIfAbsent(String code, String name) {
        accounts.computeIfAbsent(code, c -> new LedgerAccount(c, name));
    }

    public LedgerAccount findByCode(String accountCode) {
        LedgerAccount a = accounts.get(accountCode);
        return a == null ? null : a.snapshot();
    }

    /** 需要在过账锁内拿到真实可变对象时使用，不对外暴露到服务层以外。 */
    LedgerAccount mutable(String accountCode) {
        return accounts.get(accountCode);
    }

    /** 借方累加并推进版本号；账户不存在即拒绝（原因可区分）。 */
    public void applyDebit(String accountCode, Money amount) {
        accounts.compute(accountCode, (code, a) -> {
            if (a == null) {
                throw new AccountNotFoundException("账户不存在: " + code);
            }
            LedgerAccount next = a.snapshot();
            next.setDebitBalance(a.getDebitBalance().plus(amount));
            next.setVersion(a.getVersion() + 1);
            return next;
        });
    }

    /** 贷方累加并推进版本号。 */
    public void applyCredit(String accountCode, Money amount) {
        accounts.compute(accountCode, (code, a) -> {
            if (a == null) {
                throw new AccountNotFoundException("账户不存在: " + code);
            }
            LedgerAccount next = a.snapshot();
            next.setCreditBalance(a.getCreditBalance().plus(amount));
            next.setVersion(a.getVersion() + 1);
            return next;
        });
    }

    /** 回滚用：冲销一次借方/贷方累加。 */
    public void reverseDebit(String accountCode, Money amount) {
        accounts.computeIfPresent(accountCode, (code, a) -> {
            LedgerAccount next = a.snapshot();
            next.setDebitBalance(a.getDebitBalance().minus(amount));
            next.setVersion(a.getVersion() + 1);
            return next;
        });
    }

    public void reverseCredit(String accountCode, Money amount) {
        accounts.computeIfPresent(accountCode, (code, a) -> {
            LedgerAccount next = a.snapshot();
            next.setCreditBalance(a.getCreditBalance().minus(amount));
            next.setVersion(a.getVersion() + 1);
            return next;
        });
    }

    public List<LedgerAccount> findAll() {
        List<LedgerAccount> all = new ArrayList<>(accounts.size());
        for (LedgerAccount a : accounts.values()) {
            all.add(a.snapshot());
        }
        return all;
    }
}
