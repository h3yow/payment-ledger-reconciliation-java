package com.github.h3yow.paymentledgerreconciliationjava.service;

import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelSettlementRecord;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelStatus;
import com.github.h3yow.paymentledgerreconciliationjava.domain.DifferenceType;
import com.github.h3yow.paymentledgerreconciliationjava.domain.Money;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentOrder;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentStatus;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationBatch;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationResult;
import com.github.h3yow.paymentledgerreconciliationjava.exception.InvalidParameterException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.StateConflictException;
import com.github.h3yow.paymentledgerreconciliationjava.repository.PaymentOrderRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.ReconciliationResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 对账服务：渠道结算数据（本地模拟）与本地支付单逐条核对。
 *
 * <p><b>对账口径</b>：
 * <ol>
 *   <li>键：bizId。两侧 bizId 的并集，每条 bizId 恰好产生一条结论，不遗漏、不重复处理；</li>
 *   <li>状态映射：本地 SUCCESS 应对应渠道 SETTLED；本地 PENDING/FAILED 应对应 NOT_SETTLED；</li>
 *   <li>分类优先级（互斥、可区分，差异不笼统合并）：
 *     <ul>
 *       <li>渠道有、本地无 → {@code LOCAL_MISSING}（本地缺失）</li>
 *       <li>本地有、渠道无 → {@code CHANNEL_MISSING}（渠道缺失）</li>
 *       <li>两侧都有但状态口径不一致 → {@code STATUS_MISMATCH}</li>
 *       <li>状态一致但金额不一致 → {@code AMOUNT_MISMATCH}</li>
 *       <li>状态、金额都一致 → {@code MATCHED}</li>
 *     </ul>
 *   </li>
 *   <li>批次幂等：同一 batchId 重复发起直接 {@code STATE_CONFLICT} 拒绝；
 *       历史结论 append-only 保存，用同一 batchId 查回的结果与首次完全一致，可复现。</li>
 * </ol>
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaymentOrderRepository orderRepository;
    private final ReconciliationResultRepository resultRepository;

    public ReconciliationService(PaymentOrderRepository orderRepository,
                                 ReconciliationResultRepository resultRepository) {
        this.orderRepository = orderRepository;
        this.resultRepository = resultRepository;
    }

    /**
     * 执行对账。
     *
     * @param batchId        对账批次号（幂等键）
     * @param channelRecords 本次拿到的渠道结算数据全集（本地模拟，快照入参）
     */
    public ReconciliationBatch reconcile(String batchId,
                                         Collection<ChannelSettlementRecord> channelRecords) {
        if (batchId == null || batchId.isBlank()) {
            throw new InvalidParameterException("对账批次号不能为空");
        }
        if (channelRecords == null) {
            throw new InvalidParameterException("渠道结算数据集不能为 null（空集请传空列表）");
        }
        if (resultRepository.existsBatch(batchId)) {
            throw new StateConflictException("对账批次已处理，拒绝重复对账: " + batchId
                    + "（历史结论可通过查询接口复现）");
        }

        // 1) 入参规范化 + 校验：渠道数据集内 bizId 不允许重复（防止同一差异被重复处理）
        Map<String, ChannelSettlementRecord> channelMap = new HashMap<>();
        for (ChannelSettlementRecord r : channelRecords) {
            if (r == null || r.getBizId() == null || r.getBizId().isBlank()) {
                throw new InvalidParameterException("渠道记录或其 bizId 为空，拒绝对账");
            }
            if (r.getAmount() == null) {
                throw new InvalidParameterException("渠道记录金额为空: bizId=" + r.getBizId());
            }
            if (r.getStatus() == null) {
                throw new InvalidParameterException("渠道记录结算状态为空: bizId=" + r.getBizId());
            }
            ChannelSettlementRecord duplicate = channelMap.put(r.getBizId(), r);
            if (duplicate != null) {
                throw new InvalidParameterException(
                        "渠道数据集内 bizId 重复，无法形成唯一结论: " + r.getBizId());
            }
        }

        // 2) 本地单全集 + bizId 并集（TreeSet 保证结论顺序稳定，历史可复现）
        // putIfAbsent 去重：ConcurrentHashMap 迭代期间若另一线程恰在替换 value（状态推进），
        // 弱一致迭代器可能同时观察到新旧两个快照，必须以 bizId 收敛为唯一一条，
        // 防止同一支付单被对账两次（差异被重复处理）。
        Map<String, PaymentOrder> localMap = new HashMap<>();
        Set<String> allIds = new TreeSet<>();
        for (PaymentOrder o : orderRepository.findAll()) {
            localMap.putIfAbsent(o.getBizId(), o);
            allIds.add(o.getBizId());
        }
        allIds.addAll(channelMap.keySet());

        long now = System.currentTimeMillis();
        ReconciliationBatch batch = new ReconciliationBatch();
        batch.setBatchId(batchId);
        batch.setCreatedAt(now);
        batch.setResults(new ArrayList<>());

        // 并发兜底：若批次号已被另一线程抢先处理，拒绝重复写入
        if (!resultRepository.insertBatchIfAbsent(batch)) {
            throw new StateConflictException("对账批次并发重复提交，本请求落败: " + batchId);
        }

        // 3) 逐条分类（appendResult 已将结果写入批次列表，禁止再手工 add，否则结论会重复一遍）
        Map<DifferenceType, Integer> counters = new HashMap<>();
        for (String bizId : allIds) {
            PaymentOrder local = localMap.get(bizId);
            ChannelSettlementRecord channel = channelMap.get(bizId);
            ReconciliationResult result = classify(batchId, bizId, local, channel, now);
            resultRepository.appendResult(result);
            counters.merge(result.getType(), 1, Integer::sum);
            log.info("[对账结论] batchId={} bizId={} type={} basis={}",
                    batchId, bizId, result.getType(), result.getReason());
        }

        log.info("[对账完成] batchId={} total={} 分类计数={}", batchId, allIds.size(), counters);
        return resultRepository.findBatch(batchId);
    }

    public ReconciliationBatch getBatch(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            throw new InvalidParameterException("对账批次号不能为空");
        }
        ReconciliationBatch batch = resultRepository.findBatch(batchId);
        if (batch == null) {
            throw new InvalidParameterException("对账批次不存在: " + batchId);
        }
        return batch;
    }

    private ReconciliationResult classify(String batchId, String bizId,
                                          PaymentOrder local, ChannelSettlementRecord channel,
                                          long now) {
        ReconciliationResult r = new ReconciliationResult();
        r.setReconcileBatchId(batchId);
        r.setBizId(bizId);
        r.setCreatedAt(now);
        if (local != null) {
            r.setLocalStatus(local.getStatus().name());
            r.setLocalAmount(local.getAmount());
        }
        if (channel != null) {
            r.setChannelStatus(channel.getStatus().name());
            r.setChannelAmount(channel.getAmount());
        }

        if (local == null) {
            r.setType(DifferenceType.LOCAL_MISSING);
            r.setReason("渠道存在结算数据但本地无支付单: 渠道金额=" + channel.getAmount()
                    + " 渠道状态=" + channel.getStatus());
            return r;
        }
        if (channel == null) {
            r.setType(DifferenceType.CHANNEL_MISSING);
            r.setReason("本地存在支付单但渠道无结算数据: 本地金额=" + local.getAmount()
                    + " 本地状态=" + local.getStatus());
            return r;
        }

        ChannelStatus expectedChannelStatus = mapLocalToChannel(local.getStatus());
        if (channel.getStatus() != expectedChannelStatus) {
            r.setType(DifferenceType.STATUS_MISMATCH);
            r.setReason("状态口径不一致: 本地=" + local.getStatus()
                    + " 应对应渠道=" + expectedChannelStatus
                    + " 实际渠道=" + channel.getStatus());
            return r;
        }
        if (!local.getAmount().equals(channel.getAmount())) {
            r.setType(DifferenceType.AMOUNT_MISMATCH);
            r.setReason("状态一致但金额不符: 本地=" + local.getAmount()
                    + " 渠道=" + channel.getAmount()
                    + " 差额(渠道-本地)=" + channel.getAmount().minus(local.getAmount()));
            return r;
        }
        r.setType(DifferenceType.MATCHED);
        r.setReason("金额与状态均一致: " + local.getAmount() + "/" + local.getStatus()
                + " ↔ " + channel.getAmount() + "/" + channel.getStatus());
        return r;
    }

    /** 本地状态→期望渠道结算状态的映射口径。 */
    static ChannelStatus mapLocalToChannel(PaymentStatus local) {
        return switch (local) {
            case SUCCESS -> ChannelStatus.SETTLED;
            case PENDING, FAILED -> ChannelStatus.NOT_SETTLED;
        };
    }
}
