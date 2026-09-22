package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentStatus;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryAccountRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryLedgerEntryRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.InMemoryPaymentRepository;
import com.github.h3yow.paymentledgerreconciliationjava.service.ConflictException;
import com.github.h3yow.paymentledgerreconciliationjava.service.DuplicateRequestException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过账原子性：在“第一行余额已更新、第二行尚未更新”处注入异常，
 * 验证余额全额冲正、不留下半成品分录、指令终态 FAILED、全局余额守恒，
 * 且同一 businessId 之后不能再次过账。
 */
class LedgerAtomicPostingTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerAtomicPostingTest.class);

    private InMemoryAccountRepository accountRepository;
    private InMemoryLedgerEntryRepository entryRepository;
    private LedgerService ledgerService;
    private PaymentService paymentService;
    private InMemoryPaymentRepository paymentRepository;

    @BeforeEach
    void setUp() {
        accountRepository = new InMemoryAccountRepository();
        entryRepository = new InMemoryLedgerEntryRepository();
        paymentRepository = new InMemoryPaymentRepository();
        ledgerService = new LedgerService(accountRepository, entryRepository);
        paymentService = new PaymentService(paymentRepository, ledgerService);
    }

    @Test
    @DisplayName("过账中途失败：借方已入账后抛错 => 冲正后两账户余额归 0，凭证零残留，指令 FAILED")
    void failureMidPostingLeavesNoHalfDoneState() {
        String businessId = "BIZ-FAIL-001";
        String amount = "88.88";
        // 故障：只对本凭证触发一次
        ledgerService.setFailureHook(voucherNo -> {
            throw new IllegalStateException("模拟：余额更新到一半，数据库/网络中断");
        });
        log.info("[输入] 故障凭证 businessId={}, 金额={}, 注入点=第一行余额更新后", businessId, amount);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> paymentService.submit(new PaymentCommand(
                        businessId, LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, amount)));
        log.info("[判定依据] 过账抛出: {}", ex.getMessage());

        // 1) 账户余额：付款方借 88.88 必须被反向冲正为 0；收款方从未被改动，仍为 0
        BigDecimal payerBalance = accountRepository.getByCode(LedgerService.ACCT_PAYER).getBalance();
        BigDecimal payeeBalance = accountRepository.getByCode(LedgerService.ACCT_PAYEE).getBalance();
        log.info("[判定依据] 冲正后余额: 付款方={}, 收款方={}", payerBalance, payeeBalance);
        assertEquals(0, payerBalance.compareTo(BigDecimal.ZERO.setScale(2)), "付款方余额必须冲正为 0");
        assertEquals(0, payeeBalance.compareTo(BigDecimal.ZERO.setScale(2)), "收款方余额必须保持 0");
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum(), "全局余额守恒（合计为 0）");

        // 2) 分录：失败凭证不得有任何残留
        assertTrue(ledgerService.findEntriesByVoucherNo(businessId).isEmpty(), "失败凭证不得留下分录");
        assertEquals(0, ledgerService.findAllEntries().size(), "全库不得有任何分录");

        // 3) 指令：唯一存在且为 FAILED 终态，带失败原因
        var instruction = paymentService.getByBusinessId(businessId);
        assertEquals(PaymentStatus.FAILED, instruction.getStatus(), "指令必须为 FAILED 终态");
        log.info("[判定依据] 指令状态={}, 失败原因={}", instruction.getStatus(), instruction.getFailReason());

        // 4) 同一 businessId 不允许再成功：相同内容 => 重复请求（终态可解释）
        DuplicateRequestException dup = assertThrows(DuplicateRequestException.class,
                () -> paymentService.submit(new PaymentCommand(
                        businessId, LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, amount)));
        assertEquals("FAILED", dup.getExistingStatus());
        log.info("[判定依据] FAILED 后同键相同请求 => DUPLICATE_REQUEST(existingStatus=FAILED)");

        // 5) 换金额再来 => 内容冲突
        assertThrows(ConflictException.class, () -> paymentService.submit(new PaymentCommand(
                businessId, LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "1.00")));

        // 6) 新业务键在故障钩子移除后应能正常过账
        ledgerService.setFailureHook(null);
        paymentService.submit(new PaymentCommand(
                "BIZ-OK-001", LedgerService.ACCT_PAYER, LedgerService.ACCT_PAYEE, "11.12"));
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum(), "正常过账后仍守恒");
        assertEquals(2, ledgerService.findAllEntries().size(), "仅新凭证留下 2 行分录");
    }

    @Test
    @DisplayName("借贷不平衡的凭证必须被拒绝，且不改动任何余额")
    void unbalancedVoucherRejected() {
        assertThrows(Exception.class, () -> ledgerService.postVoucher(
                "BIZ-BAD-V",
                java.util.List.of(
                        new LedgerService.VoucherLine(LedgerService.ACCT_PAYER,
                                new BigDecimal("10.00"), BigDecimal.ZERO),
                        new LedgerService.VoucherLine(LedgerService.ACCT_PAYEE,
                                BigDecimal.ZERO, new BigDecimal("9.00"))),
                "不平衡凭证"));
        assertEquals(0, ledgerService.totalBalanceAcrossAccounts().signum());
        assertTrue(ledgerService.findEntriesByVoucherNo("BIZ-BAD-V").isEmpty());
        log.info("[判定依据] 借10.00≠贷9.00 的凭证被拒绝，无余额与分录副作用");
    }
}
