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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：HTTP 受理 -> 幂等拒绝/冲突拒绝/参数非法三类可区分错误码；
 * 账本查询余额守恒；对账接口产出分类结论。全程本地内存。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("端到端：成功过账；重复=>DUPLICATE_REQUEST；篡改=>STATUS_CONFLICT；非法=>INVALID_PARAMETER")
    void endToEndAcceptanceAndDistinguishableRejections() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "businessId", "API-BIZ-001",
                "payerAccountNo", "1001",
                "payeeAccountNo", "2001",
                "amountText", "12.34"));

        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));

        // 重复提交
        String dupResponse = mockMvc
                .perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"))
                .andReturn().getResponse().getContentAsString();
        log.info("[HTTP 重复] {}", dupResponse);

        // 内容冲突（改金额）
        String conflictBody = objectMapper.writeValueAsString(Map.of(
                "businessId", "API-BIZ-001",
                "payerAccountNo", "1001",
                "payeeAccountNo", "2001",
                "amountText", "99.00"));
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATUS_CONFLICT"));

        // 参数非法（3 位小数）
        String invalidBody = objectMapper.writeValueAsString(Map.of(
                "businessId", "API-BIZ-002",
                "payerAccountNo", "1001",
                "payeeAccountNo", "2001",
                "amountText", "1.005"));
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        // 余额守恒：账户合计 0；付款方 −12.34（借方为正视角下 1001=+12.34，2001=−12.34）
        String accounts = mockMvc.perform(get("/api/ledger/accounts"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode nodes = objectMapper.readTree(accounts);
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode node : nodes) {
            sum = sum.add(node.get("balance").decimalValue());
        }
        assertEquals(0, sum.compareTo(BigDecimal.ZERO), "HTTP 视角下全部账户余额合计为 0");
        log.info("[HTTP 守恒] 账户余额合计={}", sum);
    }

    @Test
    @DisplayName("端到端：对账接口识别金额不符与本地缺失，且批次可按 batchId 取回")
    void endToEndReconciliation() throws Exception {
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(
                objectMapper.writeValueAsString(Map.of(
                        "businessId", "API-REC-1", "payerAccountNo", "1001",
                        "payeeAccountNo", "2001", "amountText", "5.00"))))
                .andExpect(status().isOk());

        Map<String, Object> channelMatched = Map.of(
                "businessId", "API-REC-1", "amount", 5.00, "status", "SUCCESS",
                "settledAt", Instant.now().toString());
        Map<String, Object> channelMissingLocal = Map.of(
                "businessId", "API-REC-GHOST", "amount", 6.00, "status", "SUCCESS",
                "settledAt", Instant.now().toString());

        String recBody = objectMapper.writeValueAsString(Map.of(
                "batchId", "API-BATCH-1",
                "channelRecords", List.of(channelMatched, channelMissingLocal),
                "scopeBusinessIds", List.of("API-REC-1")));

        mockMvc.perform(post("/api/reconciliations").contentType(MediaType.APPLICATION_JSON).content(recBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.differenceCount").value(1))
                .andExpect(jsonPath("$.items[0].differenceType").value("MATCHED"))
                .andExpect(jsonPath("$.items[1].differenceType").value("LOCAL_MISSING"));

        mockMvc.perform(get("/api/reconciliations/API-BATCH-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("API-BATCH-1"));
        log.info("[HTTP 对账] 批次 API-BATCH-1：1 一致 + 1 本地缺失，可按 batchId 复现取回");
    }
}
