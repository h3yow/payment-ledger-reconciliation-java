package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.github.h3yow.paymentledgerreconciliationjava.config.DataInitializer;
import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerEntry;
import com.github.h3yow.paymentledgerreconciliationjava.domain.Money;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentStatus;
import com.github.h3yow.paymentledgerreconciliationjava.exception.DuplicateRequestException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.InvalidParameterException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.StateConflictException;
import com.github.h3yow.paymentledgerreconciliationjava.service.LedgerService;
import com.github.h3yow.paymentledgerreconciliationjava.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受理幂等测试：顺序重复、并发重复、金额冲突、参数非法、状态冲突，失败原因必须可区分。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentIdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentIdempotencyTest.class);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private LedgerService ledgerService;

    @Test
    void concurrentDuplicateSubmitProducesExactlyOneEffectiveResult() throws Exception {
        String bizId = "CONC-001";
        String amount = "88.88";
        int threads = 20;
        log.info("[关键输入] bizId={} amount={} 并发线程数={}", bizId, amount, threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        ConcurrentLinkedQueue<String> rejectCodes = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    paymentService.submit(bizId, amount);
                    success.incrementAndGet();
                } catch (DuplicateRequestException ex) {
                    rejectCodes.add(ex.getCode());
                } catch (Exception ex) {
                    rejectCodes.add("UNEXPECTED:" + ex.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[判定依据] 成功笔数={} 拒绝笔数={} 拒绝码分布={}",
                success.get(), rejectCodes.size(),
                rejectCodes.stream().collect(java.util.stream.Collectors.groupingBy(
                        c -> c, java.util.LinkedHashMap::new, java.util.stream.Collectors.counting())));

        assertEquals(1, success.get(), "同一 bizId 并发提交只能有 1 笔有效结果");
        assertEquals(threads - 1, rejectCodes.size());
        assertTrue(rejectCodes.stream().allMatch("DUPLICATE_REQUEST"::equals),
                "落败请求必须全部以 DUPLICATE_REQUEST 被拒绝");

        // 账本侧同样只能有一笔受理过账（2 条分录），余额恰好等于一笔金额
        List<LedgerEntry> entries = ledgerService.entriesOfBiz(bizId);
        log.info("[判定依据] bizId={} 分录数={} 应收借方={} 待清算贷方={}",
                bizId, entries.size(),
                ledgerService.account(DataInitializer.ACC_RECEIVABLE).getDebitBalance(),
                ledgerService.account(DataInitializer.ACC_CUSTOMER_PENDING).getCreditBalance());
        assertEquals(2, entries.size());
        assertEquals(Money.of("88.88"), ledgerService.account(DataInitializer.ACC_RECEIVABLE).getDebitBalance());
        assertEquals(Money.of("88.88"), ledgerService.account(DataInitializer.ACC_CUSTOMER_PENDING).getCreditBalance());
        assertEquals(PaymentStatus.PENDING, paymentService.get(bizId).getStatus());
    }

    @Test
    void sequentialDuplicateAndDifferentAmountAreDuplicatesWithDistinctReasons() {
        paymentService.submit("SEQ-001", "10.00");
        DuplicateRequestException same = catchDuplicate(() -> paymentService.submit("SEQ-001", "10.00"));
        DuplicateRequestException diff = catchDuplicate(() -> paymentService.submit("SEQ-001", "20.00"));
        log.info("[重复-金额一致] {}", same.getMessage());
        log.info("[重复-金额不同] {}", diff.getMessage());
        assertTrue(same.getMessage().contains("金额一致"));
        assertTrue(diff.getMessage().contains("金额不一致=20.00"));
        assertEquals(Money.of("10.00"), paymentService.get("SEQ-001").getAmount(), "重复提交不得覆盖既有金额");
    }

    @Test
    void illegalParametersAreRejectedBeforeAnyBooking() {
        InvalidParameterException blank = catchInvalid(() -> paymentService.submit(" ", "1.00"));
        InvalidParameterException badAmount = catchInvalid(() -> paymentService.submit("P-1", "abc"));
        InvalidParameterException zero = catchInvalid(() -> paymentService.submit("P-1", "0"));
        InvalidParameterException tooManyDecimals = catchInvalid(() -> paymentService.submit("P-1", "1.009"));
        log.info("[参数非法] blank='{}' badAmount='{}' zero='{}' precision='{}'",
                blank.getMessage(), badAmount.getMessage(), zero.getMessage(), tooManyDecimals.getMessage());
        assertTrue(ledgerService.allEntries().isEmpty(), "参数非法不得产生任何分录");
    }

    @Test
    void illegalStateTransitionsAreRejectedAndOrderStaysIntact() {
        paymentService.submit("P-S", "5.00");
        paymentService.markSuccess("P-S");
        StateConflictException againSuccess = catchState(() -> paymentService.markSuccess("P-S"));
        StateConflictException failedAfterSuccess = catchState(() -> paymentService.markFailed("P-S", "x"));
        log.info("[状态冲突] 成功后再成功='{}' 成功后置失败='{}'",
                againSuccess.getMessage(), failedAfterSuccess.getMessage());
        assertEquals(PaymentStatus.SUCCESS, paymentService.get("P-S").getStatus());
        // 成功单的账户去向：备付金借、客户已清算贷 各 5.00
        assertEquals(Money.of("5.00"), ledgerService.account(DataInitializer.ACC_SETTLED_FUNDS).getDebitBalance());
        assertEquals(Money.of("5.00"), ledgerService.account(DataInitializer.ACC_CUSTOMER_SETTLED).getCreditBalance());
    }

    @Test
    void failFlowReversesAcceptanceAndConservesBalance() {
        paymentService.submit("P-F", "7.00");
        assertNotNull(paymentService.markFailed("P-F", "CHANNEL_TIMEOUT"));
        log.info("[失败冲销后] 应收: 借={}/贷={}/净额={} 待清算: 借={}/贷={}/净额={} 状态={}",
                ledgerService.account(DataInitializer.ACC_RECEIVABLE).getDebitBalance(),
                ledgerService.account(DataInitializer.ACC_RECEIVABLE).getCreditBalance(),
                ledgerService.account(DataInitializer.ACC_RECEIVABLE).signedBalance(),
                ledgerService.account(DataInitializer.ACC_CUSTOMER_PENDING).getDebitBalance(),
                ledgerService.account(DataInitializer.ACC_CUSTOMER_PENDING).getCreditBalance(),
                ledgerService.account(DataInitializer.ACC_CUSTOMER_PENDING).signedBalance(),
                paymentService.get("P-F").getStatus());
        // 红字冲销为反向新增分录：借贷发生额栏各留 7.00，但净有符号余额必须为 0
        assertEquals(Money.of("7.00"), ledgerService.account(DataInitializer.ACC_RECEIVABLE).getDebitBalance());
        assertEquals(Money.of("7.00"), ledgerService.account(DataInitializer.ACC_RECEIVABLE).getCreditBalance());
        assertEquals(Money.zero(), ledgerService.account(DataInitializer.ACC_RECEIVABLE).signedBalance());
        assertEquals(Money.of("7.00"), ledgerService.account(DataInitializer.ACC_CUSTOMER_PENDING).getDebitBalance());
        assertEquals(Money.of("7.00"), ledgerService.account(DataInitializer.ACC_CUSTOMER_PENDING).getCreditBalance());
        assertEquals(Money.zero(), ledgerService.account(DataInitializer.ACC_CUSTOMER_PENDING).signedBalance());
        assertEquals(PaymentStatus.FAILED, paymentService.get("P-F").getStatus());
        assertEquals("CHANNEL_TIMEOUT", paymentService.get("P-F").getFailReason());
        // 冲销分录本身也是一笔平衡过账：该 bizId 共 4 条分录（受理2 + 冲销2）
        assertEquals(4, ledgerService.entriesOfBiz("P-F").size());
    }

    private static DuplicateRequestException catchDuplicate(Runnable r) {
        try {
            r.run();
            throw new AssertionError("期望抛出 DuplicateRequestException");
        } catch (DuplicateRequestException ex) {
            return ex;
        }
    }

    private static InvalidParameterException catchInvalid(Runnable r) {
        try {
            r.run();
            throw new AssertionError("期望抛出 InvalidParameterException");
        } catch (InvalidParameterException ex) {
            return ex;
        }
    }

    private static StateConflictException catchState(Runnable r) {
        try {
            r.run();
            throw new AssertionError("期望抛出 StateConflictException");
        } catch (StateConflictException ex) {
            return ex;
        }
    }
}
