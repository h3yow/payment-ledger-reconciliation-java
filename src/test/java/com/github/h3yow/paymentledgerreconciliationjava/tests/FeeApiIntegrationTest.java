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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 端到端：带手续费净额结算受理、费率/净额随支付可查、
 * 净额与手续费差异分别识别、默认费率策略可在线调整、超精度金额被拒。
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
    @DisplayName("HTTP：带手续费支付受理 -> 快照可查 -> 三账户守恒 -> 手续费/净额对账分类 -> 策略可调")
    void feePaymentEndToEnd() throws Exception {
        // 1) 带手续费受理：100.00，60bp，手续费账户 4001 => fee 0.60 net 99.40
        String body = objectMapper.writeValueAsString(Map.of(
                "businessId", "API-FEE-001",
                "payerAccountNo", "1001",
                "payeeAccountNo", "2001",
                "amountText", "100.00",
                "feeRateText", "60",
                "feeAccountNo", "4001"));
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.feeSnapshot.fee.amount").value(0.60))
                .andExpect(jsonPath("$.feeSnapshot.netAmount.amount").value(99.40))
                .andExpect(jsonPath("$.feeSnapshot.rate").value(60.00))
                .andExpect(jsonPath("$.feeSnapshot.rawFee").value(0.6000));

        // 2) 单独查出该笔的实际费率与净额
        String got = mockMvc.perform(get("/api/payments/API-FEE-001"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(got);
        assertEquals(0, node.get("fee").get("amount").decimalValue().compareTo(new BigDecimal("0.60")));
        assertEquals(0, node.get("netAmount").get("amount").decimalValue().compareTo(new BigDecimal("99.40")));
        log.info("[HTTP 快照] {}", got);

        // 3) 余额守恒（新增 4001 收入账户后合计仍为 0）
        String accounts = mockMvc.perform(get("/api/ledger/accounts"))
                .andReturn().getResponse().getContentAsString();
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode a : objectMapper.readTree(accounts)) {
            sum = sum.add(a.get("balance").decimalValue());
        }
        assertEquals(0, sum.compareTo(BigDecimal.ZERO), "全部账户余额合计仍为 0");

        // 4) 对账：渠道手续费对不上 => FEE_MISMATCH（依据含双方值）
        String channelBadFee = objectMapper.writeValueAsString(Map.of(
                "batchId", "API-FEE-BATCH-1",
                "channelRecords", List.of(Map.of(
                        "businessId", "API-FEE-001", "amount", 100.00, "status", "SUCCESS",
                        "channelFee", 0.90, "channelNetAmount", 99.10,
                        "settledAt", Instant.now().toString())),
                "scopeBusinessIds", List.of("API-FEE-001")));
        mockMvc.perform(post("/api/reconciliations").contentType(MediaType.APPLICATION_JSON)
                        .content(channelBadFee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].differenceType").value("FEE_MISMATCH"));

        // 5) 对账：三项一致 => MATCHED
        String channelOk = objectMapper.writeValueAsString(Map.of(
                "batchId", "API-FEE-BATCH-2",
                "channelRecords", List.of(Map.of(
                        "businessId", "API-FEE-001", "amount", 100.00, "status", "SUCCESS",
                        "channelFee", 0.60, "channelNetAmount", 99.40,
                        "settledAt", Instant.now().toString())),
                "scopeBusinessIds", List.of("API-FEE-001")));
        mockMvc.perform(post("/api/reconciliations").contentType(MediaType.APPLICATION_JSON)
                        .content(channelOk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].differenceType").value("MATCHED"));

        // 6) 老口径渠道单（无费用字段）与该本地支付：总额一致 => MATCHED，历史行为不变
        String legacy = objectMapper.writeValueAsString(Map.of(
                "batchId", "API-FEE-BATCH-3",
                "channelRecords", List.of(Map.of(
                        "businessId", "API-FEE-001", "amount", 100.00, "status", "SUCCESS",
                        "settledAt", Instant.now().toString())),
                "scopeBusinessIds", List.of("API-FEE-001")));
        mockMvc.perform(post("/api/reconciliations").contentType(MediaType.APPLICATION_JSON)
                        .content(legacy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].differenceType").value("MATCHED"));
    }

    @Test
    @DisplayName("HTTP：默认费率策略可查/改/清，且只影响之后受理的支付")
    void feePolicyAdjustableAtRuntime() throws Exception {
        // 初始未配置
        mockMvc.perform(get("/api/fee-policy")).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));

        // 配置 30bp -> 4001
        mockMvc.perform(put("/api/fee-policy").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "feeRateText", "30", "feeAccountNo", "4001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.defaultRateBp").value(30.00));

        // 不带费率的支付回落默认策略
        String body = objectMapper.writeValueAsString(Map.of(
                "businessId", "API-FEE-POLICY-1",
                "payerAccountNo", "1001", "payeeAccountNo", "2001", "amountText", "100.00"));
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.feeSnapshot.fee.amount").value(0.30));

        // 非法费率 => 400
        mockMvc.perform(put("/api/fee-policy").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "feeRateText", "10000", "feeAccountNo", "4001"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        // 清空
        mockMvc.perform(delete("/api/fee-policy")).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    @DisplayName("HTTP：超精度金额(受理/对账)与非法费率均 400 INVALID_PARAMETER，原因可区分")
    void excessivePrecisionAndBadRateRejectedOverHttp() throws Exception {
        // 受理金额 3 位小数
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "businessId", "API-FEE-BAD-1", "payerAccountNo", "1001",
                                "payeeAccountNo", "2001", "amountText", "10.005"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        // 非法费率
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "businessId", "API-FEE-BAD-2", "payerAccountNo", "1001",
                                "payeeAccountNo", "2001", "amountText", "10.00",
                                "feeRateText", "60.999", "feeAccountNo", "4001"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        // 对账渠道净额 3 位小数
        String rec = objectMapper.writeValueAsString(Map.of(
                "batchId", "API-FEE-BAD-BATCH",
                "channelRecords", List.of(Map.of(
                        "businessId", "X", "amount", 10.00, "status", "SUCCESS",
                        "channelFee", 0.01, "channelNetAmount", 9.999,
                        "settledAt", Instant.now().toString())),
                "scopeBusinessIds", List.of()));
        mockMvc.perform(post("/api/reconciliations").contentType(MediaType.APPLICATION_JSON).content(rec))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
}
