package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.github.h3yow.paymentledgerreconciliationjava.config.DataInitializer;
import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerAccount;
import com.github.h3yow.paymentledgerreconciliationjava.domain.Money;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PostingLine;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PostingRequest;
import com.github.h3yow.paymentledgerreconciliationjava.exception.InvalidParameterException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.PostingException;
import com.github.h3yow.paymentledgerreconciliationjava.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复式过账测试：借贷平衡校验、中途失败无半成品、重复过账拒绝、并发守恒。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LedgerPostingTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerPostingTest.class);

    @Autowired
    private LedgerService ledgerService;

    @Test
    void midPostingFailureLeavesNoHalfDoneEntriesOrBalances() {
        String postingId = "BOOM-001";
        PostingRequest request = balancedPosting(postingId, "BIZ-BOOM", "30.00");
        log.info("[关键输入] 故障过账 postingId={} 行数=2（余额更新后、分录写入前抛错）", postingId);

        // 快照故障前余额
        Money receivableBefore = ledgerService.account(DataInitializer.ACC_RECEIVABLE).signedBalance();
        Money pendingBefore = ledgerService.account(DataInitializer.ACC_CUSTOMER_PENDING).signedBalance();

        ledgerService.setFailureHook(req -> {
            throw new PostingException("注入故障：模拟过账中途异常");
        });
        PostingException ex = assertThrows(PostingException.class, () -> ledgerService.post(request));
        ledgerService.setFailureHook(null);

        log.info("[判定依据] 异常 code={} message={}", ex.getCode(), ex.getMessage());
        assertTrue(ledgerService.entriesOfPosting(postingId).isEmpty(), "失败过账不得留下任何分录");
        assertEquals(receivableBefore,
                ledgerService.account(DataInitializer.ACC_RECEIVABLE).signedBalance(), "余额必须回滚");
        assertEquals(pendingBefore,
                ledgerService.account(DataInitializer.ACC_CUSTOMER_PENDING).signedBalance(), "余额必须回滚");
        log.info("[回滚后] 应收净额={} 待清算净额={} 该过账分录数={}",
                ledgerService.account(DataInitializer.ACC_RECEIVABLE).signedBalance(),
                ledgerService.account(DataInitializer.ACC_CUSTOMER_PENDING).signedBalance(),
                ledgerService.entriesOfPosting(postingId).size());

        // 失败之后允许用相同业务内容重新过账（但必须换新 postingId，因为旧编号从未生效）
        PostingRequest retry = balancedPosting("RETRY-001", "BIZ-BOOM", "30.00");
        ledgerService.post(retry);
        assertEquals(2, ledgerService.entriesOfPosting("RETRY-001").size());
        assertEquals(Money.of("30.00"),
                ledgerService.account(DataInitializer.ACC_RECEIVABLE).signedBalance().minus(receivableBefore));
    }

    @Test
    void unbalancedAndMalformedPostingsAreRejectedWithReasons() {
        PostingRequest unbalanced = new PostingRequest("UB-1", "BIZ-UB", List.of(
                new PostingLine(DataInitializer.ACC_RECEIVABLE, true, Money.of("10.00"), "借多"),
                new PostingLine(DataInitializer.ACC_CUSTOMER_PENDING, false, Money.of("9.00"), "贷少")));
        InvalidParameterException e1 = assertThrows(InvalidParameterException.class,
                () -> ledgerService.post(unbalanced));
        log.info("[拒绝-不平衡] {}", e1.getMessage());
        assertTrue(e1.getMessage().contains("借贷不平衡"));

        PostingRequest unknownAccount = new PostingRequest("UA-1", "BIZ-UA", List.of(
                new PostingLine("9999", true, Money.of("1.00"), "x"),
                new PostingLine(DataInitializer.ACC_CUSTOMER_PENDING, false, Money.of("1.00"), "y")));
        InvalidParameterException e2 = assertThrows(InvalidParameterException.class,
                () -> ledgerService.post(unknownAccount));
        log.info("[拒绝-账户不存在] {}", e2.getMessage());
        assertTrue(e2.getMessage().contains("账户不存在"));

        PostingRequest zeroAmount = new PostingRequest("ZA-1", "BIZ-ZA", List.of(
                new PostingLine(DataInitializer.ACC_RECEIVABLE, true, Money.of("0.00"), "零"),
                new PostingLine(DataInitializer.ACC_CUSTOMER_PENDING, false, Money.of("0.00"), "零")));
        InvalidParameterException e3 = assertThrows(InvalidParameterException.class,
                () -> ledgerService.post(zeroAmount));
        log.info("[拒绝-零金额] {}", e3.getMessage());
        assertTrue(e3.getMessage().contains("金额必须为正数"));

        assertTrue(ledgerService.allEntries().isEmpty(), "非法过账不得留下分录");
    }

    @Test
    void duplicatePostingIdIsRejectedAndNotBookedTwice() {
        ledgerService.post(balancedPosting("DUP-POST", "BIZ-DUP", "12.00"));
        PostingException ex = assertThrows(PostingException.class,
                () -> ledgerService.post(balancedPosting("DUP-POST", "BIZ-DUP", "12.00")));
        log.info("[拒绝-过账编号重复] code={} message={}", ex.getCode(), ex.getMessage());
        assertTrue(ex.getMessage().contains("过账编号重复"));
        assertEquals(2, ledgerService.entriesOfPosting("DUP-POST").size());
    }

    @Test
    void concurrentPostingsConserveTheAccountingEquation() throws Exception {
        int n = 50;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        log.info("[关键输入] 并发过账笔数={} 每笔金额=1.00", n);
        for (int i = 0; i < n; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ledgerService.post(balancedPosting("CONC-P-" + idx, "BIZ-C-" + idx, "1.00"));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    log.error("并发过账异常 idx={}", idx, e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(n, ok.get());

        List<LedgerAccount> accounts = ledgerService.accounts();
        Money totalDebitSigned = Money.zero();
        Money totalCreditSigned = Money.zero();
        for (LedgerAccount a : accounts) {
            if (a.getAccountCode().startsWith("11")) {
                totalDebitSigned = totalDebitSigned.plus(a.signedBalance());
            } else {
                // 负债类 signedBalance 为负值（贷方余额），取反得到"资金来源"侧合计
                totalCreditSigned = totalCreditSigned.plus(a.signedBalance().negate());
            }
        }
        // 每笔过账借贷各自平衡 => 全部分录借方合计 == 贷方合计；资产净额 == 负债净额
        Money entryDebit = ledgerService.allEntries().stream()
                .filter(e -> e.isDebit()).map(e -> e.getAmount()).reduce(Money.zero(), Money::plus);
        Money entryCredit = ledgerService.allEntries().stream()
                .filter(e -> !e.isDebit()).map(e -> e.getAmount()).reduce(Money.zero(), Money::plus);
        log.info("[守恒判定] 成功过账={} 资产侧净额={} 负债侧净额={} 分录借方合计={} 分录贷方合计={}",
                ok.get(), totalDebitSigned, totalCreditSigned, entryDebit, entryCredit);
        assertEquals(entryDebit, entryCredit, "全部分录借贷合计必须相等");
        assertEquals(totalDebitSigned, totalCreditSigned, "资产=负债 恒等式必须成立");
        assertEquals(Money.of(n + ".00"), totalDebitSigned);
    }

    private static PostingRequest balancedPosting(String postingId, String bizId, String amount) {
        return new PostingRequest(postingId, bizId, List.of(
                new PostingLine(DataInitializer.ACC_RECEIVABLE, true, Money.of(amount), "借应收"),
                new PostingLine(DataInitializer.ACC_CUSTOMER_PENDING, false, Money.of(amount), "贷待清算")));
    }
}
