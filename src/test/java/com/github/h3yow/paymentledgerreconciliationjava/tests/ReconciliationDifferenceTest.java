package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelSettlementRecord;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelStatus;
import com.github.h3yow.paymentledgerreconciliationjava.domain.DifferenceType;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationBatch;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationResultItem;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryAccountRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryLedgerEntryRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryPaymentRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryReconciliationBatchRepository;
import com.github.h3yow.paymentledgerreconciliationjava.service.ConflictException;
import com.github.h3yow.paymentledgerreconciliationjava.service.InvalidParameterException;
import com.github.h3yow.paymentledgerreconciliationjava.service.LedgerService;
import com.github.h3yow.paymentledgerreconciliationjava.service.PaymentCommand;
import com.github.h3yow.paymentledgerreconciliationjava.service.PaymentService;
import com.github.h3yow.paymentledgerreconciliationjava.service.ReconciliationCommand;
import com.github.h3yow.paymentledgerreconciliationjava.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账差异识别：金额不符、状态不一致、本地缺失、渠道缺失、一致五类互斥结论；
 * 批次结论可复现、不重复处理；异常输入被可区分拒绝。
 */
class ReconciliationDifferenceTest {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationDifferenceTest.class);

    private PaymentService paymentService;
    private ReconciliationService reconciliationService;
    private InMemoryReconciliationBatchRepository batchRepository;

    @BeforeEach
    void setUp() {
        InMemoryPaymentRepository paymentRepository = new InMemoryPaymentRepository();
        batchRepository = new InMemoryReconciliationBatchRepository();
        LedgerService ledgerService = new LedgerService(
                new InMemoryAccountRepository(), new InMemoryLedgerEntryRepository());
        paymentService = new PaymentService(paymentRepository, ledgerService);
        reconciliationService = new ReconciliationService(paymentRepository, batchRepository);
    }

    private void pay(String businessId, String amount) {
        paymentService.submit(new PaymentCommand(
                businessId, LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, amount));
    }

    private ChannelSettlementRecord ch(String businessId, String amount, ChannelStatus status) {
        return new ChannelSettlementRecord(businessId, new BigDecimal(amount), status, Instant.now());
    }

    @Test
    @DisplayName("五类差异全部识别且互斥：MATCHED / AMOUNT_MISMATCH / STATUS_MISMATCH / LOCAL_MISSING / CHANNEL_MISSING")
    void allFiveDifferenceTypesClassified() {
        // 本地流水
        pay("B-MATCH", "10.00");     // 渠道一致
        pay("B-AMT", "20.00");       // 渠道金额不符
        pay("B-STAT", "30.00");      // 渠道状态不一致：本地 POSTED，渠道 FAILED
        pay("B-LOCAL-ONLY", "40.00");// 渠道缺单

        List<ChannelSettlementRecord> channel = List.of(
                ch("B-MATCH", "10.00", ChannelStatus.SUCCESS),
                ch("B-AMT", "19.99", ChannelStatus.SUCCESS),
                ch("B-STAT", "30.00", ChannelStatus.FAILED),
                ch("B-CH-ONLY", "50.00", ChannelStatus.SUCCESS) // 本地缺失
        );

        ReconciliationCommand command = new ReconciliationCommand(
                "BATCH-001", channel,
                List.of("B-MATCH", "B-AMT", "B-STAT", "B-LOCAL-ONLY"));
        log.info("[输入] 渠道记录数={}, 本地范围={}", channel.size(), command.scopeBusinessIds());

        ReconciliationBatch batch = reconciliationService.reconcile(command);
        Map<String, ReconciliationResultItem> byId = batch.items().stream()
                .collect(Collectors.toMap(ReconciliationResultItem::businessId, Function.identity()));

        assertType(byId, "B-MATCH", DifferenceType.MATCHED);
        assertType(byId, "B-AMT", DifferenceType.AMOUNT_MISMATCH);
        assertType(byId, "B-STAT", DifferenceType.STATUS_MISMATCH);
        assertType(byId, "B-CH-ONLY", DifferenceType.LOCAL_MISSING);
        assertType(byId, "B-LOCAL-ONLY", DifferenceType.CHANNEL_MISSING);

        assertEquals(5, batch.totalCount());
        assertEquals(1, batch.matchedCount());
        assertEquals(4, batch.differenceCount(), "4 条差异均须显式列出，不得静默忽略或合并");

        for (ReconciliationResultItem item : batch.items()) {
            log.info("[结论] {} => {} | {} | 判定依据: {}",
                    item.businessId(), item.differenceType(), item.detail(), item.basis());
            assertTrue(item.basis() != null && !item.basis().isBlank(),
                    "每条结论必须携带判定依据，保证可解释");
        }
    }

    @Test
    @DisplayName("历史结论可复现：同 batchId 同输入返回同一批次，不产生第二条；不同输入冲突拒绝")
    void batchReproducibleAndConflictDistinguishable() {
        pay("B1", "1.00");
        var sameInput = new ReconciliationCommand("BATCH-X",
                List.of(ch("B1", "1.00", ChannelStatus.SUCCESS)), List.of("B1"));

        ReconciliationBatch first = reconciliationService.reconcile(sameInput);
        ReconciliationBatch second = reconciliationService.reconcile(sameInput);
        assertSame(first, second, "同 batchId 同输入必须返回同一历史结论（可复现、不重复处理）");
        assertEquals(1, batchRepository.findAll().size(), "不得产生重复批次");
        log.info("[判定依据] BATCH-X 重复执行复用历史结论 ranAt={}", first.ranAt());

        var tampered = new ReconciliationCommand("BATCH-X",
                List.of(ch("B1", "2.00", ChannelStatus.SUCCESS)), List.of("B1"));
        ConflictException conflict = assertThrows(ConflictException.class,
                () -> reconciliationService.reconcile(tampered));
        log.info("[判定依据] 同 batchId 不同输入 => STATUS_CONFLICT: {}", conflict.getMessage());
    }

    @Test
    @DisplayName("渠道数据重复 businessId / 金额非法 / batchId 缺失 均按参数非法拒绝")
    void invalidReconciliationInputRejected() {
        var duplicated = new ReconciliationCommand("BATCH-Y",
                List.of(ch("B1", "1.00", ChannelStatus.SUCCESS),
                        ch("B1", "1.00", ChannelStatus.SUCCESS)), null);
        assertThrows(InvalidParameterException.class,
                () -> reconciliationService.reconcile(duplicated));

        var badAmount = new ReconciliationCommand("BATCH-Z",
                List.of(ch("B1", "0", ChannelStatus.SUCCESS)), null);
        assertThrows(InvalidParameterException.class,
                () -> reconciliationService.reconcile(badAmount));

        var noBatch = new ReconciliationCommand("  ", List.of(), null);
        assertThrows(InvalidParameterException.class,
                () -> reconciliationService.reconcile(noBatch));
        log.info("[判定依据] 渠道重复单/非正金额/空 batchId 均归类 INVALID_PARAMETER");
    }

    @Test
    @DisplayName("空范围口径：渠道空单 + scope 覆盖全部本地流水 => 全部 CHANNEL_MISSING，差异不遗漏")
    void emptyChannelAllLocalAreChannelMissing() {
        pay("ONLY-1", "7.77");
        pay("ONLY-2", "8.88");
        ReconciliationBatch batch = reconciliationService.reconcile(
                new ReconciliationCommand("BATCH-EMPTY", List.of(),
                        List.of("ONLY-1", "ONLY-2")));
        assertEquals(2, batch.differenceCount());
        assertTrue(batch.items().stream()
                .allMatch(i -> i.differenceType() == DifferenceType.CHANNEL_MISSING));
        log.info("[判定依据] 渠道空数据时本地 2 笔均为 CHANNEL_MISSING，未被静默忽略");
    }

    private void assertType(Map<String, ReconciliationResultItem> byId,
                            String businessId, DifferenceType expected) {
        ReconciliationResultItem item = byId.get(businessId);
        assertEquals(expected, item.differenceType(),
                () -> businessId + " 应判定为 " + expected + "，实际 " + item.differenceType()
                        + "（依据: " + item.basis() + "）");
    }
}
