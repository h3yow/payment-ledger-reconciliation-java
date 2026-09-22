package com.github.h3yow.paymentledgerreconciliationjava.web;

import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationBatch;
import com.github.h3yow.paymentledgerreconciliationjava.service.ReconciliationCommand;
import com.github.h3yow.paymentledgerreconciliationjava.service.ReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 对账接口（渠道数据由请求体传入，全程本地处理）。 */
@RestController
@RequestMapping("/api/reconciliations")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /** 执行（或复用）一个对账批次。 */
    @PostMapping
    public ReconciliationBatch run(@RequestBody ReconciliationCommand command) {
        return reconciliationService.reconcile(command);
    }

    @GetMapping("/{batchId}")
    public ReconciliationBatch get(@PathVariable String batchId) {
        return reconciliationService.findAllBatches().stream()
                .filter(b -> b.batchId().equals(batchId))
                .findFirst()
                .orElse(null);
    }

    @GetMapping
    public List<ReconciliationBatch> list() {
        return reconciliationService.findAllBatches();
    }
}
