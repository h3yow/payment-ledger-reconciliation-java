package com.github.h3yow.paymentledgerreconciliationjava.service;

import com.github.h3yow.paymentledgerreconciliationjava.domain.Account;
import com.github.h3yow.paymentledgerreconciliationjava.domain.AccountType;
import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerEntry;
import com.github.h3yow.paymentledgerreconciliationjava.domain.Money;
import com.github.h3yow.paymentledgerreconciliationjava.repository.AccountRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryAccountRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 账本过账服务（本地内存，无外部依赖）。
 *
 * <p>原子性口径：一张凭证的全部处理在单一过账锁（{@code postLock}）内完成，顺序为
 * “校验借贷平衡 → 逐行更新余额（可被故障钩子打断）→ 全局余额守恒校验 → 凭证整体落库”。
 * 任一步骤抛出异常：已更新的余额按相反方向全额冲正、凭证不落库，
 * 因此<b>不会留下半成品分录，也不会只更新部分余额</b>。
 *
 * <p>幂等防线：凭证号（业务上取支付 businessId）唯一，{@code saveVoucher} 是 CAS；
 * 重复过账同一凭证号会以 STATUS_CONFLICT 原因拒绝。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    /** 预置：付款方资产类账户（在途资金）。 */
    public static final String ACCT_PAYER = "1001";
    /** 预置：收款方负债类账户（待结算款）。 */
    public static final String ACCT_PAYEE = "2001";
    /** 预置：结算方手续费收入类账户（手续费净额结算的入账目标）。 */
    public static final String ACCT_FEE = "4001";

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final InMemoryAccountRepository accountStore;

    /** 单一过账锁：串行化全部余额变更，消除并发交错导致的中间态。 */
    private final Object postLock = new Object();

    /** 测试用故障钩子，默认不安装。 */
    private volatile FailureHook failureHook;

    public LedgerService(AccountRepository accountRepository, LedgerEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        if (accountRepository instanceof InMemoryAccountRepository store) {
            this.accountStore = store;
        } else {
            this.accountStore = null;
        }
        seedAccounts();
    }

    private void seedAccounts() {
        createAccount(ACCT_PAYER, AccountType.ASSET, "付款方在途资金", BigDecimal.ZERO);
        createAccount(ACCT_PAYEE, AccountType.LIABILITY, "收款方待结算款", BigDecimal.ZERO);
        createAccount(ACCT_FEE, AccountType.REVENUE, "结算方手续费收入", BigDecimal.ZERO);
        log.info("账本账户预置完成: {}({}), {}({}), {}({})", ACCT_PAYER, AccountType.ASSET,
                ACCT_PAYEE, AccountType.LIABILITY, ACCT_FEE, AccountType.REVENUE);
    }

    /** 供演示/测试显式开户；期初余额统一规范到 2 位小数。重复开户以状态冲突拒绝。 */
    public Account createAccount(String code, AccountType type, String name, BigDecimal openingBalance) {
        if (code == null || code.isBlank()) {
            throw new InvalidParameterException("账户号不能为空");
        }
        Objects.requireNonNull(type, "账户类型不能为空");
        BigDecimal opening = Money.round2(openingBalance == null ? BigDecimal.ZERO : openingBalance);
        if (accountRepository.findByCode(code).isPresent()) {
            throw new ConflictException("账户已存在，禁止重复开户: " + code);
        }
        Account account = new Account(code, type, name == null ? code : name, opening);
        if (accountStore != null) {
            accountStore.put(account);
        }
        return account;
    }

    /** 凭证中的一行：账户号 + 借方金额 + 贷方金额（正常一行只有一方为正，另一方为 0）。 */
    public record VoucherLine(String accountCode, BigDecimal debit, BigDecimal credit) {
    }

    /** 账户存在则返回，否则以参数非法拒绝（供受理侧在落指令前做前置校验）。 */
    public Account requireAccount(String code) {
        return accountRepository.findByCode(code)
                .orElseThrow(() -> new InvalidParameterException("账户不存在: " + code));
    }

    /**
     * 原子过账一张凭证。
     *
     * @throws InvalidParameterException 凭证为空、账户不存在、行金额非法或借贷不平衡
     * @throws ConflictException         凭证号已过账
     * @throws RuntimeException          过账中途失败（含测试注入故障）；此时余额已全额冲正、无分录残留
     */
    public void postVoucher(String voucherNo, List<VoucherLine> lines, String summary) {
        validateVoucher(voucherNo, lines);

        List<NormalizedLine> normalized = lines.stream().map(this::normalizeLine).toList();
        assertBalanced(normalized);

        synchronized (postLock) {
            // 锁内复查凭证幂等，避免并发线程同时通过前置校验
            if (!entryRepository.findByVoucherNo(voucherNo).isEmpty()) {
                throw new ConflictException("凭证已过账，拒绝重复过账: " + voucherNo);
            }
            List<NormalizedLine> applied = new ArrayList<>();
            try {
                boolean firstMutation = true;
                for (NormalizedLine line : normalized) {
                    Account account = accountRepository.getByCode(line.accountCode());
                    if (line.debit().signum() > 0) {
                        account.applyDebit(line.debit());
                    } else {
                        account.applyCredit(line.credit());
                    }
                    applied.add(line);
                    log.debug("凭证 {} 已更新账户余额: {}, 借={}, 贷={}",
                            voucherNo, line.accountCode(), line.debit(), line.credit());
                    if (firstMutation) {
                        firstMutation = false;
                        // 故障注入点：第一行余额更新之后、后续处理之前
                        FailureHook hook = failureHook;
                        if (hook != null) {
                            hook.afterFirstBalanceMutation(voucherNo);
                        }
                    }
                }

                BigDecimal total = totalBalanceAcrossAccounts();
                if (total.signum() != 0) {
                    throw new IllegalStateException("过账后全局余额不守恒，合计=" + total.toPlainString());
                }

                Instant now = Instant.now();
                List<LedgerEntry> entries = new ArrayList<>();
                for (int i = 0; i < normalized.size(); i++) {
                    NormalizedLine line = normalized.get(i);
                    entries.add(new LedgerEntry(
                            voucherNo + "-" + (i + 1), voucherNo, line.accountCode(),
                            line.debit(), line.credit(), summary, now));
                }
                boolean saved = entryRepository.saveVoucher(voucherNo, entries);
                if (!saved) {
                    throw new ConflictException("凭证已过账，拒绝重复过账: " + voucherNo);
                }
                log.info("凭证过账成功: voucherNo={}, 行数={}, 借贷合计={}",
                        voucherNo, normalized.size(), normalized.get(0).debit());
            } catch (RuntimeException ex) {
                // 全额反向冲正已改动的余额，保证账户余额与分录同成同败
                for (int i = applied.size() - 1; i >= 0; i--) {
                    NormalizedLine line = applied.get(i);
                    Account account = accountRepository.getByCode(line.accountCode());
                    if (line.debit().signum() > 0) {
                        account.applyCredit(line.debit());
                    } else {
                        account.applyDebit(line.credit());
                    }
                }
                log.warn("凭证过账失败并已全额冲正: voucherNo={}, 已回滚行数={}, 原因={}",
                        voucherNo, applied.size(), ex.getMessage());
                throw ex;
            }
        }
    }

    private void validateVoucher(String voucherNo, List<VoucherLine> lines) {
        if (voucherNo == null || voucherNo.isBlank()) {
            throw new InvalidParameterException("凭证号不能为空");
        }
        if (lines == null || lines.isEmpty()) {
            throw new InvalidParameterException("凭证分录不能为空: " + voucherNo);
        }
    }

    private NormalizedLine normalizeLine(VoucherLine raw) {
        if (raw == null || raw.accountCode() == null || raw.accountCode().isBlank()) {
            throw new InvalidParameterException("分录账户号不能为空");
        }
        // 账户必须已存在（锁外只做存在性校验，getByCode 在锁内再取一次）
        accountRepository.getByCode(raw.accountCode());

        BigDecimal debit = raw.debit() == null ? BigDecimal.ZERO : Money.round2(raw.debit());
        BigDecimal credit = raw.credit() == null ? BigDecimal.ZERO : Money.round2(raw.credit());
        if (debit.signum() < 0 || credit.signum() < 0) {
            throw new InvalidParameterException("分录金额不能为负: 账户=" + raw.accountCode());
        }
        if (debit.signum() == 0 && credit.signum() == 0) {
            throw new InvalidParameterException("分录借贷不能同时为零: 账户=" + raw.accountCode());
        }
        if (debit.signum() > 0 && credit.signum() > 0) {
            throw new InvalidParameterException("同一行分录不能借贷同时有金额: 账户=" + raw.accountCode());
        }
        return new NormalizedLine(raw.accountCode(), debit, credit);
    }

    private void assertBalanced(List<NormalizedLine> lines) {
        BigDecimal debitSum = BigDecimal.ZERO;
        BigDecimal creditSum = BigDecimal.ZERO;
        for (NormalizedLine line : lines) {
            debitSum = debitSum.add(line.debit());
            creditSum = creditSum.add(line.credit());
        }
        if (debitSum.compareTo(creditSum) != 0) {
            throw new InvalidParameterException(String.format(
                    "借贷不平衡，借方合计=%s，贷方合计=%s",
                    debitSum.toPlainString(), creditSum.toPlainString()));
        }
    }

    private record NormalizedLine(String accountCode, BigDecimal debit, BigDecimal credit) {
    }

    public List<LedgerEntry> findEntriesByVoucherNo(String voucherNo) {
        return entryRepository.findByVoucherNo(voucherNo);
    }

    public List<LedgerEntry> findAllEntries() {
        return entryRepository.findAll();
    }

    public List<Account> findAllAccounts() {
        return accountRepository.findAll();
    }

    /** 全局守恒口径：所有账户净额（借−贷）之和必须恒为 0。 */
    public BigDecimal totalBalanceAcrossAccounts() {
        BigDecimal total = BigDecimal.ZERO.setScale(Money.SCALE);
        for (Account account : accountRepository.findAll()) {
            total = total.add(account.getBalance());
        }
        return total;
    }

    /** 安装故障注入钩子（仅测试使用，生产代码不调用）。 */
    public void setFailureHook(FailureHook hook) {
        this.failureHook = hook;
    }

    /** 故障注入钩子：第一行余额更新成功后回调，抛出异常即可模拟“过账中途失败”。 */
    public interface FailureHook {
        void afterFirstBalanceMutation(String voucherNo);
    }
}
