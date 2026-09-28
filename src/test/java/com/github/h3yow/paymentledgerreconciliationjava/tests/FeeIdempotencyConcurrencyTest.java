package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.github.h3yow.paymentledgerreconciliationjava.domain.FeeSettlement;
import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerEntry;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentInstruction;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryAccountRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryLedgerEntryRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryPaymentRepository;
import com.github.h3yow.paymentledgerreconciliationjava.service.ConflictException;
import com.github.h3yow.paymentledgerreconciliationjava.service.DuplicateRequestException;
import com.github.h3yow.paymentledgerreconciliationjava.service.FeeConfiguration;
import com.github.h3yow.paymentledgerreconciliationjava.service.LedgerService;
import com.github.h3yow.paymentledgerreconciliationjava.service.PaymentCommand;
import com.github.h3yow.paymentledgerreconciliationjava.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手续费净额支付的并发与重复提交：同业务标识串行/并发都只有一笔有效结果，
 * 手续费、净额、账务完全一致；篡改费率或手续费账户 => STATUS_CONFLICT；
 * 并发期间费率配置变化不影响在途/已受理支付的结果。
 */
class FeeIdempotencyConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(FeeIdempotencyConcurrencyTest.class);

    private InMemoryPaymentRepository paymentRepository;
    private InMemoryLedgerEntryRepository entryRepository;
    private LedgerService ledgerService;
    private FeeConfiguration feeConfiguration;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentRepository = new InMemoryPaymentRepository();
        entryRepository = new InMemoryLedgerEntryRepository();
        ledgerService = new LedgerService(new InMemoryAccountRepository(), entryRepository);
        feeConfiguration = new FeeConfiguration();
        paymentService = new PaymentService(paymentRepository, ledgerService, feeConfiguration);
    }

    @Test
    @DisplayName("并发 20 线程提交同一带手续费 businessId：仅 1 笔成功，结果唯一，三行分录与余额守恒")
    void concurrentFeeSubmissionYieldsSingleIdenticalResult() throws Exception {
        int threads = 20;
        String businessId = "FEE-CONC-001";
        // 88.88 * 65bp = 0.57772 -> HALF_UP 0.58，净额 88.30
        PaymentCommand command = new PaymentCommand(
                businessId, "1001", "2001", "88.88", 65, "3001");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        ConcurrentMap<String, AtomicInteger> reasons = new ConcurrentHashMap<>();
        AtomicReference<FeeSettlement> winnerFee = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    PaymentInstruction p = paymentService.submit(command);
                    success.incrementAndGet();
                    winnerFee.set(p.getFeeSettlement());
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

        assertEquals(1, success.get(), "并发只能有一笔成功");
        assertEquals(threads - 1,
                reasons.getOrDefault("DUPLICATE_REQUEST", new AtomicInteger()).get());
        FeeSettlement fs = winnerFee.get();
        log.info("[判定依据] 唯一成功快照: 费率={}bp 手续费={} 净额={} 尾差={}",
                fs.feeRateBp(), fs.feeAmount(), fs.netAmount(), fs.roundingAdjustment());
        assertEquals(0, fs.feeAmount().compareTo(new BigDecimal("0.58")));
        assertEquals(0, fs.netAmount().compareTo(new BigDecimal("88.30")));

        // 账务唯一且平衡
        List<LedgerEntry> entries = entryRepository.findByVoucherNo(businessId);
        assertEquals(3, entries.size(), "一张三行手续费凭证");
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (LedgerEntry e : entries) {
            debit = debit.add(e.debit());
            credit = credit.add(e.credit());
        }
        assertEquals(0, debit.compareTo(new BigDecimal("88.88")));
        assertEquals(0, credit.compareTo(new BigDecimal("88.88")));
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum());
        assertEquals(1, paymentService.findAll().size());

        // 串行重复：拿到的快照与赢家完全一致
        PaymentInstruction stored = paymentService.getByBusinessId(businessId);
        assertEquals(fs, stored.getFeeSettlement(), "重复查询的手续费快照必须完全一致");
    }

    @Test
    @DisplayName("串行重复相同请求=>DUPLICATE；篡改费率或手续费账户=>STATUS_CONFLICT")
    void duplicateAndTamperedFeeFieldsAreDistinguishable() {
        PaymentCommand first = new PaymentCommand("FEE-SEQ-1", "1001", "2001", "100.00", 60, "3001");
        paymentService.submit(first);
        assertThrows(DuplicateRequestException.class, () -> paymentService.submit(first));

        // 另开一个手续费账户
        ledgerService.createAccount("3002",
                com.github.h3yow.paymentledgerreconciliationjava.domain.AccountType.LIABILITY,
                "另一个手续费账户", BigDecimal.ZERO);

        PaymentCommand tamperedRate = new PaymentCommand(
                "FEE-SEQ-1", "1001", "2001", "100.00", 61, "3001");
        ConflictException c1 = assertThrows(ConflictException.class,
                () -> paymentService.submit(tamperedRate));
        log.info("[费率篡改] {}", c1.getMessage());

        PaymentCommand tamperedAccount = new PaymentCommand(
                "FEE-SEQ-1", "1001", "2001", "100.00", 60, "3002");
        ConflictException c2 = assertThrows(ConflictException.class,
                () -> paymentService.submit(tamperedAccount));
        log.info("[手续费账户篡改] {}", c2.getMessage());

        // 改成无手续费要素也算内容冲突
        PaymentCommand noFee = new PaymentCommand("FEE-SEQ-1", "1001", "2001", "100.00");
        assertThrows(ConflictException.class, () -> paymentService.submit(noFee));
    }

    @Test
    @DisplayName("受理后修改费率配置，不改变这笔支付的手续费与净额；按受理当时费率重放仍是重复请求")
    void configChangeDuringProcessingDoesNotChangeAcceptedPayment() {
        feeConfiguration.update(true, 60, "3001");
        PaymentInstruction p = paymentService.submit(new PaymentCommand(
                "FEE-CFG-RACE", "1001", "2001", "100.00"));
        FeeSettlement at60 = p.getFeeSettlement();
        assertEquals(60, at60.feeRateBp());
        assertEquals(0, at60.feeAmount().compareTo(new BigDecimal("0.60")));

        // 改配置后，按受理当时费率 60bp 重放同一 businessId：仍判 DUPLICATE（落库快照是唯一真相），
        // 且取回的快照不受新配置影响
        feeConfiguration.update(true, 300, "3001");
        assertThrows(DuplicateRequestException.class, () -> paymentService.submit(
                new PaymentCommand("FEE-CFG-RACE", "1001", "2001", "100.00", 60, "3001")));
        FeeSettlement still = paymentService.getFeeSettlement("FEE-CFG-RACE");
        assertEquals(60, still.feeRateBp());
        assertEquals(0, still.feeAmount().compareTo(new BigDecimal("0.60")));
        assertEquals(0, still.netAmount().compareTo(new BigDecimal("99.40")));
        log.info("[判定依据] 配置 60->300bp 后，FEE-CFG-RACE 快照仍为 60bp/0.60/99.40");
    }
}
