package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.github.h3yow.paymentledgerreconciliationjava.domain.AccountType;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelSettlementRecord;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelStatus;
import com.github.h3yow.paymentledgerreconciliationjava.domain.DifferenceType;
import com.github.h3yow.paymentledgerreconciliationjava.domain.FeeSettlement;
import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerEntry;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentInstruction;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentStatus;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationBatch;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationResultItem;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryAccountRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryLedgerEntryRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryPaymentRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryReconciliationBatchRepository;
import com.github.h3yow.paymentledgerreconciliationjava.service.ConflictException;
import com.github.h3yow.paymentledgerreconciliationjava.service.DuplicateRequestException;
import com.github.h3yow.paymentledgerreconciliationjava.service.FeeConfiguration;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：带费率（请求显式带费率，或本地默认费率生效）但手续费按 HALF_UP 处理到分后正好 0.00 的支付。
 *
 * <p>覆盖用例：
 * <ol>
 *   <li>极小正费率算成 0.00：费率/0.00 手续费/净额(=总额)仍随支付留痕且可单独查；账务两行、净额入账；</li>
 *   <li>显式 0bp 费率：快照保留 0 费率；</li>
 *   <li>本地默认费率为 0bp（enabled=true）：默认生效的 0 费率同样留痕；</li>
 *   <li>无任何费率要素的老行为：无快照（404 口径），与扩展前一致；</li>
 *   <li>幂等强度：完全相同（即便手续费都是 0.00）=> 重复；改费率/改手续费账户/去掉费率要素 => 冲突；</li>
 *   <li>并发同键只有一笔有效，手续费/净额/账务完全一致；</li>
 *   <li>受理后改费率配置，0.00 手续费支付的费率/手续费/净额/查询不变；</li>
 *   <li>对账按实际保留的 0.00 手续费/净额参与核对：MATCHED / FEE_MISMATCH 可识别，老批次口径不变；</li>
 *   <li>尾差规则：舍去产生负尾差（正手续费场景）与 0 手续费场景的尾差都可解释，恒等式严格成立。</li>
 * </ol>
 */
class ZeroFeeRateRetentionTest {

    private static final Logger log = LoggerFactory.getLogger(ZeroFeeRateRetentionTest.class);

