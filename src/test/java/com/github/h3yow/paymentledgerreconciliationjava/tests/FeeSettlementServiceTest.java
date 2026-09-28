package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.github.h3yow.paymentledgerreconciliationjava.domain.FeeSettlement;
import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerEntry;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentInstruction;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentStatus;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryAccountRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryLedgerEntryRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryPaymentRepository;
import com.github.h3yow.paymentledgerreconciliationjava.service.FeeConfiguration;
import com.github.h3yow.paymentledgerreconciliationjava.service.InvalidParameterException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手续费净额结算：费率计算、尾差处理、三账户分账、借贷平衡、
 * 费率/净额/手续费单独可查、各类非法情形在账务前拒绝且原因可区分、费率快照随后续调整不变。
 */
class FeeSettlementServiceTest {

    private static final Logger log = LoggerFactory.getLogger(FeeSettlementServiceTest.class);

    private InMemoryAccountRepository accountRepository;
    private InMemoryLedgerEntryRepository entryRepository;
    private InMemoryPaymentRepository paymentRepository;
    private LedgerService ledgerService;
    private FeeConfiguration feeConfiguration;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        accountRepository = new InMemoryAccountRepository();
        entryRepository = new InMemoryLedgerEntryRepository();
        paymentRepository = new InMemoryPaymentRepository();
        ledgerService = new LedgerService(accountRepository, entryRepository);
        feeConfiguration = new FeeConfiguration();
        paymentService = new PaymentService(paymentRepository, ledgerService, feeConfiguration);
    }

    @Test
    @DisplayName("带手续费净额结算：总额拆净额+手续费分记三账户，凭证平衡、余额可回溯、费率快照可查")
    void feePaymentPostsNetAndFeeToSeparateAccounts() {
        // 100.00，费率 60bp(0.60%) => 手续费 0.60，净额 99.40
        PaymentCommand command = new PaymentCommand(
                "FEE-1", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "100.00",
                60, LedgerService.ACCT_FEE);
        PaymentInstruction p = paymentService.submit(command);
        assertEquals(PaymentStatus.POSTED, p.getStatus());

        FeeSettlement fs = p.getFeeSettlement();
        assertNotNull(fs, "指令必须带手续费快照");
        log.info("[判定依据] 快照: 费率={}bp 手续费={} 理论={} 尾差={} 净额={} 手续费账户={}",
                fs.feeRateBp(), fs.feeAmount(), fs.rawFeeAmount(), fs.roundingAdjustment(),
                fs.netAmount(), fs.feeAccountNo());
        assertEquals(60, fs.feeRateBp());
        assertEquals(0, fs.feeAmount().compareTo(new BigDecimal("0.60")));
        assertEquals(0, fs.netAmount().compareTo(new BigDecimal("99.40")));
        assertEquals(0, fs.roundingAdjustment().signum(), "整除时尾差为 0");
        assertEquals(LedgerService.ACCT_FEE, fs.feeAccountNo());

        // 账户余额：付款方 +100.00（借）、收款方 -99.40（贷）、手续费账户 -0.60（贷）
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_PAYER).getBalance()
                .compareTo(new BigDecimal("100.00")));
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_PAYEE).getBalance()
                .compareTo(new BigDecimal("-99.40")));
        assertEquals(0, accountRepository.getByCode(LedgerService.ACCT_FEE).getBalance()
                .compareTo(new BigDecimal("-0.60")));
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum(), "三账户合计恒为 0");

        // 凭证三行：借总额、贷净额、贷手续费
        List<LedgerEntry> entries = entryRepository.findByVoucherNo("FEE-1");
        assertEquals(3, entries.size());
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (LedgerEntry e : entries) {
            debit = debit.add(e.debit());
            credit = credit.add(e.credit());
        }
        assertEquals(0, debit.compareTo(new BigDecimal("100.00")));
        assertEquals(0, credit.compareTo(new BigDecimal("100.00")), "借贷必须相等");

        // 单独查询手续费/净额
        FeeSettlement queried = paymentService.getFeeSettlement("FEE-1");
        assertEquals(fs, queried);
    }

    @Test
    @DisplayName("尾差处理：分位以外小数按 HALF_UP 到分，尾差入账手续费，恒等式可解释每笔去向")
    void subCentFeeRoundedHalfUpAndExplained() {
        // 10.01 * 35 / 10000 = 0.035035 => HALF_UP => 0.04（尾差 +0.004965 进入手续费）
        PaymentInstruction p = paymentService.submit(new PaymentCommand(
                "FEE-ROUND", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "10.01",
                35, LedgerService.ACCT_FEE));
        FeeSettlement fs = p.getFeeSettlement();
        log.info("[判定依据] 理论手续费={}, 入账手续费={}, 尾差={}, 净额={}",
                fs.rawFeeAmount(), fs.feeAmount(), fs.roundingAdjustment(), fs.netAmount());
        assertEquals(0, fs.feeAmount().compareTo(new BigDecimal("0.04")),
                "0.035035 按 HALF_UP 应为 0.04");
        assertEquals(0, fs.roundingAdjustment().compareTo(new BigDecimal("0.004965")),
                "尾差保留精确值 0.004965（解释分位以外小数的去向）");
        assertEquals(0, fs.netAmount().compareTo(new BigDecimal("9.97")));

        // 恒等式：入账手续费 = 理论手续费 + 尾差（BigDecimal 精确成立）
        BigDecimal recomputed = fs.rawFeeAmount().add(fs.roundingAdjustment());
        assertEquals(0, fs.feeAmount().compareTo(recomputed),
                "入账手续费必须等于理论手续费加尾差");
        // 总额 = 净额 + 入账手续费
        assertEquals(0, p.getAmount().amount().compareTo(fs.netAmount().add(fs.feeAmount())));
    }

    @Test
    @DisplayName("明确进位尾差：0.0055 进位到 0.01，尾差精确可解释")
    void halfUpCarryProducesPositiveAdjustment() {
        // 5.00 * 11 / 10000 = 0.0055 -> HALF_UP = 0.01，尾差 +0.0045
        PaymentInstruction p = paymentService.submit(new PaymentCommand(
                "FEE-CARRY", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "5.00",
                11, LedgerService.ACCT_FEE));
        FeeSettlement fs = p.getFeeSettlement();
        log.info("[判定依据] 理论={} 入账={} 尾差={} 净额={}",
                fs.rawFeeAmount(), fs.feeAmount(), fs.roundingAdjustment(), fs.netAmount());
        assertEquals(0, fs.feeAmount().compareTo(new BigDecimal("0.01")));
        assertEquals(0, fs.roundingAdjustment().compareTo(new BigDecimal("0.0045")),
                "尾差保留精确值 0.0045");
        assertEquals(0, fs.netAmount().compareTo(new BigDecimal("4.99")));
        assertEquals(0, fs.rawFeeAmount().add(fs.roundingAdjustment()).compareTo(fs.feeAmount()));
    }

    @Test
    @DisplayName("非法情形在任何账务之前拒绝，原因可区分：费率非法/净额非正/手续费账户不存在/账户相同")
    void invalidFeeScenariosRejectedBeforeAnyPosting() {
        assertReasonContains(new PaymentCommand(
                "FEE-BAD-1", "1001", "2001", "10.00", 10_000, "3001"), "费率非法");
        assertReasonContains(new PaymentCommand(
                "FEE-BAD-2", "1001", "2001", "10.00", -1, "3001"), "费率非法");
        assertReasonContains(new PaymentCommand(
                "FEE-BAD-3", "1001", "2001", "10.00", 9_999, "3001"), "净额不合理");
        assertReasonContains(new PaymentCommand(
                "FEE-BAD-4", "1001", "2001", "10.00", 60, "9999"), "账户不存在");
        assertReasonContains(new PaymentCommand(
                "FEE-BAD-5", "1001", "2001", "10.00", 60, "1001"), "不能与付款方或收款方账户相同");
        assertReasonContains(new PaymentCommand(
                "FEE-BAD-6", "1001", "2001", "10.00", null, "3001"),
                "指定了手续费收款账户但未提供费率");

        assertEquals(0, paymentService.findAll().size(), "非法请求不得留下任何指令");
        assertEquals(0, entryRepository.findAll().size(), "非法请求不得留下任何分录");
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum());
    }

    @Test
    @DisplayName("费率快照锁定：受理后调整费率，原支付的手续费/净额与查询结果不变")
    void acceptedPaymentLocksRateSnapshotAcrossLaterConfigChange() {
        feeConfiguration.update(true, 60, LedgerService.ACCT_FEE);
        PaymentInstruction p = paymentService.submit(new PaymentCommand(
                "FEE-LOCK", "1001", "2001", "200.00"));
        FeeSettlement before = p.getFeeSettlement();
        assertEquals(0, before.feeAmount().compareTo(new BigDecimal("1.20")));
        assertEquals(0, before.netAmount().compareTo(new BigDecimal("198.80")));

        // 之后大幅调整费率
        feeConfiguration.update(true, 120, LedgerService.ACCT_FEE);
        FeeSettlement after = paymentService.getFeeSettlement("FEE-LOCK");
        assertEquals(60, after.feeRateBp(), "已受理支付锁定受理当时费率");
        assertEquals(0, after.feeAmount().compareTo(new BigDecimal("1.20")));
        assertEquals(0, after.netAmount().compareTo(new BigDecimal("198.80")));
        // 已出分录金额也不变
        List<LedgerEntry> entries = entryRepository.findByVoucherNo("FEE-LOCK");
        BigDecimal feeCredit = entries.stream()
                .filter(e -> e.accountCode().equals(LedgerService.ACCT_FEE))
                .map(LedgerEntry::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, feeCredit.compareTo(new BigDecimal("1.20")));
        log.info("[判定依据] 费率 60->120bp 后，FEE-LOCK 仍为手续费 1.20 / 净额 198.80");
    }

    @Test
    @DisplayName("本地配置默认驱动：启用配置后不带费率的请求也按默认费率计手续费；关闭后新支付无手续费")
    void defaultConfigurationDrivesFeeAndCanBeDisabled() {
        // 默认配置关闭：不带费率要素的支付无手续费
        assertNull(paymentService.submit(new PaymentCommand(
                "FEE-CFG-0", "1001", "2001", "50.00")).getFeeSettlement());

        feeConfiguration.update(true, 100, LedgerService.ACCT_FEE); // 1%
        PaymentInstruction p = paymentService.submit(new PaymentCommand(
                "FEE-CFG-1", "1001", "2001", "50.00"));
        assertEquals(0, p.getFeeSettlement().feeAmount().compareTo(new BigDecimal("0.50")));
        assertEquals(100, p.getFeeSettlement().feeRateBp());

        feeConfiguration.disable();
        PaymentInstruction p2 = paymentService.submit(new PaymentCommand(
                "FEE-CFG-2", "1001", "2001", "50.00"));
        assertNull(p2.getFeeSettlement(), "关闭配置后的新支付不带手续费");
        // 关闭配置时仍可逐单显式指定费率
        PaymentInstruction p3 = paymentService.submit(new PaymentCommand(
                "FEE-CFG-3", "1001", "2001", "50.00", 200, LedgerService.ACCT_FEE));
        assertEquals(0, p3.getFeeSettlement().feeAmount().compareTo(new BigDecimal("1.00")));
    }

    private void assertReasonContains(PaymentCommand command, String reasonPart) {
        InvalidParameterException ex = assertThrows(InvalidParameterException.class,
                () -> paymentService.submit(command));
        assertTrue(ex.getMessage().contains(reasonPart),
                "拒绝原因应包含 [" + reasonPart + "]，实际: " + ex.getMessage());
        log.info("[参数非法判定] {} -> INVALID_PARAMETER: {}", reasonPart, ex.getMessage());
    }
}
