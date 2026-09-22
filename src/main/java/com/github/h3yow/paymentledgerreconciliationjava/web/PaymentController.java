package com.github.h3yow.paymentledgerreconciliationjava.web;

import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelSettlementRecord;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelStatus;
import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerAccount;
import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerEntry;
import com.github.h3yow.paymentledgerreconciliationjava.domain.Money;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentOrder;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationBatch;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationResult;
import com.github.h3yow.paymentledgerreconciliationjava.web.dto.AccountView;
import com.github.h3yow.paymentledgerreconciliationjava.web.dto.BatchView;
import com.github.h3yow.paymentledgerreconciliationjava.web.dto.ChannelRecordData;
import com.github.h3yow.paymentledgerreconciliationjava.web.dto.FailRequest;
import com.github.h3yow.paymentledgerreconciliationjava.web.dto.OrderView;
import com.github.h3yow.paymentledgerreconciliationjava.web.dto.ReconcileRequest;
import com.github.h3yow.paymentledgerreconciliationjava.web.dto.ResultView;
import com.github.h3yow.paymentledgerreconciliationjava.web.dto.SubmitRequest;
import com.github.h3yow.paymentledgerreconciliationjava.exception.InvalidParameterException;
import com.github.h3yow.paymentledgerreconciliationjava.service.LedgerService;
import com.github.h3yow.paymentledgerreconciliationjava.service.PaymentService;
import com.github.h3yow.paymentledgerreconciliationjava.service.ReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地 HTTP 入口：仅用于手工验证，不接入任何外部服务。
 */
@RestController
@RequestMapping("/local")
public class PaymentController {

    private final PaymentService paymentService;
    private final ReconciliationService reconciliationService;
    private final LedgerService ledgerService;

    public PaymentController(PaymentService paymentService,
                             ReconciliationService reconciliationService,
                             LedgerService ledgerService) {
        this.paymentService = paymentService;
        this.reconciliationService = reconciliationService;
        this.ledgerService = ledgerService;
    }

    /** 受理支付指令（幂等键 bizId）。 */
    @PostMapping("/payments")
    public OrderView submit(@RequestBody SubmitRequest request) {
        return toView(paymentService.submit(request.bizId(), request.amount()));
    }

    @GetMapping("/payments/{bizId}")
    public OrderView get(@PathVariable String bizId) {
        return toView(paymentService.get(bizId));
    }

    /** 本地模拟渠道确认结算。 */
    @PostMapping("/payments/{bizId}/success")
    public OrderView markSuccess(@PathVariable String bizId) {
        return toView(paymentService.markSuccess(bizId));
    }

    /** 本地模拟渠道返回失败。 */
    @PostMapping("/payments/{bizId}/failed")
    public OrderView markFailed(@PathVariable String bizId, @RequestBody(required = false) FailRequest request) {
        String reason = request == null ? "LOCAL_UNKNOWN" : request.reason();
        return toView(paymentService.markFailed(bizId, reason));
    }

    /** 用提交体携带的本地模拟渠道数据执行对账。 */
    @PostMapping("/reconciliations")
    public BatchView reconcile(@RequestBody ReconcileRequest request) {
        List<ChannelSettlementRecord> records = new ArrayList<>();
        if (request.records() != null) {
            for (ChannelRecordData d : request.records()) {
                records.add(toRecord(d));
            }
        }
        return toBatchView(reconciliationService.reconcile(request.batchId(), records));
    }

    @GetMapping("/reconciliations/{batchId}")
    public BatchView getBatch(@PathVariable String batchId) {
        return toBatchView(reconciliationService.getBatch(batchId));
    }

    /** 账本账户余额一览（守恒人工核验用）。 */
    @GetMapping("/ledger/accounts")
    public List<AccountView> accounts() {
        List<AccountView> views = new ArrayList<>();
        for (LedgerAccount a : ledgerService.accounts()) {
            views.add(new AccountView(a.getAccountCode(), a.getAccountName(),
                    a.getDebitBalance().toString(), a.getCreditBalance().toString(),
                    a.signedBalance().toString(), a.getVersion()));
        }
        return views;
    }

    /** 全部分录流水（可回溯）。 */
    @GetMapping("/ledger/entries")
    public List<LedgerEntry> entries() {
        return ledgerService.allEntries();
    }

    private static ChannelSettlementRecord toRecord(ChannelRecordData d) {
        if (d == null || d.bizId() == null || d.bizId().isBlank()) {
            throw new InvalidParameterException("渠道记录或其 bizId 为空");
        }
        ChannelSettlementRecord r = new ChannelSettlementRecord();
        r.setBizId(d.bizId());
        r.setAmount(Money.of(d.amount()));
        try {
            r.setStatus(ChannelStatus.valueOf(d.status()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidParameterException(
                    "渠道状态非法(仅支持 SETTLED/NOT_SETTLED): " + d.status());
        }
        r.setSettleBatchId(d.settleBatchId());
        return r;
    }

    private static OrderView toView(PaymentOrder o) {
        return new OrderView(o.getBizId(), o.getAmount().toString(), o.getStatus().name(),
                o.getFailReason(), o.getCreatedAt(), o.getUpdatedAt());
    }

    private static BatchView toBatchView(ReconciliationBatch batch) {
        List<ResultView> views = new ArrayList<>();
        for (ReconciliationResult r : batch.getResults()) {
            views.add(new ResultView(
                    r.getBizId(), r.getType().name(), r.getReason(),
                    r.getLocalStatus(), r.getLocalAmount() == null ? null : r.getLocalAmount().toString(),
                    r.getChannelStatus(), r.getChannelAmount() == null ? null : r.getChannelAmount().toString()));
        }
        return new BatchView(batch.getBatchId(), batch.getCreatedAt(), views.size(), views);
    }
}
