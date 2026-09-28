package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.github.h3yow.paymentledgerreconciliationjava.domain.Account;
import com.github.h3yow.paymentledgerreconciliationjava.domain.FeeSnapshot;
import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerEntry;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentInstruction;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentStatus;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryAccountRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryLedgerEntryRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryPaymentRepository;
import com.github.h3yow.paymentledgerreconciliationjava.service.FeePolicyService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 带手续费的净额结算：
 * 费用计算与三行平衡凭证、尾差去向可解释、费率/净额/账户类记账前拒绝原因可区分、
 * 受理时刻费率快照在策略调整后保持不变、费率与净额随支付可单独查出。
 */
class FeeNetSettlementTest {

    private static final Logger log = LoggerFactory.getLogger(FeeNetSettlementTest.class);

    private InMemoryAccountRepository accountRepository;
    private InMemoryLedgerEntryRepository entryRepository;
    private InMemoryPaymentRepository paymentRepository;
    private LedgerService ledgerService;
    private FeePolicyService feePolicyService;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        accountRepository = new InMemoryAccountRepository();
        entryRepository = new InMemoryLedgerEntryRepository();
        paymentRepository = new InMemoryPaymentRepository();
        ledgerService = new LedgerService(accountRepository, entryRepository);
        feePolicyService = new FeePolicyService();
        paymentService = new PaymentService(paymentRepository, ledgerService, feePolicyService);
    }

    private PaymentCommand feeCommand(String businessId, String amount, String rateBp, String feeAccount) {
        return new PaymentCommand(businessId, LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE,
                amount, rateBp, feeAccount);
    }

    @Test
    @DisplayName("带手续费支付：手续费与净额分账入账，三行凭证平衡，本金=净额+手续费，余额守恒")
    void feeAndNetPostedToSeparateAccounts() {
        // 100.00 × 60bp/10000 = 0.60，净额 99.40
        PaymentInstruction p = paymentService.submit(
                feeCommand("FEE-001", "100.00", "60", LedgerService.ACCT_FEE));
        FeeSnapshot s = p.getFeeSnapshot();
        log.info("[判定依据] {} 本金={} 费率={} 手续费={} 净额={} 尾差={}",
                p.getBusinessId(), p.getAmount(), s.rate(), s.fee(), s.netAmount(), s.roundingDifference());

        assertEquals(PaymentStatus.POSTED, p.getStatus());
        assertEquals(0, new BigDecimal("0.60").compareTo(s.fee().amount()));
        assertEquals(0, new BigDecimal("99.40").compareTo(s.netAmount().amount()));
        assertEquals(0, s.rawFee().compareTo(new BigDecimal("0.6000")));
        assertEquals(0, s.roundingDifference().compareTo(new BigDecimal("0.0000")), "无尾差");
        // 恒等式：本金 = 净额 + 手续费（到分精确）
        assertEquals(0, p.getAmount().amount()
                .compareTo(s.netAmount().amount().add(s.fee().amount())));

        // 三行凭证：借付款方 100.00；贷收款方 99.40；贷手续费账户 0.60
        var entries = entryRepository.findByVoucherNo("FEE-001");
        assertEquals(3, entries.size(), "带手续费凭证恰好 3 行");
        BigDecimal debitSum = BigDecimal.ZERO;
        BigDecimal creditSum = BigDecimal.ZERO;
        for (LedgerEntry e : entries) {
            debitSum = debitSum.add(e.debit());
            creditSum = creditSum.add(e.credit());
        }
        assertEquals(0, debitSum.compareTo(creditSum), "借贷必须平衡");
        assertEquals(0, debitSum.compareTo(new BigDecimal("100.00")));

        // 账户余额：付款方 +100.00（借），收款方 -99.40（贷），手续费收入 -0.60（贷）；合计 0
        BigDecimal payer = accountRepository.getByCode(LedgerService.ACCT_PAYER).getBalance();
        BigDecimal payee = accountRepository.getByCode(LedgerService.ACCT_PAYEE).getBalance();
        BigDecimal feeAcct = accountRepository.getByCode(LedgerService.ACCT_FEE).getBalance();
        log.info("[判定依据] 余额 付款方={} 收款方={} 手续费账户={} 合计={}",
                payer, payee, feeAcct, payer.add(payee).add(feeAcct));
        assertEquals(0, payer.compareTo(new BigDecimal("100.00")));
        assertEquals(0, payee.compareTo(new BigDecimal("-99.40")));
        assertEquals(0, feeAcct.compareTo(new BigDecimal("-0.60")));
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum(), "全局余额守恒");
    }

    @Test
    @DisplayName("手续费尾差按 HALF_UP 到分并由净额承担，尾差去向随快照可解释（0.006 进位 / 0.004 舍去）")
    void roundingTailExplainedAndBorneByNet() {
        // 10.00 × 6bp/10000 = 0.0060，HALF_UP 到分 => 0.01（进位），净额 9.99，尾差 +0.0040
        PaymentInstruction p = paymentService.submit(
                feeCommand("FEE-ROUND", "10.00", "6", LedgerService.ACCT_FEE));
        FeeSnapshot s = p.getFeeSnapshot();
        log.info("[判定依据] 原始手续费={} 到分手续费={} 净额={} 尾差={}",
                s.rawFee().toPlainString(), s.fee(), s.netAmount(), s.roundingDifference());
        assertEquals(0, s.rawFee().compareTo(new BigDecimal("0.0060")));
        assertEquals(0, s.fee().amount().compareTo(new BigDecimal("0.01")), "0.0060 HALF_UP 进位为 0.01");
        assertEquals(0, s.netAmount().amount().compareTo(new BigDecimal("9.99")), "尾差由收款方净额承担");
        assertEquals(0, s.roundingDifference().compareTo(new BigDecimal("0.0040")),
                "尾差=0.01−0.0060=+0.0040（手续费进位，收款方少拿 0.0040）");
        // 到分恒等式仍精确成立
        assertEquals(0, p.getAmount().amount()
                .compareTo(s.netAmount().amount().add(s.fee().amount())));
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum());

        // 舍去场景：10.00 × 4bp = 0.0040 => 0.00（舍去），净额 10.00
        PaymentInstruction p2 = paymentService.submit(
                feeCommand("FEE-ROUND-2", "10.00", "4", LedgerService.ACCT_FEE));
        FeeSnapshot s2 = p2.getFeeSnapshot();
        log.info("[判定依据] 原始手续费={} 到分手续费={} 净额={} 尾差={}",
                s2.rawFee().toPlainString(), s2.fee(), s2.netAmount(), s2.roundingDifference());
        assertEquals(0, s2.rawFee().compareTo(new BigDecimal("0.0040")));
        assertEquals(0, s2.fee().amount().compareTo(new BigDecimal("0.00")), "0.0040 舍去为 0.00");
        assertEquals(0, s2.netAmount().amount().compareTo(new BigDecimal("10.00")), "舍去时收款方拿全额");
        assertEquals(0, s2.roundingDifference().compareTo(new BigDecimal("-0.0040")),
                "尾差=0.00−0.0040=−0.0040（手续费舍去，收款方多拿 0.0040）");
    }

    @Test
    @DisplayName("记账前拒绝原因可区分：费率非法、费率超精度、净额不合理、费率账户缺失、账户不存在、账户与收付方相同")
    void rejectionsBeforeAnyPostingAreDistinguishable() {
        // 费率为负
        assertInvalid(feeCommand("F-BAD-1", "10.00", "-1", LedgerService.ACCT_FEE), "费率不能为负");
        // 费率达到 100%（净额必为 0）
        assertInvalid(feeCommand("F-BAD-2", "10.00", "10000", LedgerService.ACCT_FEE), "费率必须小于");
        // 费率 3 位小数：拒绝隐式舍入
        assertInvalid(feeCommand("F-BAD-3", "10.00", "30.123", LedgerService.ACCT_FEE), "真实精度超过");
        // 扣费后净额非正：费率虽 <10000bp 但小额本金 + 进位可导致净额为 0（1.00 × 9999bp = 0.9999 -> 1.00）
        assertInvalid(feeCommand("F-BAD-4", "1.00", "9999", LedgerService.ACCT_FEE), "扣费后净额不为正");
        // 只带费率不带账户
        assertInvalid(new PaymentCommand("F-BAD-5", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE,
                "10.00", "30", null), "必须同时提供");
        // 只带账户不带费率
        assertInvalid(new PaymentCommand("F-BAD-6", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE,
                "10.00", null, LedgerService.ACCT_FEE), "必须同时提供");
        // 手续费账户不存在
        assertInvalid(feeCommand("F-BAD-7", "10.00", "30", "9999"), "账户不存在");
        // 手续费账户与付款方相同
        assertInvalid(feeCommand("F-BAD-8", "10.00", "30", LedgerService.ACCT_PAYER), "必须与付款方");
        // 手续费账户与收款方相同
        assertInvalid(feeCommand("F-BAD-9", "10.00", "30", LedgerService.ACCT_PAYEE), "必须与付款方");

        assertEquals(0, paymentService.findAll().size(), "所有非法请求都不得产生指令");
        assertEquals(0, entryRepository.findAll().size(), "所有非法请求都不得产生分录");
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum(), "非法请求后余额仍守恒");
        for (Account a : accountRepository.findAll()) {
            assertEquals(0, a.getBalance().compareTo(BigDecimal.ZERO.setScale(2)),
                    "非法请求后账户 " + a.getCode() + " 余额必须仍为 0");
        }
    }

    @Test
    @DisplayName("费率快照锁定受理时刻：之后调整默认策略，历史支付的费率/手续费/净额/凭证均不变")
    void feeSnapshotLockedAtAcceptanceAndUnaffectedByLaterPolicyChange() {
        feePolicyService.updateDefault("30", LedgerService.ACCT_FEE);
        // 请求不带费率，回落默认 30bp：100.00 × 30bp = 0.30，净额 99.70
        PaymentInstruction first = paymentService.submit(new PaymentCommand(
                "FEE-LOCK-1", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "100.00"));
        assertEquals(0, new BigDecimal("0.30").compareTo(first.getFeeSnapshot().fee().amount()));
        assertEquals(0, new BigDecimal("99.70").compareTo(first.getFeeSnapshot().netAmount().amount()));
        assertEquals(0, new BigDecimal("30.00").compareTo(first.getFeeSnapshot().rate().basisPoints()));

        // 调整策略：费率 60bp，手续费账户换一个新开的收入账户
        ledgerService.createAccount("4002", com.github.h3yow.paymentledgerreconciliationjava.domain.AccountType.REVENUE,
                "第二手续费收入", BigDecimal.ZERO);
        feePolicyService.updateDefault("60", "4002");

        // 回查历史支付：快照与分录都必须保持受理当时口径
        PaymentInstruction reloaded = paymentService.getByBusinessId("FEE-LOCK-1");
        assertEquals(0, new BigDecimal("30.00").compareTo(reloaded.getFeeSnapshot().rate().basisPoints()),
                "历史费率不得被后续调整改变");
        assertEquals(0, new BigDecimal("0.30").compareTo(reloaded.getFeeSnapshot().fee().amount()));
        assertEquals(0, new BigDecimal("99.70").compareTo(reloaded.getFeeSnapshot().netAmount().amount()));
        assertEquals(LedgerService.ACCT_FEE, reloaded.getFeeSnapshot().feeAccountCode());

        var entries = entryRepository.findByVoucherNo("FEE-LOCK-1");
        assertEquals(3, entries.size());
        BigDecimal feeAcctCredit = entries.stream()
                .filter(e -> e.accountCode().equals(LedgerService.ACCT_FEE))
                .map(LedgerEntry::credit).findFirst().orElseThrow();
        assertEquals(0, feeAcctCredit.compareTo(new BigDecimal("0.30")), "历史凭证手续费行不变");
        assertEquals(0, accountRepository.getByCode("4002").getBalance().compareTo(BigDecimal.ZERO.setScale(2)),
                "新账户不得被历史支付影响");

        // 新支付则按新策略：100.00 × 60bp = 0.60，落到 4002
        PaymentInstruction second = paymentService.submit(new PaymentCommand(
                "FEE-LOCK-2", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "100.00"));
        assertEquals(0, new BigDecimal("60.00").compareTo(second.getFeeSnapshot().rate().basisPoints()));
        assertEquals(0, new BigDecimal("0.60").compareTo(second.getFeeSnapshot().fee().amount()));
        assertEquals("4002", second.getFeeSnapshot().feeAccountCode());
        log.info("[判定依据] 策略调整后：历史 {} 仍为 30bp/0.30，新单 {} 为 60bp/0.60",
                first.getBusinessId(), second.getBusinessId());
    }

    @Test
    @DisplayName("每笔支付实际费率、手续费、净额随支付保留，可按 businessId 单独查出")
    void effectiveRateAndNetPersistedAndQueryable() {
        paymentService.submit(feeCommand("FEE-Q-1", "88.88", "12.5", LedgerService.ACCT_FEE));
        // 88.88 × 12.5bp /10000 = 0.1111 -> HALF_UP 0.11；净额 88.77
        PaymentInstruction queried = paymentService.getByBusinessId("FEE-Q-1");
        assertNotNull(queried.getFeeSnapshot(), "手续费快照必须随支付保留");
        assertEquals(0, new BigDecimal("12.50").compareTo(queried.getFeeSnapshot().rate().basisPoints()));
        assertEquals(0, new BigDecimal("0.11").compareTo(queried.getFee().amount()));
        assertEquals(0, new BigDecimal("88.77").compareTo(queried.getNetAmount().amount()));
        assertEquals(LedgerService.ACCT_FEE, queried.getFeeSnapshot().feeAccountCode());
        log.info("[判定依据] 单独查出 {}: 费率={} 原始费={} 手续费={} 净额={}",
                queried.getBusinessId(), queried.getFeeSnapshot().rate(),
                queried.getFeeSnapshot().rawFee().toPlainString(),
                queried.getFee(), queried.getNetAmount());
    }

    @Test
    @DisplayName("无手续费支付不受策略存在影响；0bp 显式费率产生零手续费快照")
    void zeroFeeRateAndNoPolicyBehavior() {
        feePolicyService.updateDefault("30", LedgerService.ACCT_FEE);
        // 显式 0bp：仍记录快照，凭证三行（手续费行金额为 0 会被账本拒绝——故 0 手续费走两行口径）
        // 说明：FeeSnapshot 允许 fee=0；为避免“0 金额分录行”，0 手续费与无手续费同口径处理
        PaymentInstruction zero = paymentService.submit(
                feeCommand("FEE-ZERO", "10.00", "0", LedgerService.ACCT_FEE));
        assertEquals(PaymentStatus.POSTED, zero.getStatus());
        assertNotNull(zero.getFeeSnapshot());
        assertEquals(0, zero.getFee().amount().compareTo(BigDecimal.ZERO.setScale(2)));
        assertEquals(0, new BigDecimal("10.00").compareTo(zero.getNetAmount().amount()));
        assertEquals(2, entryRepository.findByVoucherNo("FEE-ZERO").size(), "零手续费按两行凭证过账");
        assertTrue(entryRepository.findByVoucherNo("FEE-ZERO").stream()
                .noneMatch(e -> e.accountCode().equals(LedgerService.ACCT_FEE)), "零费用不动手续费账户");
        log.info("[判定依据] 0bp 支付：快照保留费率0，凭证两行，手续费账户无发生额");
    }

    private void assertInvalid(PaymentCommand command, String reasonFragment) {
        InvalidParameterException ex = assertThrows(InvalidParameterException.class,
                () -> paymentService.submit(command));
        assertTrue(ex.getMessage().contains(reasonFragment),
                () -> "拒绝原因应包含 '" + reasonFragment + "'，实际: " + ex.getMessage());
        log.info("[可区分拒绝] => {}", ex.getMessage());
    }
}
