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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 净额/手续费分项对账：
 * 一致、净额不符、手续费不符分别识别；老请求（不带 fee/net）口径与扩展前完全一致；
 * 超精度外部金额（总额/净额/手续费）一律按参数非法拒绝且原因可区分；费率调整不改历史结论。
 */
class FeeReconciliationDifferenceTest {

    private static final Logger log = LoggerFactory.getLogger(FeeReconciliationDifferenceTest.class);

    private PaymentService paymentService;
    private ReconciliationService reconciliationService;
    private InMemoryPaymentRepository paymentRepository;

    @BeforeEach
    void setUp() {
        paymentRepository = new InMemoryPaymentRepository();
        var batchRepository = new InMemoryReconciliationBatchRepository();
        LedgerService ledgerService = new LedgerService(
                new InMemoryAccountRepository(), new InMemoryLedgerEntryRepository());
        paymentService = new PaymentService(paymentRepository, ledgerService);
        reconciliationService = new ReconciliationService(paymentRepository, batchRepository);
    }

    /** 受理一笔带手续费支付：100.00，60bp => 手续费 0.60，净额 99.40。 */
    private void payFee(String businessId, String gross, int rateBp) {
        paymentService.submit(new PaymentCommand(
                businessId, "1001", "2001", gross, rateBp, "3001"));
    }

    private ChannelSettlementRecord ch(String id, String amount, ChannelStatus st,
                                       String fee, String net) {
        return new ChannelSettlementRecord(id, new BigDecimal(amount), st, Instant.now(),
                fee == null ? null : new BigDecimal(fee),
                net == null ? null : new BigDecimal(net));
    }

    @Test
    @DisplayName("分项一致：总额/净额/手续费全部一致 => MATCHED，依据列出三者实际值")
    void matchedWhenAllThreeAgree() {
        payFee("M1", "100.00", 60);
        var cmd = new ReconciliationCommand("RB-1",
                List.of(ch("M1", "100.00", ChannelStatus.SUCCESS, "0.60", "99.40")),
                List.of("M1"));
        ReconciliationBatch batch = reconciliationService.reconcile(cmd);
        ReconciliationResultItem item = batch.items().get(0);
        assertEquals(DifferenceType.MATCHED, item.differenceType());
        assertTrue(item.basis().contains("净额一致(99.40)"), item.basis());
        assertTrue(item.basis().contains("手续费一致(0.60)"), item.basis());
        log.info("[一致] {}", item.basis());
    }

    @Test
    @DisplayName("净额不符与手续费不符分开识别，互不混淆，依据分别写明双方实际值")
    void netMismatchAndFeeMismatchClassifiedSeparately() {
        // 本地：F-NET 100.00/0.60/99.40；F-FEE 同
        payFee("F-NET", "100.00", 60);
        payFee("F-FEE", "100.00", 60);

        var cmd = new ReconciliationCommand("RB-2", List.of(
                // 总额一致 100.00，但净额 99.00 / 手续费 1.00（净额先不符）
                ch("F-NET", "100.00", ChannelStatus.SUCCESS, "1.00", "99.00"),
                // 总额一致、净额一致 99.40，但手续费 0.70（0.70+99.40=100.10，仅手续费维度比）
                ch("F-FEE", "100.00", ChannelStatus.SUCCESS, "0.70", "99.40")),
                List.of("F-NET", "F-FEE"));
        ReconciliationBatch batch = reconciliationService.reconcile(cmd);
        Map<String, ReconciliationResultItem> byId = batch.items().stream()
                .collect(Collectors.toMap(ReconciliationResultItem::businessId, Function.identity()));

        ReconciliationResultItem netItem = byId.get("F-NET");
        assertEquals(DifferenceType.NET_MISMATCH, netItem.differenceType());
        assertTrue(netItem.basis().contains("净额 本地=99.40 渠道=99.00"), netItem.basis());
        assertTrue(netItem.basis().contains("手续费实际值 本地=0.60 渠道=1.00"), netItem.basis());

        ReconciliationResultItem feeItem = byId.get("F-FEE");
        assertEquals(DifferenceType.FEE_MISMATCH, feeItem.differenceType());
        assertTrue(feeItem.basis().contains("手续费 本地=0.60 渠道=0.70"), feeItem.basis());
        assertTrue(feeItem.basis().contains("净额一致(99.40)"), feeItem.basis());
        log.info("[净额不符] {}", netItem.basis());
        log.info("[手续费不符] {}", feeItem.basis());
    }

