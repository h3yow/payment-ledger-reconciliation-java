package com.github.h3yow.paymentledgerreconciliationjava.service;

import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerAccount;
import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerEntry;
import com.github.h3yow.paymentledgerreconciliationjava.domain.Money;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PostingLine;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PostingRequest;
import com.github.h3yow.paymentledgerreconciliationjava.exception.InvalidParameterException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.PostingException;
import com.github.h3yow.paymentledgerreconciliationjava.repository.LedgerAccountRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 复式记账服务。
 *
 * <p><b>过账口径</b>：
 * <ol>
 *   <li>postingId 幂等：同一过账编号重复提交直接拒绝（原因=过账编号重复），绝不重复入账；</li>
 *   <li>分录至少两条、金额必须为正、账户必须存在、同一笔内借贷合计相等，否则拒绝（原因可区分）；</li>
 *   <li>过账以一把全局锁串行化（本地单机模型），杜绝跨账户更新交错；</li>
 *   <li>余额更新与分录写入的"提交点"在最后；此前或此后任何步骤抛异常，
 *       都按反向操作把已做改动全部撤销，并删除半成品分录——
 *       即异常后系统状态与"这笔过账从未发生过"完全一致；</li>
 *   <li>入账后再次自检借贷平衡与余额非负，不平衡则回滚并抛错。</li>
 * </ol>
 */
@Service
public class LedgerService {

    private final LedgerAccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final ReentrantLock postingLock = new ReentrantLock();

    /**
     * 失败注入点：测试在"余额更新完成后、分录写入完成前"触发异常，
     * 用于验证半成品不会残留。默认空实现（正常链路永不触发）。
     */
    private volatile Consumer<PostingRequest> failureHook = request -> {
    };

    public LedgerService(LedgerAccountRepository accountRepository,
                         LedgerEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
    }

    /** 仅测试使用：注入中途失败钩子。 */
    public void setFailureHook(Consumer<PostingRequest> failureHook) {
        this.failureHook = failureHook == null ? request -> {
        } : failureHook;
    }

    public void post(PostingRequest request) {
        validate(request);

        postingLock.lock();
        boolean committed = false;
        boolean rolledBack = false;
        List<PostingLine> appliedBalance = new ArrayList<>();
        List<LedgerEntry> writtenEntries = new ArrayList<>();
        try {
            // 1) 锁内二次判重，防止并发下两笔相同 postingId 同时入账
            if (entryRepository.existsByPostingId(request.postingId())) {
                throw new PostingException("过账编号重复，拒绝重复入账: " + request.postingId());
            }

            // 2) 逐行更新余额，记录已做动作以便回滚
            for (PostingLine line : request.lines()) {
                if (line.isDebit()) {
                    accountRepository.applyDebit(line.getAccountCode(), line.getAmount());
                } else {
                    accountRepository.applyCredit(line.getAccountCode(), line.getAmount());
                }
                appliedBalance.add(line);

                // 3) 余额非负校验（负债类账户体现为贷方余额 >= 0）
                LedgerAccount acc = accountRepository.findByCode(line.getAccountCode());
                if (acc == null || acc.getDebitBalance().isNegative() || acc.getCreditBalance().isNegative()) {
                    throw new PostingException("账户余额为负，过账中止: " + line.getAccountCode());
                }
            }

            // === 测试故障注入点：模拟"余额已改、分录写到一半"的中途失败 ===
            failureHook.accept(request);

            // 4) 写分录流水
            long now = System.currentTimeMillis();
            for (PostingLine line : request.lines()) {
                LedgerEntry entry = new LedgerEntry();
                entry.setPostingId(request.postingId());
                entry.setBizId(request.bizId());
                entry.setAccountCode(line.getAccountCode());
                entry.setDebit(line.isDebit());
                entry.setAmount(line.getAmount());
                entry.setSummary(line.getSummary());
                entry.setCreatedAt(now);
                entryRepository.save(entry);
                writtenEntries.add(entry);
            }

            // 5) 提交后自检：该笔分录借贷必须平衡
            Money debitSum = Money.zero();
            Money creditSum = Money.zero();
            for (LedgerEntry e : entryRepository.findByPostingId(request.postingId())) {
                if (e.isDebit()) {
                    debitSum = debitSum.plus(e.getAmount());
                } else {
                    creditSum = creditSum.plus(e.getAmount());
                }
            }
            if (!debitSum.equals(creditSum)) {
                throw new PostingException("提交后自检发现借贷不平衡，触发回滚: " + request.postingId());
            }
            committed = true;
        } catch (RuntimeException ex) {
            rollback(request.postingId(), appliedBalance, writtenEntries);
            rolledBack = true;
            throw ex;
        } finally {
            // 双保险：若以未提交状态离开（理论上 catch 已覆盖），保证只回滚一次
            if (!committed && !rolledBack) {
                rollback(request.postingId(), appliedBalance, writtenEntries);
            }
            postingLock.unlock();
        }
    }

