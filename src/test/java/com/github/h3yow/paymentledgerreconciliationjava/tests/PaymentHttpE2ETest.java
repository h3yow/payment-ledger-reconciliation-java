package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 端到端：受理→重复→结算→对账 全链路，验证错误码在 HTTP 层可区分、账本余额可查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentHttpE2ETest {

    private static final Logger log = LoggerFactory.getLogger(PaymentHttpE2ETest.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullFlowWithDistinguishableRejections() throws Exception {
        // 1) 受理成功
        MvcResult ok = mockMvc.perform(post("/local/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("bizId", "E2E-1", "amount", "100.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        log.info("[受理] {}", ok.getResponse().getContentAsString());

        // 2) 重复提交 => 409 DUPLICATE_REQUEST
        MvcResult dup = mockMvc.perform(post("/local/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("bizId", "E2E-1", "amount", "100.00"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"))
                .andReturn();
        JsonNode dupBody = mapper.readTree(dup.getResponse().getContentAsString());
        log.info("[重复] httpStatus={} code={} message={}",
                dupBody.get("httpStatus").asText(), dupBody.get("code").asText(), dupBody.get("message").asText());

        // 3) 参数非法 => 400 INVALID_PARAMETER
        MvcResult invalid = mockMvc.perform(post("/local/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("bizId", "E2E-2", "amount", "0.001"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andReturn();
        log.info("[非法] {}", invalid.getResponse().getContentAsString());

        // 4) 结算成功
        mockMvc.perform(post("/local/payments/E2E-1/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 5) 终态再推进 => 409 STATE_CONFLICT
        mockMvc.perform(post("/local/payments/E2E-1/success"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        // 6) 对账：渠道少给 1 元 => AMOUNT_MISMATCH
        String reconcileBody = """
                {
                  "batchId": "BATCH-E2E",
                  "records": [
                    {"bizId": "E2E-1", "amount": "99.00", "status": "SETTLED", "settleBatchId": "S1"}
                  ]
                }
                """;
        MvcResult rec = mockMvc.perform(post("/local/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON).content(reconcileBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].type").value("AMOUNT_MISMATCH"))
                .andReturn();
        log.info("[对账] {}", rec.getResponse().getContentAsString());

        // 7) 批次重复 => 409 STATE_CONFLICT
        mockMvc.perform(post("/local/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON).content(reconcileBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        // 8) 历史结论可复现
        mockMvc.perform(get("/local/reconciliations/BATCH-E2E"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].type").value("AMOUNT_MISMATCH"));

        // 9) 余额守恒人工核验：资产侧 100 在备付金、负债侧 100 在客户已清算
        MvcResult accounts = mockMvc.perform(get("/local/ledger/accounts"))
                .andExpect(status().isOk()).andReturn();
        log.info("[账本余额] {}", accounts.getResponse().getContentAsString());
        mockMvc.perform(get("/local/ledger/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6)); // 受理2 + 结算4
    }
}
