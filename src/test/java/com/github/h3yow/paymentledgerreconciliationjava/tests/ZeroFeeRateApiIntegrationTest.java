package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.paymentledgerreconciliationjava.service.FeeConfiguration;
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
 * 业务：HTTP 端到端验证“带费率但手续费 0.00”的支付。
 * 用例：0 手续费快照 200 可查（费率/手续费/净额/负尾差）；相同重放 DUPLICATE、
 * 篡改费率（手续费同为 0.00）CONFLICT；账务两行、三账户守恒；无费率要素仍 404；
 * 渠道按 0.00/总额对账 MATCHED。全程本地内存。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ZeroFeeRateApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ZeroFeeRateApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FeeConfiguration feeConfiguration;

    @Test
    @DisplayName("HTTP：1bp 算出 0.00 手续费仍可查费率/净额/负尾差；重复 DUPLICATE、篡改 CONFLICT；无费 404")
    void zeroFeePaymentRetainedQueriedAndDistinguishedOverHttp() throws Exception {
        // 1.00 * 1bp = 0.0001 -> 0.00，净额 1.00，尾差 -0.0001
        String body = objectMapper.writeValueAsString(Map.of(
                "businessId", "API-ZERO-1",
                "payerAccountNo", "1001",
                "payeeAccountNo", "2001",
                "amountText", "1.00",
                "feeRateBp", 1,
                "feeAccountNo", "3001"));
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.feeSettlement.feeRateBp").value(1))
                .andExpect(jsonPath("$.feeSettlement.feeAmount").value(0.00))
                .andExpect(jsonPath("$.feeSettlement.netAmount").value(1.00));

        // 单独查询：不是 404，能查到费率、0.00 手续费、净额与负尾差
        String settlement = mockMvc.perform(get("/api/payments/API-ZERO-1/fee-settlement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeRateBp").value(1))
                .andExpect(jsonPath("$.feeAmount").value(0.00))
                .andExpect(jsonPath("$.netAmount").value(1.00))
                .andExpect(jsonPath("$.feeAccountNo").value("3001"))
                .andReturn().getResponse().getContentAsString();
        JsonNode settlementNode = objectMapper.readTree(settlement);
        assertEquals(0, settlementNode.get("roundingAdjustment").decimalValue()
                .compareTo(new BigDecimal("-0.0001")), "舍去尾差为 -0.0001");
        assertEquals(0, settlementNode.get("rawFeeAmount").decimalValue()
                .compareTo(new BigDecimal("0.0001")), "理论手续费 0.0001");

        // 完全相同重放 => 409 DUPLICATE_REQUEST
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));

        // 费率 1bp->2bp（两次手续费都是 0.00）=> 409 STATUS_CONFLICT，而非重复
        String tampered = objectMapper.writeValueAsString(Map.of(
                "businessId", "API-ZERO-1", "payerAccountNo", "1001", "payeeAccountNo", "2001",
                "amountText", "1.00", "feeRateBp", 2, "feeAccountNo", "3001"));
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(tampered))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATUS_CONFLICT"));

        // 账务：只有两行分录（无 3001 的 0 金额行），三账户合计仍为 0
        String entries = mockMvc.perform(get("/api/ledger/entries"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode entryNodes = objectMapper.readTree(entries);
        int feeLines = 0;
        for (JsonNode node : entryNodes) {
            if ("API-ZERO-1".equals(node.get("voucherNo").asText())
                    && "3001".equals(node.get("accountCode").asText())) {
                feeLines++;
            }
        }
        assertEquals(0, feeLines, "0 手续费不得产生手续费账户分录行");

        String accounts = mockMvc.perform(get("/api/ledger/accounts"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode node : objectMapper.readTree(accounts)) {
            sum = sum.add(node.get("balance").decimalValue());
        }
        assertEquals(0, sum.compareTo(BigDecimal.ZERO), "余额合计恒为 0");
        log.info("[HTTP 0手续费守恒] 无手续费账户分录行，账户余额合计={}", sum);

        // 真正无费率要素的支付：显式关闭可能被其它测试类开启的本地默认配置后提交，
        // 快照查询仍为 404（Spring 上下文跨测试类共享，配置为进程内状态）
        feeConfiguration.disable();
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(
                        objectMapper.writeValueAsString(Map.of(
                                "businessId", "API-ZERO-NONE", "payerAccountNo", "1001",
                                "payeeAccountNo", "2001", "amountText", "3.00"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/payments/API-ZERO-NONE/fee-settlement"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEE_SETTLEMENT_NOT_FOUND"));

        // 对账：渠道 fee=0.00/net=1.00 => MATCHED
        Map<String, Object> channel = Map.of(
                "businessId", "API-ZERO-1", "amount", 1.00, "status", "SUCCESS",
                "settledAt", Instant.now().toString(), "fee", 0.00, "netAmount", 1.00);
        String reconcile = objectMapper.writeValueAsString(Map.of(
                "batchId", "API-ZERO-BATCH-1",
                "channelRecords", List.of(channel),
                "scopeBusinessIds", List.of("API-ZERO-1")));
        mockMvc.perform(post("/api/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON).content(reconcile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].differenceType").value("MATCHED"));
        log.info("[HTTP 0手续费对账] 渠道 fee=0.00/net=1.00 => MATCHED");
    }
}