    private void rollback(String postingId, List<PostingLine> appliedBalance,
                          List<LedgerEntry> writtenEntries) {
        // 反向冲销已写分录：只在本次调用确实写过（哪怕只写了一半）时整笔删除，
        // 避免"重复 postingId 被拒"时误删此前已成功过账的历史分录
        if (!writtenEntries.isEmpty()) {
            entryRepository.deleteByPostingId(postingId);
        }
        // 反向冲销余额更新（后做的先冲）
        for (int i = appliedBalance.size() - 1; i >= 0; i--) {
            PostingLine line = appliedBalance.get(i);
            if (line.isDebit()) {
                accountRepository.reverseDebit(line.getAccountCode(), line.getAmount());
            } else {
                accountRepository.reverseCredit(line.getAccountCode(), line.getAmount());
            }
        }
    }

    private void validate(PostingRequest request) {
        if (request == null) {
            throw new InvalidParameterException("过账请求不能为空");
        }
        if (request.postingId() == null || request.postingId().isBlank()) {
            throw new InvalidParameterException("过账编号不能为空");
        }
        if (request.bizId() == null || request.bizId().isBlank()) {
            throw new InvalidParameterException("业务标识不能为空");
        }
        List<PostingLine> lines = request.lines();
        if (lines == null || lines.size() < 2) {
            throw new InvalidParameterException("一笔过账至少包含两条分录");
        }
        Money debitSum = Money.zero();
        Money creditSum = Money.zero();
        Set<String> accounts = new HashSet<>();
        for (PostingLine line : lines) {
            if (line == null) {
                throw new InvalidParameterException("分录行不能为空");
            }
            if (line.getAccountCode() == null || line.getAccountCode().isBlank()) {
                throw new InvalidParameterException("分录账户不能为空");
            }
            if (line.getAmount() == null || line.getAmount().isNegative() || line.getAmount().isZero()) {
                throw new InvalidParameterException("分录金额必须为正数: 账户=" + line.getAccountCode());
            }
            if (accountRepository.findByCode(line.getAccountCode()) == null) {
                throw new InvalidParameterException("分录账户不存在: " + line.getAccountCode());
            }
            accounts.add(line.getAccountCode());
            if (line.isDebit()) {
                debitSum = debitSum.plus(line.getAmount());
            } else {
                creditSum = creditSum.plus(line.getAmount());
            }
        }
        if (!debitSum.equals(creditSum)) {
            throw new InvalidParameterException(
                    "借贷不平衡，拒绝过账: 借方合计=" + debitSum + " 贷方合计=" + creditSum);
        }
    }

    public List<LedgerEntry> entriesOfBiz(String bizId) {
        return entryRepository.findByBizId(bizId);
    }

    public List<LedgerEntry> entriesOfPosting(String postingId) {
        return entryRepository.findByPostingId(postingId);
    }

    public List<LedgerEntry> allEntries() {
        return entryRepository.findAll();
    }

    public LedgerAccount account(String code) {
        return accountRepository.findByCode(code);
    }

    public List<LedgerAccount> accounts() {
        return accountRepository.findAll();
    }
}