    @Test
    @DisplayName("本地无手续费、渠道却报有手续费：净额(100 vs 99)或手续费(0 vs 0.60)差异被如实识别")
    void localWithoutFeeVsChannelWithFee() {
        // 老的无手续费本地支付
        paymentService.submit(new PaymentCommand("OLD1", "1001", "2001", "100.00"));
        var cmd = new ReconciliationCommand("RB-3",
                List.of(ch("OLD1", "100.00", ChannelStatus.SUCCESS, "0.60", "99.40")),
                List.of("OLD1"));
        ReconciliationResultItem item = reconciliationService.reconcile(cmd).items().get(0);
        assertEquals(DifferenceType.NET_MISMATCH, item.differenceType(),
                "本地净额=100.00 vs 渠道99.40 应判净额不符（净额优先），不静默忽略");
        log.info("[本地无费/渠道有费] {}", item.basis());
    }

    @Test
    @DisplayName("老请求不带 fee/net：即使本地是手续费支付，也只按总额与状态核对，行为与扩展前一致")
    void legacyChannelRecordWithoutFeeNetUsesGrossOnly() {
        payFee("LEG1", "100.00", 60);
        var cmd = new ReconciliationCommand("RB-4",
                List.of(ch("LEG1", "100.00", ChannelStatus.SUCCESS, null, null)),
                List.of("LEG1"));
        ReconciliationResultItem item = reconciliationService.reconcile(cmd).items().get(0);
        assertEquals(DifferenceType.MATCHED, item.differenceType());
        assertTrue(item.basis().contains("按总额口径核对"), item.basis());
        log.info("[老请求兼容] {}", item.basis());
    }

    @Test
    @DisplayName("精度差异不被当成一致：渠道总额/净额/手续费超 2 位小数一律 INVALID_PARAMETER，原因带字段名")
    void overPrecisionExternalAmountsRejectedWithDistinctReason() {
        payFee("P1", "100.00", 60);

        InvalidParameterException e1 = assertThrows(InvalidParameterException.class,
                () -> reconciliationService.reconcile(new ReconciliationCommand("RB-5",
                        List.of(ch("P1", "100.005", ChannelStatus.SUCCESS, "0.60", "99.40")),
                        List.of("P1"))));
        assertTrue(e1.getMessage().contains("渠道金额") && e1.getMessage().contains("精度"),
                e1.getMessage());

        InvalidParameterException e2 = assertThrows(InvalidParameterException.class,
                () -> reconciliationService.reconcile(new ReconciliationCommand("RB-6",
                        List.of(ch("P1", "100.00", ChannelStatus.SUCCESS, "0.601", "99.40")),
                        List.of("P1"))));
        assertTrue(e2.getMessage().contains("渠道手续费") && e2.getMessage().contains("精度"),
                e2.getMessage());

        InvalidParameterException e3 = assertThrows(InvalidParameterException.class,
                () -> reconciliationService.reconcile(new ReconciliationCommand("RB-7",
                        List.of(ch("P1", "100.00", ChannelStatus.SUCCESS, "0.60", "99.399")),
                        List.of("P1"))));
        assertTrue(e3.getMessage().contains("渠道净额") && e3.getMessage().contains("精度"),
                e3.getMessage());

        // 只给净额不给手续费 => 参数非法
        assertThrows(InvalidParameterException.class,
                () -> reconciliationService.reconcile(new ReconciliationCommand("RB-8",
                        List.of(ch("P1", "100.00", ChannelStatus.SUCCESS, null, "99.40")),
                        List.of("P1"))));
        log.info("[超精度拒绝] {} | {} | {}", e1.getMessage(), e2.getMessage(), e3.getMessage());
    }

    @Test
    @DisplayName("费率快照不影响对账结论：受理后改费率，按受理当时快照的历史批次结论可复现不变")
    void rateConfigChangeDoesNotChangeHistoricalBatch() {
        payFee("H1", "100.00", 60);
        var cmd = new ReconciliationCommand("RB-9",
                List.of(ch("H1", "100.00", ChannelStatus.SUCCESS, "0.60", "99.40")),
                List.of("H1"));
        ReconciliationBatch first = reconciliationService.reconcile(cmd);
        assertEquals(DifferenceType.MATCHED, first.items().get(0).differenceType());

        // 渠道把净额改错，用同一 batchId 不同输入 => 冲突拒绝，历史结论不被覆盖
        var tampered = new ReconciliationCommand("RB-9",
                List.of(ch("H1", "100.00", ChannelStatus.SUCCESS, "0.60", "99.00")),
                List.of("H1"));
        assertThrows(com.github.h3yow.paymentledgerreconciliationjava.service.ConflictException.class,
                () -> reconciliationService.reconcile(tampered));

        // 同输入重放 => 同一历史结论
        ReconciliationBatch again = reconciliationService.reconcile(cmd);
        assertEquals(first, again);
        log.info("[历史可复现] RB-9 结论不被后续变更覆盖");
    }
}
