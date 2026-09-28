package com.github.h3yow.paymentledgerreconciliationjava.web;

import com.github.h3yow.paymentledgerreconciliationjava.service.FeePolicyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地手续费默认策略配置接口（进程内、可在线调整、无外部依赖）。
 * 仅影响调整之后才受理的支付；已受理支付的费率快照不受影响。
 */
@RestController
@RequestMapping("/api/fee-policy")
public class FeeController {

    private final FeePolicyService feePolicyService;

    public FeeController(FeePolicyService feePolicyService) {
        this.feePolicyService = feePolicyService;
    }

    /** 查看当前默认费率策略（未配置时 configured=false）。 */
    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured",
                feePolicyService.getDefaultRate().isPresent()
                        && feePolicyService.getDefaultFeeAccount().isPresent());
        feePolicyService.getDefaultRate().ifPresent(r -> body.put("defaultRateBp", r.basisPoints()));
        feePolicyService.getDefaultFeeAccount().ifPresent(a -> body.put("defaultFeeAccount", a));
        return body;
    }

    /**
     * 更新默认策略。请求体：{@code {"feeRateText":"60","feeAccountNo":"4001"}}。
     * 费率非法/账户缺失返回 INVALID_PARAMETER。
     */
    @PutMapping
    public Map<String, Object> update(@RequestBody FeePolicyRequest request) {
        if (request == null) {
            throw new com.github.h3yow.paymentledgerreconciliationjava.service.InvalidParameterException(
                    "请求体不能为空");
        }
        feePolicyService.updateDefault(request.feeRateText(), request.feeAccountNo());
        return get();
    }

    /** 清空默认策略（之后受理的支付若不带费率即按无手续费处理）。 */
    @DeleteMapping
    public Map<String, Object> clear() {
        feePolicyService.clearDefault();
        return get();
    }

    /** 默认费率策略请求体。 */
    public record FeePolicyRequest(String feeRateText, String feeAccountNo) {
    }
}
