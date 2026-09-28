package com.github.h3yow.paymentledgerreconciliationjava.web;

import com.github.h3yow.paymentledgerreconciliationjava.service.FeeConfiguration;
import com.github.h3yow.paymentledgerreconciliationjava.service.InvalidParameterException;
import com.github.h3yow.paymentledgerreconciliationjava.service.LedgerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地手续费费率配置接口：可随时查询与修改；修改只影响之后受理的支付，
 * 已受理支付的费率快照与对账结论不变。
 */
@RestController
@RequestMapping("/api/fees")
public class FeeController {

    private final FeeConfiguration feeConfiguration;
    private final LedgerService ledgerService;

    public FeeController(FeeConfiguration feeConfiguration, LedgerService ledgerService) {
        this.feeConfiguration = feeConfiguration;
        this.ledgerService = ledgerService;
    }

    @GetMapping("/config")
    public FeeConfiguration.Snapshot get() {
        return feeConfiguration.snapshot();
    }

    @PutMapping("/config")
    public FeeConfiguration.Snapshot update(@RequestBody FeeConfigCommand command) {
        if (command == null) {
            throw new InvalidParameterException("配置请求体不能为空");
        }
        boolean enabled = Boolean.TRUE.equals(command.enabled());
        String feeAccountNo = (command.feeAccountNo() == null || command.feeAccountNo().isBlank())
                ? feeConfiguration.snapshot().feeAccountNo() : command.feeAccountNo().trim();
        // 账户不存在在受理之前即可被拒绝（INVALID_PARAMETER，原因可区分）
        ledgerService.requireAccount(feeAccountNo);
        return feeConfiguration.update(enabled, command.feeRateBp(), feeAccountNo);
    }
}
