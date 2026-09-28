package com.github.h3yow.paymentledgerreconciliationjava.web;

import com.github.h3yow.paymentledgerreconciliationjava.domain.Account;
import com.github.h3yow.paymentledgerreconciliationjava.domain.FeeSettlement;
import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerEntry;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentInstruction;
import com.github.h3yow.paymentledgerreconciliationjava.service.LedgerService;
import com.github.h3yow.paymentledgerreconciliationjava.service.PaymentCommand;
import com.github.h3yow.paymentledgerreconciliationjava.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 支付受理与账本查询接口（仅本地内存）。
 */
@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService paymentService;
    private final LedgerService ledgerService;

    public PaymentController(PaymentService paymentService, LedgerService ledgerService) {
        this.paymentService = paymentService;
        this.ledgerService = ledgerService;
    }

    /** 受理并过账一笔支付。重复提交/冲突/非法参数返回可区分错误码。 */
    @PostMapping("/payments")
    public PaymentInstruction submit(@RequestBody PaymentCommand command) {
        return paymentService.submit(command);
    }

    @GetMapping("/payments/{businessId}")
    public PaymentInstruction get(@PathVariable String businessId) {
        return paymentService.getByBusinessId(businessId);
    }

    /**
     * 单独查某笔支付实际生效的费率与手续费/净额/尾差快照。
     * 支付存在但不含手续费要素时返回 404（body 带可区分 code=FEE_SETTLEMENT_NOT_FOUND）。
     */
    @GetMapping("/payments/{businessId}/fee-settlement")
    public ResponseEntity<FeeSettlement> getFeeSettlement(@PathVariable String businessId) {
        FeeSettlement settlement = paymentService.getFeeSettlement(businessId);
        if (settlement == null) {
            throw new FeeSettlementNotFoundException(
                    "该支付不含手续费结算要素: " + businessId);
        }
        return ResponseEntity.ok(settlement);
    }

    @GetMapping("/payments")
    public List<PaymentInstruction> list() {
        return paymentService.findAll();
    }

    @GetMapping("/ledger/accounts")
    public List<Account> accounts() {
        return ledgerService.findAllAccounts();
    }

    @GetMapping("/ledger/entries")
    public List<LedgerEntry> entries() {
        return ledgerService.findAllEntries();
    }
}
