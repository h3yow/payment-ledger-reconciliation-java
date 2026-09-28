package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerEntry;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentInstruction;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryAccountRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryLedgerEntryRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryPaymentRepository;
import com.github.h3yow.paymentledgerreconciliationjava.service.ConflictException;
import com.github.h3yow.paymentledgerreconciliationjava.service.DuplicateRequestException;
import com.github.h3yow.paymentledgerreconciliationjava.service.FeePolicyService;
import com.github.h3yow.paymentledgerreconciliationjava.service.LedgerService;
import com.github.h3yow.paymentledgerreconciliationjava.service.PaymentCommand;
import com.github.h3yow.paymentledgerreconciliationjava.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 带手续费净额结算下的幂等与并发强度：
 * 同一 businessId 并发/串行重复只能有一笔有效结果，手续费、净额、三行凭证完全一致；
 * 篡改费率/手续费账户为内容冲突；手续费凭证过账中途失败不留半成品。
 */
class FeeIdempotencyConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(FeeIdempotencyConcurrencyTest.class);

    private InMemoryPaymentRepository paymentRepository;
    private InMemoryLedgerEntryRepository entryRepository;
    private InMemoryAccountRepository accountRepository;
    private LedgerService ledgerService;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentRepository = new InMemoryPaymentRepository();
        entryRepository = new InMemoryLedgerEntryRepository();
        accountRepository = new InMemoryAccountRepository();
        ledgerService = new LedgerService(accountRepository, entryRepository);
        paymentService = new PaymentService(paymentRepository, ledgerService, new FeePolicyService());
    }

    private PaymentCommand feeCommand(String businessId, String rate) {
        return new PaymentCommand(businessId, LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE,
                "100.00", rate, LedgerService.ACCT_FEE);
    }

    @Test
    @DisplayName("并发 20 线程提交同一带手续费 businessId：仅 1 笔成功，其余全部重复拒绝；手续费/净额/分录唯一且一致")
    void concurrentFeeSubmissionYieldsSingleIdenticalResult() throws Exception {
        int threads = 20;
        String businessId = "FEE-CONC-001";
        PaymentCommand command = feeCommand(businessId, "60"); // 手续费 0.60，净额 99.40

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        ConcurrentMap<String, AtomicInteger> reasons = new ConcurrentHashMap<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    paymentService.submit(command);
                    success.incrementAndGet();
                } catch (DuplicateRequestException e) {
                    reasons.computeIfAbsent("DUPLICATE_REQUEST", k -> new AtomicInteger()).incrementAndGet();
                } catch (Exception e) {
                    reasons.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        log.info("[判定依据] 成功={} 拒绝分类={}", success.get(), reasons);
        assertEquals(1, success.get());
        assertEquals(threads - 1,
                reasons.getOrDefault("DUPLICATE_REQUEST", new AtomicInteger()).get());

        // 唯一一条指令，费率快照口径唯一
        assertEquals(1, paymentService.findAll().size());
        PaymentInstruction p = paymentService.getByBusinessId(businessId);
        assertEquals(0, new BigDecimal("0.60").compareTo(p.getFee().amount()));
        assertEquals(0, new BigDecimal("99.40").compareTo(p.getNetAmount().amount()));

        // 唯一一张三行凭证，金额组合唯一
        var entries = entryRepository.findByVoucherNo(businessId);
        assertEquals(3, entries.size(), "并发下也只能有一张三行凭证");
        assertEquals(3, entryRepository.findAll().size(), "全库分录恰好 3 行，无重复过账");
        BigDecimal creditPayee = entries.stream()
                .filter(e -> e.accountCode().equals(LedgerService.ACCT_PAYEE))
                .map(LedgerEntry::credit).findFirst().orElseThrow();
        BigDecimal creditFee = entries.stream()
                .filter(e -> e.accountCode().equals(LedgerService.ACCT_FEE))
                .map(LedgerEntry::credit).findFirst().orElseThrow();
        assertEquals(0, creditPayee.compareTo(new BigDecimal("99.40")));
        assertEquals(0, creditFee.compareTo(new BigDecimal("0.60")));

        // 三个账户余额与并发无关、确定守恒
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum());
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_PAYER).getBalance()
                .compareTo(new BigDecimal("100.00")));
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_PAYEE).getBalance()
                .compareTo(new BigDecimal("-99.40")));
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_FEE).getBalance()
                .compareTo(new BigDecimal("-0.60")));
    }

    @Test
    @DisplayName("串行重复相同费用请求 => DUPLICATE；改费率/改手续费账户 => STATUS_CONFLICT，且冲突不改任何数据")
    void sequentialFeeDuplicateAndTamperedFeeAreDistinguishable() {
        PaymentCommand first = feeCommand("FEE-SEQ-1", "30");
        paymentService.submit(first);

        DuplicateRequestException dup = assertThrows(DuplicateRequestException.class,
                () -> paymentService.submit(first));
        assertEquals("POSTED", dup.getExistingStatus());

        // 篡改费率：内容冲突
        ConflictException byRate = assertThrows(ConflictException.class,
                () -> paymentService.submit(feeCommand("FEE-SEQ-1", "60")));
        log.info("[篡改费率] {}", byRate.getMessage());

        // 篡改手续费账户（开第二个收入账户）
        ledgerService.createAccount("4002",
                com.github.h3yow.paymentledgerreconciliationjava.domain.AccountType.REVENUE,
                "第二手续费收入", BigDecimal.ZERO);
        ConflictException byAccount = assertThrows(ConflictException.class,
                () -> paymentService.submit(new PaymentCommand(
                        "FEE-SEQ-1", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE,
                        "100.00", "30", "4002")));
        log.info("[篡改手续费账户] {}", byAccount.getMessage());

        // 原支付手续费口径不受冲突请求影响
        PaymentInstruction p = paymentService.getByBusinessId("FEE-SEQ-1");
        assertEquals(0, new BigDecimal("0.30").compareTo(p.getFee().amount()));
        assertEquals(LedgerService.ACCT_FEE, p.getFeeSnapshot().feeAccountCode());
        assertEquals(3, entryRepository.findByVoucherNo("FEE-SEQ-1").size());
    }

    @Test
    @DisplayName("带手续费凭证过账中途失败：三行余额全额冲正、零分录残留、指令 FAILED、重复只见终态")
    void feeVoucherFailureLeavesNoHalfDoneState() {
        String businessId = "FEE-FAIL-1";
        ledgerService.setFailureHook(v -> {
            throw new IllegalStateException("模拟手续费凭证过账到一半中断");
        });
        assertThrows(IllegalStateException.class,
                () -> paymentService.submit(feeCommand(businessId, "60")));

        for (String code : java.util.List.of(
                LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, LedgerService.ACCT_FEE)) {
            assertEquals(0, accountRepository.getByCode(code).getBalance().compareTo(BigDecimal.ZERO.setScale(2)),
                    "失败后账户 " + code + " 余额必须为 0");
        }
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum());
        assertTrue(entryRepository.findByVoucherNo(businessId).isEmpty());
        assertEquals(0, entryRepository.findAll().size());

        DuplicateRequestException dup = assertThrows(DuplicateRequestException.class,
                () -> paymentService.submit(feeCommand(businessId, "60")));
        assertEquals("FAILED", dup.getExistingStatus());
    }
}
