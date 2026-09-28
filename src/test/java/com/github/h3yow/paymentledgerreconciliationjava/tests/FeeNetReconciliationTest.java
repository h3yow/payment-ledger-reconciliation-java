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
import com.github.h3yow.paymentledgerreconciliationjava.service.FeePolicyService;
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
 * 净额与手续费对账口径：
 * FEE_MISMATCH 与 NET_MISMATCH 分别识别、依据列出双方三项实际值；
 * 老渠道请求（不带手续费/净额）行为与扩展前完全一致；
 * 超精度外部金额一律拒绝且原因可区分；费率快照保证历史批次结论不受后续调费率影响。
 */
class FeeNetReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(FeeNetReconciliationTest.class);

    private InMemoryPaymentRepository paymentRepository;
    private PaymentService paymentService;
    private ReconciliationService reconciliationService;
    private InMemoryReconciliationBatchRepository batchRepository;
    private FeePolicyService feePolicyService;

    @BeforeEach
    void setUp() {
        paymentRepository = new InMemoryPaymentRepository();
        batchRepository = new InMemoryReconciliationBatchRepository();
        LedgerService ledgerService = new LedgerService(
                new InMemoryAccountRepository(), new InMemoryLedgerEntryRepository());
        feePolicyService = new FeePolicyService();
        paymentService = new PaymentService(paymentRepository, ledgerService, feePolicyService);
        reconciliationService = new ReconciliationService(paymentRepository, batchRepository);
    }

    private void payFee(String businessId, String amount, String rateBp) {
        paymentService.submit(new PaymentCommand(businessId, LedgerService.ACCT_PAYER,
                LedgerService.ACCT_PAYEE, amount, rateBp, LedgerService.ACCT_FEE));
    }

    private ChannelSettlementRecord chFee(String businessId, String amount, String fee, String net) {
        return new ChannelSettlementRecord(businessId, new BigDecimal(amount), ChannelStatus.SUCCESS,
                Instant.now(), new BigDecimal(fee), new BigDecimal(net));
    }

    private ChannelSettlementRecord ch(String businessId, String amount) {
        return new ChannelSettlementRecord(businessId, new BigDecimal(amount), ChannelStatus.SUCCESS,
                Instant.now());
    }

    private Map<String, ReconciliationResultItem> run(String batchId,
                                                      List<ChannelSettlementRecord> records,
                                                      List<String> scope) {
        ReconciliationBatch batch = reconciliationService.reconcile(
                new ReconciliationCommand(batchId, records, scope));
        return batch.items().stream()
                .collect(Collectors.toMap(ReconciliationResultItem::businessId, Function.identity()));
    }

    @Test
    @DisplayName("三项全一致 => MATCHED；手续费不符单独识别为 FEE_MISMATCH；净额不符单独识别为 NET_MISMATCH")
    void feeNetAndAmountDifferencesClassifiedSeparately() {
        // 100.00 × 60bp：本地手续费 0.60、净额 99.40
        payFee("R-MATCH", "100.00", "60");
        payFee("R-FEE", "100.00", "60");
        payFee("R-NET", "100.00", "60");

        Map<String, ReconciliationResultItem> items = run("FEE-BATCH-1", List.of(
                chFee("R-MATCH", "100.00", "0.60", "99.40"),
                // 手续费 0.90（总额/净额与本地一致）=> 仅手续费对不上
                chFee("R-FEE", "100.00", "0.90", "99.10"),
                // 手续费与本地一致 0.60，总额抬高到 100.50，净额 99.90 => 手续费对、净额错
                chFee("R-NET", "100.50", "0.60", "99.90")),
                List.of("R-MATCH", "R-FEE", "R-NET"));

        assertEquals(DifferenceType.MATCHED, items.get("R-MATCH").differenceType());
        assertEquals(DifferenceType.FEE_MISMATCH, items.get("R-FEE").differenceType());
        assertEquals(DifferenceType.NET_MISMATCH, items.get("R-NET").differenceType());
        for (ReconciliationResultItem item : items.values()) {
            log.info("[结论] {} => {} | detail={} | basis={}",
                    item.businessId(), item.differenceType(), item.detail(), item.basis());
            assertTrue(item.basis().contains("手续费 本地="), "依据必须列出双方手续费实际值");
            assertTrue(item.basis().contains("净额 本地="), "依据必须列出双方净额实际值");
            assertTrue(item.basis().contains("总额 本地="), "依据必须列出双方总额实际值");
        }
        // FEE_MISMATCH 的依据里必须分别写清双方手续费值
        assertTrue(items.get("R-FEE").basis().contains("本地=0.60"));
        assertTrue(items.get("R-FEE").basis().contains("渠道=0.90"));
        assertTrue(items.get("R-NET").basis().contains("净额 本地=99.40"));
        assertTrue(items.get("R-NET").basis().contains("渠道=99.90"));
    }

    @Test
    @DisplayName("总额不符但手续费、净额均一致不可能同时发生 => 费用对不上时绝不笼统归为 AMOUNT_MISMATCH")
    void feeDisagreementNeverCollapsedIntoAmountMismatch() {
        payFee("R-X", "100.00", "30"); // 本地 fee=0.30 net=99.70
        // 渠道总额不同（100.30），但构造为手续费一致、净额一致（100.30=0.30+100.00，净额 100.00≠99.70）
        // => 命中 NET_MISMATCH 而不是 AMOUNT_MISMATCH
        Map<String, ReconciliationResultItem> items = run("FEE-BATCH-2",
                List.of(chFee("R-X", "100.30", "0.30", "100.00")), List.of("R-X"));
        assertEquals(DifferenceType.NET_MISMATCH, items.get("R-X").differenceType());
        log.info("[判定依据] 总额不同但手续费一致 => 优先识别为净额不符: {}", items.get("R-X").basis());
    }

    @Test
    @DisplayName("老渠道请求不带手续费/净额：行为与扩展前完全一致，只比总额，不出 FEE/NET 结论")
    void legacyChannelRequestsBehaveExactlyAsBefore() {
        payFee("L-1", "10.00", "60"); // 本地虽有手续费快照
        // 老口径渠道单：无费用明细。总额一致 => MATCHED（即使本地含 0.06 手续费也不做费用比对）
        Map<String, ReconciliationResultItem> items = run("LEGACY-BATCH",
                List.of(ch("L-1", "10.00")), List.of("L-1"));
        assertEquals(DifferenceType.MATCHED, items.get("L-1").differenceType());
        assertTrue(items.get("L-1").basis().contains("按老口径仅比较总额"));

        // 总额不符仍是 AMOUNT_MISMATCH（老五类），不会被新的 FEE/NET 分类取代
        Map<String, ReconciliationResultItem> items2 = run("LEGACY-BATCH-2",
                List.of(ch("L-1", "9.00")), List.of("L-1"));
        assertEquals(DifferenceType.AMOUNT_MISMATCH, items2.get("L-1").differenceType());
        log.info("[判定依据] 老请求总额不符仍为 AMOUNT_MISMATCH，历史口径不变");
    }

    @Test
    @DisplayName("超精度外部金额（总额/手续费/净额 3 位小数）一律 INVALID_PARAMETER，原因区分且绝不隐式舍入")
    void excessivePrecisionRejectedWithDistinguishableReason() {
        InvalidParameterException e1 = assertThrows(InvalidParameterException.class, () ->
                reconciliationService.reconcile(new ReconciliationCommand("P-BAD-1",
                        List.of(chFee("A", "10.005", "0.00", "10.00")), null)));
        assertTrue(e1.getMessage().contains("渠道结算金额精度超过"));

        InvalidParameterException e2 = assertThrows(InvalidParameterException.class, () ->
                reconciliationService.reconcile(new ReconciliationCommand("P-BAD-2",
                        List.of(chFee("A", "10.00", "0.006", "9.99")), null)));
        assertTrue(e2.getMessage().contains("渠道手续费精度超过"));

        InvalidParameterException e3 = assertThrows(InvalidParameterException.class, () ->
                reconciliationService.reconcile(new ReconciliationCommand("P-BAD-3",
                        List.of(chFee("A", "10.00", "0.01", "9.994")), null)));
        assertTrue(e3.getMessage().contains("渠道净额精度超过"));
        log.info("[判定依据] 超精度拒绝原因分别为: {} | {} | {}",
                e1.getMessage(), e2.getMessage(), e3.getMessage());

        // 只带一个费用字段 => 参数非法；总额≠手续费+净额 => 参数非法
        var onlyFee = new ChannelSettlementRecord("A", new BigDecimal("10.00"), ChannelStatus.SUCCESS,
                Instant.now(), new BigDecimal("0.10"), null);
        InvalidParameterException e4 = assertThrows(InvalidParameterException.class, () ->
                reconciliationService.reconcile(
                        new ReconciliationCommand("P-BAD-4", List.of(onlyFee), null)));
        assertTrue(e4.getMessage().contains("必须成对提供"));

        InvalidParameterException e5 = assertThrows(InvalidParameterException.class, () ->
                reconciliationService.reconcile(new ReconciliationCommand("P-BAD-5",
                        List.of(chFee("A", "10.00", "0.10", "9.00")), null)));
        assertTrue(e5.getMessage().contains("总额=手续费+净额"));
    }

    @Test
    @DisplayName("表示法差异(1.0 vs 1.00)判一致；真实值差异绝不因精度口径不同被归成一致")
    void representationDifferenceMatchedButRealDifferenceNot() {
        payFee("D-1", "10.00", "30"); // fee 0.03 net 9.97
        // 渠道用等价表示法：10 / 0.03 / 9.97（scale 不同但数值相同）=> MATCHED
        Map<String, ReconciliationResultItem> items = run("REP-BATCH",
                List.of(new ChannelSettlementRecord("D-1", new BigDecimal("10.0"),
                        ChannelStatus.SUCCESS, Instant.now(),
                        new BigDecimal("0.03"), new BigDecimal("9.970"))),
                List.of("D-1"));
        assertEquals(DifferenceType.MATCHED, items.get("D-1").differenceType(),
                "10.0 与 10.00、9.970 与 9.97 为同一数值，应判一致");

        // 真实差 1 分：渠道 fee 0.02（哪怕它是“另一种舍入方式”得到的）=> FEE_MISMATCH，不允许判一致
        Map<String, ReconciliationResultItem> items2 = run("REP-BATCH-2",
                List.of(chFee("D-1", "10.00", "0.02", "9.98")), List.of("D-1"));
        assertEquals(DifferenceType.FEE_MISMATCH, items2.get("D-1").differenceType());
        log.info("[判定依据] 真实 1 分差异被明确识别为手续费不符，不因精度处理不同而放过");
    }

    @Test
    @DisplayName("费率快照保证：后续调整费率后重新对账，历史批次结论原样复现，新批次反映当前快照")
    void historicalBatchConclusionUnaffectedByLaterRateChange() {
        feePolicyService.updateDefault("30", LedgerService.ACCT_FEE);
        paymentService.submit(new PaymentCommand("H-1", LedgerService.ACCT_PAYER,
                LedgerService.ACCT_PAYEE, "100.00")); // fee 0.30 net 99.70
        var command = new ReconciliationCommand("HIST-1",
                List.of(chFee("H-1", "100.00", "0.30", "99.70")), List.of("H-1"));
        ReconciliationBatch first = reconciliationService.reconcile(command);

        // 即使后续默认费率变化，历史批次按 batchId 取回的结论不变
        feePolicyService.updateDefault("60", LedgerService.ACCT_FEE);
        ReconciliationBatch second = reconciliationService.reconcile(command);
        assertSame(first, second, "同 batchId 同输入必须返回同一历史结论");
        assertEquals(DifferenceType.MATCHED,
                second.items().get(0).differenceType());
        // 历史支付快照本身也未被影响，所以用新 batchId 重算，结论仍然基于受理当时的 0.30
        Map<String, ReconciliationResultItem> reRun = run("HIST-2",
                List.of(chFee("H-1", "100.00", "0.60", "99.40")), List.of("H-1"));
        assertEquals(DifferenceType.FEE_MISMATCH, reRun.get("H-1").differenceType(),
                "已受理支付锁定 30bp：拿 60bp 口径的渠道单重对，必然手续费不符，而不是悄悄按新费率一致");
        log.info("[判定依据] 调费率后历史批次仍 MATCHED 且同对象；按 60bp 渠道单重对历史支付 => FEE_MISMATCH");
    }
}