    private InMemoryAccountRepository accountRepository;
    private InMemoryLedgerEntryRepository entryRepository;
    private InMemoryPaymentRepository paymentRepository;
    private InMemoryReconciliationBatchRepository batchRepository;
    private LedgerService ledgerService;
    private FeeConfiguration feeConfiguration;
    private PaymentService paymentService;
    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        accountRepository = new InMemoryAccountRepository();
        entryRepository = new InMemoryLedgerEntryRepository();
        paymentRepository = new InMemoryPaymentRepository();
        batchRepository = new InMemoryReconciliationBatchRepository();
        ledgerService = new LedgerService(accountRepository, entryRepository);
        feeConfiguration = new FeeConfiguration();
        paymentService = new PaymentService(paymentRepository, ledgerService, feeConfiguration);
        reconciliationService = new ReconciliationService(paymentRepository, batchRepository);
    }

    @Test
    @DisplayName("极小正费率算出 0.00 手续费：快照保留费率/0.00/净额=总额且可查；账务按两行净额入账、守恒")
    void tinyPositiveRateYieldsZeroFeeButSnapshotIsRetained() {
        // 1.00 * 1bp / 10000 = 0.0001 -> HALF_UP -> 0.00（尾差 -0.0001），净额 = 1.00
        PaymentCommand command = new PaymentCommand(
                "ZERO-1", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "1.00",
                1, LedgerService.ACCT_FEE);
        PaymentInstruction p = paymentService.submit(command);
        assertEquals(PaymentStatus.POSTED, p.getStatus());

        FeeSettlement fs = p.getFeeSettlement();
        assertNotNull(fs, "手续费算成 0.00 也必须保留费率快照，不能当成没有手续费");
        log.info("[判定依据] ZERO-1 快照: 费率={}bp 手续费={} 理论={} 尾差={} 净额={} 账户={}",
                fs.feeRateBp(), fs.feeAmount(), fs.rawFeeAmount(), fs.roundingAdjustment(),
                fs.netAmount(), fs.feeAccountNo());
        assertEquals(1, fs.feeRateBp());
        assertEquals(0, fs.feeAmount().compareTo(new BigDecimal("0.00")), "入账手续费为 0.00");
        assertEquals(0, fs.netAmount().compareTo(new BigDecimal("1.00")), "净额等于总额");
        assertEquals(0, fs.roundingAdjustment().compareTo(new BigDecimal("-0.0001")),
                "舍去尾差精确为 -0.0001（分位以外小数的去向可解释）");
        // 恒等式严格成立：入账手续费 = 理论手续费 + 尾差；总额 = 净额 + 手续费
        assertEquals(0, fs.feeAmount().compareTo(fs.rawFeeAmount().add(fs.roundingAdjustment())));
        assertEquals(0, p.getAmount().amount().compareTo(fs.netAmount().add(fs.feeAmount())));

        // 单独查询必须查得到（不是不存在）
        FeeSettlement queried = paymentService.getFeeSettlement("ZERO-1");
        assertEquals(fs, queried);

        // 账务：两行凭证，不产生 0 金额的手续费分录行；净额=总额入账，手续费账户零发生额
        List<LedgerEntry> entries = entryRepository.findByVoucherNo("ZERO-1");
        assertEquals(2, entries.size(), "0 手续费不产生手续费账户分录行");
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (LedgerEntry e : entries) {
            debit = debit.add(e.debit());
            credit = credit.add(e.credit());
            assertTrue(!e.accountCode().equals(LedgerService.ACCT_FEE), "手续费账户不应有 0 金额分录");
        }
        assertEquals(0, debit.compareTo(new BigDecimal("1.00")));
        assertEquals(0, credit.compareTo(new BigDecimal("1.00")));
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_FEE).getBalance().signum(),
                "0 手续费时手续费账户余额不变");
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum(), "全账户合计恒为 0");
    }

    @Test
    @DisplayName("显式 0bp 费率与本地默认 0bp 费率生效时同样保留 0 费率快照；无费率要素老行为不变")
    void explicitZeroRateAndDefaultZeroRateRetainedWhileLegacyStaysNull() {
        // 显式 0bp：快照存在
        FeeSettlement explicit = paymentService.submit(new PaymentCommand(
                "ZERO-EXPLICIT", "1001", "2001", "8.00", 0, "3001")).getFeeSettlement();
        assertNotNull(explicit);
        assertEquals(0, explicit.feeRateBp());
        assertEquals(0, explicit.feeAmount().compareTo(new BigDecimal("0.00")));
        assertEquals(0, explicit.netAmount().compareTo(new BigDecimal("8.00")));

        // 本地默认费率 0bp 且启用：默认费率生效，快照同样保留
        feeConfiguration.update(true, 0, LedgerService.ACCT_FEE);
        PaymentInstruction byConfig = paymentService.submit(new PaymentCommand(
                "ZERO-CONFIG", "1001", "2001", "8.00"));
        FeeSettlement cfg = byConfig.getFeeSettlement();
        assertNotNull(cfg, "本地默认费率（含 0bp）生效时也要留痕");
        assertEquals(0, cfg.feeRateBp());
        assertEquals(0, cfg.feeAmount().compareTo(new BigDecimal("0.00")));
        assertEquals(0, cfg.netAmount().compareTo(new BigDecimal("8.00")));
        log.info("[判定依据] 默认 0bp 生效的 ZERO-CONFIG 仍可查费率快照");

        // 关闭配置且请求不带费率：老行为，无快照（查询为 null）
        feeConfiguration.disable();
        PaymentInstruction legacy = paymentService.submit(new PaymentCommand(
                "ZERO-LEGACY", "1001", "2001", "8.00"));
        assertNull(legacy.getFeeSettlement(), "无任何费率要素时保持无手续费老行为");
        assertNull(paymentService.getFeeSettlement("ZERO-LEGACY"));
    }

    @Test
    @DisplayName("幂等强度：内容完全相同的 0 手续费请求=>DUPLICATE；改费率/账户/去要素=>CONFLICT")
    void duplicateVsConflictForZeroFeeRequests() {
        PaymentCommand first = new PaymentCommand(
                "ZERO-IDEM", "1001", "2001", "1.00", 1, "3001");
        paymentService.submit(first);
        // 完全相同重放（手续费同样是 0.00）=> 重复请求
        DuplicateRequestException dup = assertThrows(DuplicateRequestException.class,
                () -> paymentService.submit(first));
        log.info("[相同重放] {}", dup.getMessage());

        // 费率 1bp -> 2bp，两次手续费都算成 0.00，但费率不同 => 状态冲突，不是重复
        ConflictException c1 = assertThrows(ConflictException.class, () -> paymentService.submit(
                new PaymentCommand("ZERO-IDEM", "1001", "2001", "1.00", 2, "3001")));
        log.info("[费率被篡改(手续费同为0.00)] {}", c1.getMessage());

        // 1bp -> 0bp，手续费也都是 0.00，费率不同仍 => 状态冲突
        assertThrows(ConflictException.class, () -> paymentService.submit(
                new PaymentCommand("ZERO-IDEM", "1001", "2001", "1.00", 0, "3001")));

        // 另开手续费账户，篡改账户 => 状态冲突
        ledgerService.createAccount("3002", AccountType.LIABILITY, "另一个手续费账户", BigDecimal.ZERO);
        assertThrows(ConflictException.class, () -> paymentService.submit(
                new PaymentCommand("ZERO-IDEM", "1001", "2001", "1.00", 1, "3002")));

        // 去掉全部费率要素 => 状态冲突（有快照 vs 无快照）
        assertThrows(ConflictException.class, () -> paymentService.submit(
                new PaymentCommand("ZERO-IDEM", "1001", "2001", "1.00")));

        // 冲突拒绝不得新增任何指令或分录
        assertEquals(1, paymentService.findAll().size());
        assertEquals(2, entryRepository.findByVoucherNo("ZERO-IDEM").size());
    }

    @Test
    @DisplayName("并发 20 线程提交同一 0 手续费 businessId：仅 1 笔成功，费率/手续费/净额/账务唯一一致")
    void concurrentZeroFeeSubmissionYieldsSingleIdenticalResult() throws Exception {
        int threads = 20;
        String businessId = "ZERO-CONC";
        PaymentCommand command = new PaymentCommand(
                businessId, "1001", "2001", "1.00", 1, "3001");

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
                    winnerFee.set(paymentService.submit(command).getFeeSettlement());
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

        assertEquals(1, success.get(), "并发只能有一笔有效结果");
        assertEquals(threads - 1, reasons.getOrDefault("DUPLICATE_REQUEST", new AtomicInteger()).get());
        FeeSettlement fs = winnerFee.get();
        assertNotNull(fs);
        assertEquals(1, fs.feeRateBp());
        assertEquals(0, fs.feeAmount().compareTo(new BigDecimal("0.00")));
        assertEquals(0, fs.netAmount().compareTo(new BigDecimal("1.00")));
        log.info("[判定依据] 0 手续费并发唯一结果: 费率={}bp 手续费={} 净额={}",
                fs.feeRateBp(), fs.feeAmount(), fs.netAmount());

        // 输家查询到的快照与赢家完全一致；账务唯一（两行）且守恒
        assertEquals(fs, paymentService.getByBusinessId(businessId).getFeeSettlement());
        assertEquals(2, entryRepository.findByVoucherNo(businessId).size());
        assertEquals(1, paymentService.findAll().size());
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum());
    }

    @Test
    @DisplayName("费率锁定：0 手续费支付受理后调整费率配置，费率/0.00 手续费/净额与查询结果都不变")
    void zeroFeePaymentLocksRateAcrossLaterConfigChange() {
        feeConfiguration.update(true, 1, "3001");
        PaymentInstruction p = paymentService.submit(new PaymentCommand(
                "ZERO-LOCK", "1001", "2001", "1.00"));
        FeeSettlement before = p.getFeeSettlement();
        assertNotNull(before);
        assertEquals(1, before.feeRateBp());
        assertEquals(0, before.feeAmount().compareTo(new BigDecimal("0.00")));
        assertEquals(0, before.netAmount().compareTo(new BigDecimal("1.00")));

        // 受理后大幅调整费率
        feeConfiguration.update(true, 60, "3001");
        FeeSettlement after = paymentService.getFeeSettlement("ZERO-LOCK");
        assertEquals(before, after, "已受理支付的快照不受后续配置变更影响");
        assertEquals(1, after.feeRateBp());
        assertEquals(0, after.feeAmount().compareTo(new BigDecimal("0.00")));
        assertEquals(0, after.netAmount().compareTo(new BigDecimal("1.00")));

        // 按新配置重放同一 businessId（不带费率要素）：内容不同 => 冲突，且原结果不变
        assertThrows(ConflictException.class, () -> paymentService.submit(
                new PaymentCommand("ZERO-LOCK", "1001", "2001", "1.00")));
        assertEquals(before, paymentService.getFeeSettlement("ZERO-LOCK"));
        log.info("[判定依据] 默认费率 1->60bp 后，ZERO-LOCK 仍为 1bp/0.00/1.00");
    }

    @Test
    @DisplayName("对账按保留的 0.00 手续费/净额参与核对：一致 MATCHED、渠道报有费 FEE_MISMATCH、老口径不变")
    void reconciliationUsesRetainedZeroFeeAndNet() {
        // 1.00, 1bp => 本地保留 fee=0.00, net=1.00
        paymentService.submit(new PaymentCommand(
                "ZERO-REC", "1001", "2001", "1.00", 1, "3001"));

        // 渠道按 0.00/1.00 核对 => MATCHED，依据列出实际值
        ReconciliationBatch ok = reconciliationService.reconcile(new ReconciliationCommand(
                "ZR-1",
                List.of(channel("ZERO-REC", "1.00", "0.00", "1.00")),
                List.of("ZERO-REC")));
        ReconciliationResultItem okItem = ok.items().get(0);
        assertEquals(DifferenceType.MATCHED, okItem.differenceType());
        assertTrue(okItem.basis().contains("手续费一致(0.00)"), okItem.basis());
        assertTrue(okItem.basis().contains("净额一致(1.00)"), okItem.basis());
        log.info("[0手续费一致] {}", okItem.basis());

        // 渠道却报有费 0.60/0.40：净额先不符 => NET_MISMATCH，依据列双方实际值
        ReconciliationResultItem netDiff = reconciliationService.reconcile(new ReconciliationCommand(
                "ZR-2",
                List.of(channel("ZERO-REC", "1.00", "0.60", "0.40")),
                List.of("ZERO-REC"))).items().get(0);
        assertEquals(DifferenceType.NET_MISMATCH, netDiff.differenceType());
        assertTrue(netDiff.basis().contains("净额 本地=1.00 渠道=0.40"), netDiff.basis());
        assertTrue(netDiff.basis().contains("手续费实际值 本地=0.00 渠道=0.60"), netDiff.basis());

        // 渠道净额一致 1.00、手续费报 0.01 => FEE_MISMATCH（0 手续费留痕参与核对，不被当成无费）
        ReconciliationResultItem feeDiff = reconciliationService.reconcile(new ReconciliationCommand(
                "ZR-3",
                List.of(channel("ZERO-REC", "1.00", "0.01", "1.00")),
                List.of("ZERO-REC"))).items().get(0);
        assertEquals(DifferenceType.FEE_MISMATCH, feeDiff.differenceType());
        assertTrue(feeDiff.basis().contains("手续费 本地=0.00 渠道=0.01"), feeDiff.basis());
        log.info("[0手续费但渠道报有费] {}", feeDiff.basis());

        // 老批次（渠道不带 fee/net）：只按总额口径核对 => MATCHED，行为与扩展前一致
        ReconciliationResultItem legacy = reconciliationService.reconcile(new ReconciliationCommand(
                "ZR-4",
                List.of(new ChannelSettlementRecord("ZERO-REC", new BigDecimal("1.00"),
                        ChannelStatus.SUCCESS, Instant.now())),
                List.of("ZERO-REC"))).items().get(0);
        assertEquals(DifferenceType.MATCHED, legacy.differenceType());
        assertTrue(legacy.basis().contains("按总额口径核对"), legacy.basis());
    }

    @Test
    @DisplayName("尾差固定规则：舍去产生负尾差（正手续费），进位产生正尾差，恒等式严格成立、去向可解释")
    void roundingAdjustmentSignsAndIdentityExplained() {
        // 舍去（负尾差）：1.00 * 6bp / 10000 = 0.0006 -> HALF_UP -> 0.00，尾差 -0.0006
        FeeSettlement truncated = paymentService.submit(new PaymentCommand(
                "ADJ-DOWN", "1001", "2001", "1.00", 6, "3001")).getFeeSettlement();
        assertEquals(0, truncated.feeAmount().compareTo(new BigDecimal("0.00")));
        assertEquals(0, truncated.roundingAdjustment().compareTo(new BigDecimal("-0.0006")),
                "舍去时尾差为负，精确保留 -0.0006");
        assertEquals(0, truncated.feeAmount().compareTo(
                truncated.rawFeeAmount().add(truncated.roundingAdjustment())));
        log.info("[舍去-负尾差] 理论={} 入账={} 尾差={}",
                truncated.rawFeeAmount(), truncated.feeAmount(), truncated.roundingAdjustment());

        // 进位（正尾差，正手续费）：5.00 * 11bp / 10000 = 0.0055 -> 0.01，尾差 +0.0045
        FeeSettlement carried = paymentService.submit(new PaymentCommand(
                "ADJ-UP", "1001", "2001", "5.00", 11, "3001")).getFeeSettlement();
        assertEquals(0, carried.feeAmount().compareTo(new BigDecimal("0.01")));
        assertEquals(0, carried.roundingAdjustment().compareTo(new BigDecimal("0.0045")),
                "进位时尾差为正，精确保留 0.0045");
        assertEquals(0, carried.feeAmount().compareTo(
                carried.rawFeeAmount().add(carried.roundingAdjustment())));
        log.info("[进位-正尾差] 理论={} 入账={} 尾差={}",
                carried.rawFeeAmount(), carried.feeAmount(), carried.roundingAdjustment());
    }

    private ChannelSettlementRecord channel(String id, String amount, String fee, String net) {
        return new ChannelSettlementRecord(id, new BigDecimal(amount), ChannelStatus.SUCCESS,
                Instant.now(), new BigDecimal(fee), new BigDecimal(net));
    }
}
