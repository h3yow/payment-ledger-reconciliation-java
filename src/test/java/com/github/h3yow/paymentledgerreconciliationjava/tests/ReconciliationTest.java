package com.github.h3yow.paymentledgerreconciliationjava.tests;

import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelSettlementRecord;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelStatus;
import com.github.h3yow.paymentledgerreconciliationjava.domain.DifferenceType;
import com.github.h3yow.paymentledgerreconciliationjava.domain.Money;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationBatch;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationResult;
import com.github.h3yow.paymentledgerreconciliationjava.exception.InvalidParameterException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.StateConflictException;
import com.github.h3yow.paymentledgerreconciliationjava.service.PaymentService;
import com.github.h3yow.paymentledgerreconciliationjava.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账测试：五类差异分类、批次幂等、历史结论可复现、输入重复防御。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationTest.class);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Test
    void allFiveDifferenceTypesAreClassifiedDistinctly() {
        // 本地侧：五条支付单，覆盖三种本地状态
        paymentService.submit("M-OK", "100.00");      // SUCCESS
        paymentService.markSuccess("M-OK");
        paymentService.submit("M-AMT", "100.00");     // SUCCESS，渠道金额 90.00
        paymentService.markSuccess("M-AMT");
        paymentService.submit("M-ST", "100.00");      // SUCCESS，渠道却未结算
        paymentService.markSuccess("M-ST");
        paymentService.submit("M-MISS", "50.00");     // SUCCESS，渠道无记录
        paymentService.markSuccess("M-MISS");
        paymentService.submit("M-FAIL", "30.00");     // FAILED，渠道也未结算 => MATCHED
        paymentService.markFailed("M-FAIL", "RISK");

        List<ChannelSettlementRecord> channel = List.of(
                channel("M-OK", "100.00", ChannelStatus.SETTLED),
                channel("M-AMT", "90.00", ChannelStatus.SETTLED),
                channel("M-ST", "100.00", ChannelStatus.NOT_SETTLED),
                channel("X-LOCAL-MISSING", "77.00", ChannelStatus.SETTLED),
                channel("M-FAIL", "30.00", ChannelStatus.NOT_SETTLED));

        log.info("[关键输入] 本地 5 单(M-OK/M-AMT/M-ST/M-MISS/M-FAIL) 渠道 5 条(多出 X-LOCAL-MISSING)");
        ReconciliationBatch batch = reconciliationService.reconcile("BATCH-20260923-01", channel);

        Map<String, ReconciliationResult> byBiz = batch.getResults().stream()
                .collect(Collectors.toMap(ReconciliationResult::getBizId, r -> r));
        for (ReconciliationResult r : batch.getResults()) {
            log.info("[判定依据] bizId={} type={} reason={}", r.getBizId(), r.getType(), r.getReason());
        }

        assertEquals(6, batch.getResults().size(), "两侧并集 6 个 bizId，每个恰好一条结论");
        assertEquals(DifferenceType.MATCHED, byBiz.get("M-OK").getType());
        assertEquals(DifferenceType.AMOUNT_MISMATCH, byBiz.get("M-AMT").getType());
        assertTrue(byBiz.get("M-AMT").getReason().contains("差额(渠道-本地)=-10.00"));
        assertEquals(DifferenceType.STATUS_MISMATCH, byBiz.get("M-ST").getType());
        assertTrue(byBiz.get("M-ST").getReason().contains("应对应渠道=SETTLED"));
        assertEquals(DifferenceType.CHANNEL_MISSING, byBiz.get("M-MISS").getType());
        assertEquals(DifferenceType.LOCAL_MISSING, byBiz.get("X-LOCAL-MISSING").getType());
        assertEquals(DifferenceType.MATCHED, byBiz.get("M-FAIL").getType(),
                "本地 FAILED ↔ 渠道 NOT_SETTLED 属口径一致");

        Map<DifferenceType, Long> counts = batch.getResults().stream()
                .collect(Collectors.groupingBy(ReconciliationResult::getType, Collectors.counting()));
        log.info("[分类计数] {}", counts);
        assertEquals(2L, counts.get(DifferenceType.MATCHED));
        assertEquals(1L, counts.get(DifferenceType.AMOUNT_MISMATCH));
        assertEquals(1L, counts.get(DifferenceType.STATUS_MISMATCH));
        assertEquals(1L, counts.get(DifferenceType.LOCAL_MISSING));
        assertEquals(1L, counts.get(DifferenceType.CHANNEL_MISSING));
    }

    @Test
    void batchIdIsIdempotentAndHistoryIsReproducible() {
        paymentService.submit("R-1", "10.00");
        paymentService.markSuccess("R-1");
        List<ChannelSettlementRecord> channel = List.of(channel("R-1", "10.00", ChannelStatus.SETTLED));

        ReconciliationBatch first = reconciliationService.reconcile("BATCH-DUP", channel);
        StateConflictException ex = assertThrows(StateConflictException.class,
                () -> reconciliationService.reconcile("BATCH-DUP", channel));
        log.info("[拒绝-批次重复] code={} message={}", ex.getCode(), ex.getMessage());
        assertTrue(ex.getMessage().contains("对账批次已处理"));

        ReconciliationBatch replayed = reconciliationService.getBatch("BATCH-DUP");
        log.info("[复现] 首次结果数={} 查询结果数={}", first.getResults().size(), replayed.getResults().size());
        assertEquals(first.getResults().size(), replayed.getResults().size());
        for (int i = 0; i < first.getResults().size(); i++) {
            ReconciliationResult a = first.getResults().get(i);
            ReconciliationResult b = replayed.getResults().get(i);
            assertEquals(a.getBizId(), b.getBizId());
            assertEquals(a.getType(), b.getType());
            assertEquals(a.getReason(), b.getReason(), "历史结论必须逐字可复现");
        }
    }

    @Test
    void duplicateBizIdInsideChannelDatasetIsRejected() {
        List<ChannelSettlementRecord> dup = List.of(
                channel("D-1", "1.00", ChannelStatus.SETTLED),
                channel("D-1", "2.00", ChannelStatus.NOT_SETTLED));
        InvalidParameterException ex = assertThrows(InvalidParameterException.class,
                () -> reconciliationService.reconcile("BATCH-BAD-INPUT", dup));
        log.info("[拒绝-渠道入参重复] {}", ex.getMessage());
        assertTrue(ex.getMessage().contains("bizId 重复"));
        // 被拒绝的批次不得留下任何痕迹
        InvalidParameterException notFound = assertThrows(InvalidParameterException.class,
                () -> reconciliationService.getBatch("BATCH-BAD-INPUT"));
        assertTrue(notFound.getMessage().contains("批次不存在"));
    }

    private static ChannelSettlementRecord channel(String bizId, String amount, ChannelStatus status) {
        ChannelSettlementRecord r = new ChannelSettlementRecord();
        r.setBizId(bizId);
        r.setAmount(Money.of(amount));
        r.setStatus(status);
        r.setSettleBatchId("SETTLE-LOCAL");
        return r;
    }
}
