package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryAccountRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryLedgerEntryRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryPaymentRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryReconciliationBatchRepository;
import com.github.h3yow.paymentledgerreconciliationjava.service.ConflictException;
import com.github.h3yow.paymentledgerreconciliationjava.service.DuplicateRequestException;
import com.github.h3yow.paymentledgerreconciliationjava.service.InvalidParameterException;
import com.github.h3yow.paymentledgerreconciliationjava.service.LedgerService;
import com.github.h3yow.paymentledgerreconciliationjava.service.PaymentCommand;
import com.github.h3yow.paymentledgerreconciliationjava.service.PaymentService;
import com.github.h3yow.paymentledgerreconciliationjava.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等与并发：同一 businessId 的并发/重复提交只能产生一笔有效过账，
 * 且“重复请求 / 内容冲突 / 参数非法”三类拒绝原因可区分。
 */
class PaymentIdempotencyConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentIdempotencyConcurrencyTest.class);

    private InMemoryPaymentRepository paymentRepository;
    private InMemoryLedgerEntryRepository entryRepository;
    private LedgerService ledgerService;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentRepository = new InMemoryPaymentRepository();
        entryRepository = new InMemoryLedgerEntryRepository();
        ledgerService = new LedgerService(new InMemoryAccountRepository(), entryRepository);
        paymentService = new PaymentService(paymentRepository, ledgerService);
        // 装配对账服务仅为保证本测试上下文完整，核心断言在支付侧
        new ReconciliationService(paymentRepository, new InMemoryReconciliationBatchRepository());
    }

    @Test
    @DisplayName("并发 20 线程提交同一 businessId：仅 1 笔成功，19 笔重复拒绝，余额守恒")
    void concurrentDuplicateSubmissionYieldsSinglePosting() throws Exception {
        int threads = 20;
        String businessId = "BIZ-CONC-001";
        PaymentCommand command = new PaymentCommand(
                businessId, LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "100.00");
        log.info("[输入] businessId={}, {} -> {}, 金额=100.00, 并发线程数={}",
                businessId, LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        ConcurrentMap<String, AtomicInteger> rejectReasons = new ConcurrentHashMap<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    paymentService.submit(command);
                    success.incrementAndGet();
                } catch (DuplicateRequestException e) {
                    rejectReasons.computeIfAbsent("DUPLICATE_REQUEST", k -> new AtomicInteger()).incrementAndGet();
                } catch (Exception e) {
                    rejectReasons.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程应全部结束");

        log.info("[判定依据] 成功数={}, 拒绝分类={}", success.get(), rejectReasons);
        assertEquals(1, success.get(), "并发提交只能有一笔成功");
        assertEquals(threads - 1,
                rejectReasons.getOrDefault("DUPLICATE_REQUEST", new AtomicInteger()).get(),
                "其余全部必须是可区分的重复请求拒绝");

        // 数据层与账本层的“一笔”校验
        assertEquals(1, paymentService.findAll().size(), "本地只能有一条指令");
        assertEquals(2, entryRepository.findAll().size(), "一张平衡凭证恰好 2 行分录");
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum(),
                "全部账户余额之和必须为 0（借" + "100.00 − 贷100.00）");
        assertNotNull(paymentService.getByBusinessId(businessId));
    }

    @Test
    @DisplayName("过账失败 + 并发：赢家 FAILED，全部输家的重复拒绝只能观察到终态 FAILED，不出现 ACCEPTED")
    void concurrentFailureNeverExposesAcceptedToLosers() throws Exception {
        int threads = 16;
        String businessId = "BIZ-CONC-FAIL";
        ledgerService.setFailureHook(v -> {
            throw new IllegalStateException("模拟并发场景下赢家过账中途失败");
        });
        PaymentCommand command = new PaymentCommand(
                businessId, LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "12.34");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winnerFailure = new AtomicInteger();
        ConcurrentMap<String, AtomicInteger> rejectionStates = new ConcurrentHashMap<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    paymentService.submit(command);
                } catch (DuplicateRequestException e) {
                    rejectionStates.computeIfAbsent(e.getExistingStatus(), k -> new AtomicInteger()).incrementAndGet();
                } catch (IllegalStateException e) {
                    winnerFailure.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        log.info("[判定依据] 赢家失败数={}, 输家观察到的已有状态分布={}",
                winnerFailure.get(), rejectionStates);
        assertEquals(1, winnerFailure.get(), "恰好一个赢家失败");
        assertEquals(threads - 1,
                rejectionStates.getOrDefault("FAILED", new AtomicInteger()).get(),
                "所有输家必须观察到终态 FAILED");
        assertEquals(0,
                rejectionStates.getOrDefault("ACCEPTED", new AtomicInteger()).get(),
                "任何输家都不得观察到 ACCEPTED 瞬时态");
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum(), "失败后余额守恒");
        assertEquals(0, ledgerService.findAllEntries().size(), "失败后无任何分录残留");
    }

    @Test
    @DisplayName("串行重复提交相同内容 => DUPLICATE_REQUEST；不同内容 => STATUS_CONFLICT")
    void sequentialDuplicateAndConflictAreDistinguishable() {
        PaymentCommand first = new PaymentCommand(
                "BIZ-SEQ-001", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "55.25");
        paymentService.submit(first);

        DuplicateRequestException dup = assertThrows(DuplicateRequestException.class,
                () -> paymentService.submit(first));
        log.info("[重复请求判定] 相同负载再次提交 -> DUPLICATE_REQUEST, 已有状态={}", dup.getExistingStatus());

        PaymentCommand tampered = new PaymentCommand(
                "BIZ-SEQ-001", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "999.00");
        ConflictException conflict = assertThrows(ConflictException.class,
                () -> paymentService.submit(tampered));
        log.info("[冲突判定] 同 businessId 不同金额 -> STATUS_CONFLICT, 原因={}", conflict.getMessage());
    }

    @Test
    @DisplayName("非法参数分类可区分：空 businessId、3 位小数金额、非正金额、不存在账户、收付相同")
    void invalidParametersAreRejectedWithReason() {
        assertRejected(new PaymentCommand(" ", "1001", "2001", "1.00"), "businessId 为空");
        assertRejected(new PaymentCommand("B1", "1001", "2001", "1.005"), "金额超过 2 位小数");
        assertRejected(new PaymentCommand("B2", "1001", "2001", "0"), "金额非正");
        assertRejected(new PaymentCommand("B3", "1001", "2001", "-3.00"), "金额为负");
        assertRejected(new PaymentCommand("B4", "1001", "9999", "1.00"), "收款账户不存在");
        assertRejected(new PaymentCommand("B5", "1001", "1001", "1.00"), "收付款账户相同");
        assertEquals(0, paymentService.findAll().size(), "非法请求不得产生任何指令");
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum(), "非法请求后余额仍守恒");
    }

    private void assertRejected(PaymentCommand command, String scenario) {
        InvalidParameterException ex = assertThrows(InvalidParameterException.class,
                () -> paymentService.submit(command));
        log.info("[参数非法判定] 场景={} -> INVALID_PARAMETER, 原因={}", scenario, ex.getMessage());
    }
}
