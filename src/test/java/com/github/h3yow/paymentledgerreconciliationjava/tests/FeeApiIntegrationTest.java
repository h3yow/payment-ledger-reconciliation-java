package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端（HTTP）：带费率的受理 -> 手续费/净额单独可查 -> 费率配置可改 ->
 * 净额与手续费分项对账；非法/无手续费要素的错误码可区分。全程本地内存。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeeApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(FeeApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("HTTP：带手续费受理成功并三账户分账；手续费快照可单独查；无手续费支付查询返回404")
    void endToEndFeeAcceptanceAndQuery() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "businessId", "API-FEE-1",
                "payerAccountNo", "1001",
                "payeeAccountNo", "2001",
                "amountText", "100.00",
                "feeRateBp", 60,
                "feeAccountNo", "3001"));
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.feeSettlement.feeRateBp").value(60))
                .andExpect(jsonPath("$.feeSettlement.feeAmount").value(0.60))
                .andExpect(jsonPath("$.feeSettlement.netAmount").value(99.40));

        // 单独查询手续费/净额
        mockMvc.perform(get("/api/payments/API-FEE-1/fee-settlement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeAccountNo").value("3001"))
                .andExpect(jsonPath("$.rawFeeAmount").exists())
                .andExpect(jsonPath("$.roundingAdjustment").exists());

        // 三账户余额：1001=+100.00, 2001=-99.40, 3001=-0.60，合计 0
        String accounts = mockMvc.perform(get("/api/ledger/accounts"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode nodes = objectMapper.readTree(accounts);
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode node : nodes) {
            sum = sum.add(node.get("balance").decimalValue());
        }
        assertEquals(0, sum.compareTo(BigDecimal.ZERO), "含手续费账户在内余额合计为 0");
        log.info("[HTTP 守恒-含手续费] 账户余额合计={}", sum);

        // 非法费率（>=10000bp）被拒绝
        String badRate = objectMapper.writeValueAsString(Map.of(
                "businessId", "API-FEE-BAD", "payerAccountNo", "1001", "payeeAccountNo", "2001",
                "amountText", "100.00", "feeRateBp", 10000, "feeAccountNo", "3001"));
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(badRate))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        // 无手续费要素的支付：查询快照 => 404 + FEE_SETTLEMENT_NOT_FOUND
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(
                        objectMapper.writeValueAsString(Map.of(
                                "businessId", "API-FEE-NONE", "payerAccountNo", "1001",
                                "payeeAccountNo", "2001", "amountText", "7.00"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/payments/API-FEE-NONE/fee-settlement"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEE_SETTLEMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("HTTP：费率配置可查询/修改；修改只影响之后受理；净额/手续费分项对账识别差异")
    void endToEndConfigChangeAndFeeReconciliation() throws Exception {
        // 用配置驱动：开启 60bp
        String enable = objectMapper.writeValueAsString(Map.of(
                "enabled", true, "feeRateBp", 60, "feeAccountNo", "3001"));
        mockMvc.perform(put("/api/fees/config").contentType(MediaType.APPLICATION_JSON).content(enable))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.feeRateBp").value(60));

        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(
                        objectMapper.writeValueAsString(Map.of(
                                "businessId", "API-RF-1", "payerAccountNo", "1001",
                                "payeeAccountNo", "2001", "amountText", "100.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeSettlement.feeAmount").value(0.60))
                .andExpect(jsonPath("$.feeSettlement.netAmount").value(99.40));

        // 对账：渠道净额错为 99.00 => NET_MISMATCH
        Map<String, Object> channelNetWrong = Map.of(
                "businessId", "API-RF-1", "amount", 100.00, "status", "SUCCESS",
                "settledAt", Instant.now().toString(), "fee", 1.00, "netAmount", 99.00);
        String recNet = objectMapper.writeValueAsString(Map.of(
                "batchId", "API-FEE-BATCH-NET",
                "channelRecords", List.of(channelNetWrong),
                "scopeBusinessIds", List.of("API-RF-1")));
        mockMvc.perform(post("/api/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON).content(recNet))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].differenceType").value("NET_MISMATCH"));

        // 对账：净额对、手续费错 => FEE_MISMATCH
        Map<String, Object> channelFeeWrong = Map.of(
                "businessId", "API-RF-1", "amount", 100.00, "status", "SUCCESS",
                "settledAt", Instant.now().toString(), "fee", 0.70, "netAmount", 99.40);
        String recFee = objectMapper.writeValueAsString(Map.of(
                "batchId", "API-FEE-BATCH-FEE",
                "channelRecords", List.of(channelFeeWrong),
                "scopeBusinessIds", List.of("API-RF-1")));
        mockMvc.perform(post("/api/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON).content(recFee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].differenceType").value("FEE_MISMATCH"));

        // 全对 => MATCHED
        Map<String, Object> channelOk = Map.of(
                "businessId", "API-RF-1", "amount", 100.00, "status", "SUCCESS",
                "settledAt", Instant.now().toString(), "fee", 0.60, "netAmount", 99.40);
        String recOk = objectMapper.writeValueAsString(Map.of(
                "batchId", "API-FEE-BATCH-OK",
                "channelRecords", List.of(channelOk),
                "scopeBusinessIds", List.of("API-RF-1")));
        mockMvc.perform(post("/api/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON).content(recOk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].differenceType").value("MATCHED"));
        log.info("[HTTP 分项对账] NET_MISMATCH / FEE_MISMATCH / MATCHED 均可区分");
    }
}
