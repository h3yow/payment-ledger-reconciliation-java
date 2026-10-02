package com.github.h3yow.paymentledgerreconciliationjava.tests;

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
 * 【手续费算成 0.00 的费率支付】专项：
 * <ol>
 *   <li>请求明确带费率（或本地默认费率生效）、按费率算出的手续费 HALF_UP 到分后正好 0.00 时，
 *       实际费率、手续费 0.00、扣费后净额（=总额）随支付一起保留，单独查询可查，不当成“没有手续费”；
 *       账务上按净额=总额两行凭证入账，不产生 0 金额分录行；</li>
 *   <li>内容完全相同（含费率、手续费账户）才算重复请求；任一项不同（哪怕两次手续费都是 0.00）
 *       也是状态冲突；并发同键最终只有一笔有效结果，账务完全一致、无半成品；</li>
 *   <li>受理当时费率锁定：之后改费率配置，这笔的费率/0.00 手续费/净额与查询结果都不变；</li>
 *   <li>对账按这笔实际保留的手续费 0.00 与净额参与核对；不带 fee/net 的老请求口径不变。</li>
 * </ol>
 */
class ZeroFeeRateRetentionTest {

    private static final Logger log = LoggerFactory.getLogger(ZeroFeeRateRetentionTest.class);

    private InMemoryAccountRepository accountRepository;
    private InMemoryLedgerEntryRepository entryRepository;
    private InMemoryPaymentRepository paymentRepository;
    private LedgerService ledgerService;
    private FeeConfiguration feeConfiguration;
    private PaymentService paymentService;
    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        accountRepository = new InMemoryAccountRepository();
        entryRepository = new InMemoryLedgerEntryRepository();
        paymentRepository = new InMemoryPaymentRepository();
        ledgerService = new LedgerService(accountRepository, entryRepository);
        feeConfiguration = new FeeConfiguration();
        paymentService = new PaymentService(paymentRepository, ledgerService, feeConfiguration);
        reconciliationService = new ReconciliationService(
                paymentRepository, new InMemoryReconciliationBatchRepository());
    }

    /** 1.00 * 4bp / 10000 = 0.0004，HALF_UP 到分 = 0.00（舍去，尾差 -0.0004）。 */
    private PaymentCommand zeroFeeCommand(String businessId, int rateBp) {
        return new PaymentCommand(businessId, LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE,
                "1.00", rateBp, LedgerService.ACCT_FEE);
    }

    @Test
    @DisplayName("显式费率算得手续费0.00：费率/手续费0.00/净额=总额随支付保留且可单独查询，账务两行无0金额分录")
    void explicitRateWithRoundedZeroFeeKeepsRateTraceAndPostsTwoLines() {
        PaymentInstruction p = paymentService.submit(zeroFeeCommand("ZF-1", 4));
        assertEquals(PaymentStatus.POSTED, p.getStatus());

        FeeSettlement fs = p.getFeeSettlement();
        assertNotNull(fs, "带费率受理的支付即使手续费为 0.00 也必须挂手续费快照，不能当成无手续费");
        log.info("[0.00 手续费留痕] businessId=ZF-1 费率={}bp 理论={} 入账手续费={} 尾差={} 净额={} 手续费账户={}",
                fs.feeRateBp(), fs.rawFeeAmount(), fs.feeAmount(), fs.roundingAdjustment(),
                fs.netAmount(), fs.feeAccountNo());
        assertEquals(4, fs.feeRateBp());
        assertEquals(0, fs.feeAmount().compareTo(new BigDecimal("0.00")), "入账手续费 0.00");
        assertEquals(0, fs.rawFeeAmount().compareTo(new BigDecimal("0.0004")), "理论手续费 0.0004");
        assertEquals(0, fs.roundingAdjustment().compareTo(new BigDecimal("-0.0004")),
                "HALF_UP 舍去时尾差为负：0.00 - 0.0004 = -0.0004");
        assertEquals(0, fs.netAmount().compareTo(new BigDecimal("1.00")), "净额=总额 1.00");
        assertEquals(LedgerService.ACCT_FEE, fs.feeAccountNo());
        // 恒等式严格成立（含负尾差）
        assertEquals(0, fs.feeAmount().compareTo(fs.rawFeeAmount().add(fs.roundingAdjustment())));
        assertEquals(0, p.getAmount().amount().compareTo(fs.netAmount().add(fs.feeAmount())));

        // 单独查询：必须查得到，而不是 FEE_SETTLEMENT_NOT_FOUND
        FeeSettlement queried = paymentService.getFeeSettlement("ZF-1");
        assertEquals(fs, queried, "单独查询返回的快照必须与受理时一致");

        // 账务：两行凭证（借 1001 / 贷 2001），无 0 金额分录行；3001 不参与、余额保持 0.00
        List<LedgerEntry> entries = entryRepository.findByVoucherNo("ZF-1");
        assertEquals(2, entries.size(), "0.00 手续费不产生手续费分录行，按两行凭证入账");
        for (LedgerEntry e : entries) {
            assertTrue(e.debit().signum() > 0 || e.credit().signum() > 0,
                    "不允许出现借贷都为 0 的半成品分录行");
        }
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_PAYER).getBalance()
                .compareTo(new BigDecimal("1.00")));
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_PAYEE).getBalance()
                .compareTo(new BigDecimal("-1.00")), "收款方按净额=总额 1.00 入账");
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_FEE).getBalance().signum(),
                "手续费账户无发生额，余额 0.00");
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum(), "全账户合计恒为 0");
    }

    @Test
    @DisplayName("显式零费率同样保留快照；与完全不带费率要素的支付可区分（后者查询为不存在）")
    void explicitZeroRateKeptButPlainPaymentHasNoSettlement() {
        paymentService.submit(zeroFeeCommand("ZF-ZERO-RATE", 0));
        FeeSettlement zeroRateFs = paymentService.getFeeSettlement("ZF-ZERO-RATE");
        assertNotNull(zeroRateFs, "显式 0bp 仍是费率生效，快照必须保留");
        assertEquals(0, zeroRateFs.feeRateBp());
        assertEquals(0, zeroRateFs.feeAmount().signum());
        assertEquals(0, zeroRateFs.netAmount().compareTo(new BigDecimal("1.00")));
        log.info("[0bp 留痕] 费率=0bp 手续费={} 净额={}", zeroRateFs.feeAmount(), zeroRateFs.netAmount());

        // 完全不带费率要素、本地配置也关闭：无手续费，查询快照为 null（404 语义保持不变）
        PaymentInstruction plain = paymentService.submit(new PaymentCommand(
                "ZF-PLAIN", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "1.00"));
        assertNull(plain.getFeeSettlement(), "未带费率且无默认配置的支付不应有手续费快照");
        assertNull(paymentService.getFeeSettlement("ZF-PLAIN"), "无手续费要素的支付单独查询为不存在");
        log.info("[可区分] ZF-ZERO-RATE 带 0bp 快照可查；ZF-PLAIN 无快照（404 语义）");
    }

    @Test
    @DisplayName("本地默认费率生效且手续费算成0.00：快照同样保留；之后关闭配置不影响已受理这笔")
    void defaultConfigRateWithZeroFeeAlsoKept() {
        // 默认费率 4bp：1.00 * 4bp = 0.0004 -> 0.00
        feeConfiguration.update(true, 4, LedgerService.ACCT_FEE);
        PaymentInstruction p = paymentService.submit(new PaymentCommand(
                "ZF-CFG", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "1.00"));
        FeeSettlement fs = p.getFeeSettlement();
        assertNotNull(fs, "本地默认费率生效时，即使手续费 0.00 也必须保留快照");
        assertEquals(4, fs.feeRateBp());
        assertEquals(0, fs.feeAmount().signum());
        assertEquals(0, fs.netAmount().compareTo(new BigDecimal("1.00")));

        // 之后改/关费率配置：已受理这笔查询结果不变
        feeConfiguration.disable();
        FeeSettlement still = paymentService.getFeeSettlement("ZF-CFG");
        assertEquals(fs, still, "改配置后已受理支付的费率/0.00 手续费/净额不变");
        log.info("[默认费率 0.00 手续费留痕并锁定] 4bp 关闭配置后仍可查: {}", still);
    }

    @Test
    @DisplayName("两笔手续费都为0.00但费率不同：相同请求=>DUPLICATE；改费率/改手续费账户=>CONFLICT")
    void duplicateVsConflictDistinguishedEvenWhenBothFeesZero() {
        PaymentCommand first = zeroFeeCommand("ZF-IDEM", 4);
        paymentService.submit(first);

        // 完全相同（费率 4bp、手续费账户 3001、金额账户都同）=> 重复请求
        assertThrows(DuplicateRequestException.class, () -> paymentService.submit(first),
                "内容完全相同（含费率与手续费账户）即使手续费 0.00 也是重复请求");

        // 费率改成 9bp：1.00 * 9bp = 0.0009，HALF_UP 到分仍是 0.00，但请求内容不同 => 冲突
        PaymentCommand differentRateSameZeroFee = zeroFeeCommand("ZF-IDEM", 9);
        ConflictException c1 = assertThrows(ConflictException.class,
                () -> paymentService.submit(differentRateSameZeroFee));
        log.info("[都为0.00但费率不同 => 状态冲突] {}", c1.getMessage());

        // 另开手续费账户，费率仍 4bp、手续费仍 0.00 => 冲突
        ledgerService.createAccount("3002",
                com.github.h3yow.paymentledgerreconciliationjava.domain.AccountType.LIABILITY,
                "另一个手续费账户", BigDecimal.ZERO);
        PaymentCommand differentAccount = new PaymentCommand(
                "ZF-IDEM", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE,
                "1.00", 4, "3002");
        ConflictException c2 = assertThrows(ConflictException.class,
                () -> paymentService.submit(differentAccount));
        log.info("[都为0.00但手续费账户不同 => 状态冲突] {}", c2.getMessage());

        // 改成完全不带费率要素 => 内容同样不同 => 冲突（有快照 vs 无快照）
        PaymentCommand noFeeElements = new PaymentCommand(
                "ZF-IDEM", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "1.00");
        assertThrows(ConflictException.class, () -> paymentService.submit(noFeeElements));

        // 最终只有一笔指令、一张两行凭证
        assertEquals(1, paymentService.findAll().size());
        assertEquals(2, entryRepository.findByVoucherNo("ZF-IDEM").size());
        FeeSettlement winner = paymentService.getFeeSettlement("ZF-IDEM");
        assertEquals(4, winner.feeRateBp(), "赢家快照始终是首次受理的 4bp");
        assertEquals(0, winner.feeAmount().signum());
    }

    @Test
    @DisplayName("并发20线程提交同一手续费0.00的费率支付：仅1笔成功，快照/两行分录/余额完全一致")
    void concurrentZeroFeeSubmissionYieldsSingleIdenticalResult() throws Exception {
        int threads = 20;
        String businessId = "ZF-CONC-1";
        PaymentCommand command = zeroFeeCommand(businessId, 4);

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
                reasons.getOrDefault("DUPLICATE_REQUEST", new AtomicInteger()).get(),
                "其余 19 笔必须全部按重复请求拒绝");
        FeeSettlement fs = winnerFee.get();
        assertNotNull(fs);
        log.info("[0.00 手续费并发唯一] 唯一成功快照: 费率={}bp 手续费={} 净额={} 尾差={}",
                fs.feeRateBp(), fs.feeAmount(), fs.netAmount(), fs.roundingAdjustment());
        assertEquals(4, fs.feeRateBp());
        assertEquals(0, fs.feeAmount().signum());
        assertEquals(0, fs.netAmount().compareTo(new BigDecimal("1.00")));

        // 账务唯一：一张两行凭证，手续费账户零发生额，全局守恒
        List<LedgerEntry> entries = entryRepository.findByVoucherNo(businessId);
        assertEquals(2, entries.size(), "并发后只能有一张两行凭证，无半成品分录");
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_FEE).getBalance().signum());
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum());
        assertEquals(1, paymentService.findAll().size());
        assertEquals(fs, paymentService.getFeeSettlement(businessId), "回查快照与赢家完全一致");
    }

    @Test
    @DisplayName("费率锁定：按4bp受理得0.00手续费后把配置/请求费率改为60bp，原支付快照/分录/对账结论都不变")
    void laterRateConfigChangeDoesNotChangeZeroFeePayment() {
        feeConfiguration.update(true, 4, LedgerService.ACCT_FEE);
        paymentService.submit(new PaymentCommand(
                "ZF-LOCK", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "1.00"));

        // 之后大幅调高默认费率
        feeConfiguration.update(true, 60, LedgerService.ACCT_FEE);
        FeeSettlement still = paymentService.getFeeSettlement("ZF-LOCK");
        assertEquals(4, still.feeRateBp(), "已受理支付锁定受理当时费率 4bp");
        assertEquals(0, still.feeAmount().signum(), "手续费仍为 0.00");
        assertEquals(0, still.netAmount().compareTo(new BigDecimal("1.00")), "净额仍为 1.00");

        // 按新费率重放同一 businessId：内容不同 => 冲突（而不是用新费率覆盖）
        PaymentCommand at60 = new PaymentCommand(
                "ZF-LOCK", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE,
                "1.00", 60, LedgerService.ACCT_FEE);
        assertThrows(ConflictException.class, () -> paymentService.submit(at60));
        // 按原内容（配置已 60bp，但显式带 4bp）重放 => 仍是重复请求
        assertThrows(DuplicateRequestException.class,
                () -> paymentService.submit(zeroFeeCommand("ZF-LOCK", 4)));

        // 对账仍按保留的 0.00 手续费 / 1.00 净额核对：渠道同口径 => MATCHED
        ReconciliationResultItem item = reconcileWithFee("ZF-LOCK", "0.00", "1.00");
        assertEquals(DifferenceType.MATCHED, item.differenceType(),
                "对账必须按这笔实际保留的手续费 0.00 与净额 1.00 核对");
        // 渠道若报非 0 手续费（净额与本地一致仍为 1.00，总额也为 1.00）=> 手续费差异被如实识别
        ReconciliationResultItem mismatch = reconcileWithFee("ZF-LOCK-B2", "ZF-LOCK", "0.60", "1.00");
        assertEquals(DifferenceType.FEE_MISMATCH, mismatch.differenceType(),
                "本地实保留手续费 0.00 vs 渠道 0.60 必须识别为手续费不符");
        log.info("[0.00 手续费费率锁定+对账] 配置 4->60bp 后仍 4bp/0.00/1.00；MATCHED 与 FEE_MISMATCH 均可判定");
    }

    @Test
    @DisplayName("0.00手续费支付过账中途失败：余额全额冲正、零分录残留，FAILED终态且费率快照仍在；相同重放=>DUPLICATE")
    void zeroFeePaymentFailureMidPostingLeavesNoHalfDoneState() {
        String businessId = "ZF-FAIL";
        ledgerService.setFailureHook(voucherNo -> {
            throw new IllegalStateException("模拟：两行凭证第一行（付款方借方）更新后中断");
        });
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> paymentService.submit(zeroFeeCommand(businessId, 4)));
        log.info("[0.00 手续费过账失败] 抛出: {}", ex.getMessage());

        // 余额全额冲正、手续费账户从未被动过、全局守恒
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_PAYER).getBalance().signum());
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_PAYEE).getBalance().signum());
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_FEE).getBalance().signum());
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum());
        // 零分录残留
        assertTrue(entryRepository.findByVoucherNo(businessId).isEmpty());
        assertEquals(0, entryRepository.findAll().size());

        // 终态 FAILED，但费率快照仍随指令保留（不因失败丢失留痕）
        PaymentInstruction failed = paymentService.getByBusinessId(businessId);
        assertEquals(PaymentStatus.FAILED, failed.getStatus());
        FeeSettlement fs = failed.getFeeSettlement();
        assertNotNull(fs);
        assertEquals(4, fs.feeRateBp());
        assertEquals(0, fs.feeAmount().signum());
        assertEquals(0, fs.netAmount().compareTo(new BigDecimal("1.00")));

        // 故障移除后，相同内容重放同一 businessId => DUPLICATE（FAILED 终态不可再过账），
        // 账务仍为空，不会“失败后又成功”产生第二套结果
        ledgerService.setFailureHook(null);
        assertThrows(DuplicateRequestException.class,
                () -> paymentService.submit(zeroFeeCommand(businessId, 4)));
        assertEquals(0, entryRepository.findAll().size(), "FAILED 终态重放不产生任何分录");
        // 费率不同的重放 => CONFLICT（即使两次手续费都是 0.00）
        assertThrows(ConflictException.class,
                () -> paymentService.submit(zeroFeeCommand(businessId, 9)));
        log.info("[0.00 手续费失败原子性] 余额归零、零分录、FAILED 快照保留；相同=>DUPLICATE 改费率=>CONFLICT");
    }

    @Test
    @DisplayName("对账：0.00手续费支付与渠道fee=0.00/net=总额一致=>MATCHED；渠道净额或手续费不符分别识别；老口径不变")
    void reconciliationUsesRetainedZeroFeeAndNet() {
        paymentService.submit(zeroFeeCommand("ZF-REC", 4));

        // 渠道按这笔实际保留值对账：fee=0.00、net=1.00 => MATCHED，依据列出 0.00
        ReconciliationResultItem matched = reconcileWithFee("RB-ZF-1", "ZF-REC", "0.00", "1.00");
        assertEquals(DifferenceType.MATCHED, matched.differenceType());
        assertTrue(matched.basis().contains("手续费一致(0.00)"), matched.basis());
        assertTrue(matched.basis().contains("净额一致(1.00)"), matched.basis());
        log.info("[0.00 对账一致] {}", matched.basis());

        // 渠道净额错（0.99）=> NET_MISMATCH，依据列出本地净额 1.00
        ReconciliationResultItem netWrong = reconcileWithFee("RB-ZF-2", "ZF-REC", "0.01", "0.99");
        assertEquals(DifferenceType.NET_MISMATCH, netWrong.differenceType());
        assertTrue(netWrong.basis().contains("净额 本地=1.00 渠道=0.99"), netWrong.basis());

        // 渠道手续费错（0.01）、净额仍 1.00 => FEE_MISMATCH
        ReconciliationResultItem feeWrong = reconcileWithFee("RB-ZF-3", "ZF-REC", "0.01", "1.00");
        assertEquals(DifferenceType.FEE_MISMATCH, feeWrong.differenceType());
        assertTrue(feeWrong.basis().contains("手续费 本地=0.00 渠道=0.01"), feeWrong.basis());
        log.info("[0.00 对账差异] NET_MISMATCH: {} || FEE_MISMATCH: {}",
                netWrong.basis(), feeWrong.basis());

        // 老请求口径：渠道不带 fee/net，只按总额+状态核对 => MATCHED，行为不变
        var legacyCmd = new ReconciliationCommand("RB-ZF-4",
                List.of(new ChannelSettlementRecord("ZF-REC", new BigDecimal("1.00"),
                        ChannelStatus.SUCCESS, Instant.now(), null, null)),
                List.of("ZF-REC"));
        ReconciliationResultItem legacy = reconciliationService.reconcile(legacyCmd).items().get(0);
        assertEquals(DifferenceType.MATCHED, legacy.differenceType());
        assertTrue(legacy.basis().contains("按总额口径核对"), legacy.basis());
        log.info("[老请求口径不变] {}", legacy.basis());
    }

    /** 用默认批次号对单笔做带 fee/net 的对账。 */
    private ReconciliationResultItem reconcileWithFee(String businessId, String fee, String net) {
        return reconcileWithFee("RB-" + businessId, businessId, fee, net);
    }

    private ReconciliationResultItem reconcileWithFee(String batchId, String businessId,
                                                      String fee, String net) {
        var cmd = new ReconciliationCommand(batchId,
                List.of(new ChannelSettlementRecord(businessId, new BigDecimal("1.00"),
                        ChannelStatus.SUCCESS, Instant.now(),
                        new BigDecimal(fee), new BigDecimal(net))),
                List.of(businessId));
        ReconciliationBatch batch = reconciliationService.reconcile(cmd);
        return batch.items().get(0);
    }
}
