package com.github.h3yow.paymentledgerreconciliationjava.service;

import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelSettlementRecord;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelStatus;
import com.github.h3yow.paymentledgerreconciliationjava.domain.DifferenceType;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentInstruction;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationBatch;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ReconciliationResultItem;
import com.github.h3yow.paymentledgerreconciliationjava.repository.PaymentRepository;
import com.github.h3yow.paymentledgerreconciliationjava.repository.ReconciliationBatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 对账服务（纯本地：渠道数据由调用方以 {@link ChannelSettlementRecord} 列表传入，无任何外部调用）。
 *
 * <p>核对口径（对每个 businessId 恰好产出一条结论，五类互斥，差异不静默、不合并、不重复处理）：
 * <ul>
 *   <li>{@code LOCAL_MISSING}：渠道有单，本地流水不存在；</li>
 *   <li>{@code CHANNEL_MISSING}：在本地核对范围内、本地有流水，但渠道无单；</li>
 *   <li>{@code STATUS_MISMATCH}：双方都有，但成功/失败口径不一致
 *       （本地 POSTED 对应渠道 SUCCESS；本地 FAILED 对应渠道 FAILED；ACCEPTED 视为未完成，与任何渠道状态都不一致）；</li>
 *   <li>{@code AMOUNT_MISMATCH}：状态一致但金额不一致（统一规范到 2 位小数后精确比较）；</li>
 *   <li>{@code MATCHED}：状态、金额均一致。</li>
 * </ul>
 *
 * <p>批次口径：结果按 businessId 排序后落库，结论不可变；同一 batchId 携带相同输入重复执行，
 * 直接返回历史结论（历史可复现、不重复处理）；携带不同输入则以 STATUS_CONFLICT 拒绝。
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaymentRepository paymentRepository;
    private final ReconciliationBatchRepository batchRepository;

    public ReconciliationService(PaymentRepository paymentRepository,
                                 ReconciliationBatchRepository batchRepository) {
        this.paymentRepository = paymentRepository;
        this.batchRepository = batchRepository;
    }

    public ReconciliationBatch reconcile(ReconciliationCommand command) {
        validate(command);

        // 批次幂等：相同 batchId 先看历史结论
        Optional<ReconciliationBatch> stored = batchRepository.findByBatchId(command.batchId());
        String fingerprint = fingerprint(command);
        if (stored.isPresent()) {
            // 历史批次不可变；输入指纹必须一致，否则拒绝混用
            if (!fingerprint.equals(stored.get().basisForReuse())) {
                throw new ConflictException("batchId=" + command.batchId()
                        + " 已被不同对账数据占用，禁止覆盖历史结论");
            }
            log.info("对账批次复用历史结论（可复现、不重复处理）: batchId={}", command.batchId());
            return stored.get();
        }

        // 渠道数据按 businessId 去重：同一业务键出现多条本身就是数据问题，直接冲突拒绝，不静默取舍
        Map<String, ChannelSettlementRecord> channelMap = new LinkedHashMap<>();
        for (ChannelSettlementRecord record : command.channelRecords()) {
            if (channelMap.put(record.businessId(), record) != null) {
                throw new InvalidParameterException(
                        "渠道数据中 businessId 重复，拒绝对账: " + record.businessId());
            }
        }

        // 本地核对范围：显式给出则用之（CHANNEL_MISSING 的检测口径），否则取全部本地流水
        List<PaymentInstruction> scopeLocal = (command.scopeBusinessIds() == null
                || command.scopeBusinessIds().isEmpty())
                ? paymentRepository.findAll()
                : paymentRepository.findAllByBusinessIds(command.scopeBusinessIds());
        Map<String, PaymentInstruction> localMap = new LinkedHashMap<>();
        for (PaymentInstruction p : scopeLocal) {
            localMap.put(p.getBusinessId(), p);
        }

        // 并集 key，保证渠道独有与本地独有都不会被漏掉
        List<String> allKeys = new ArrayList<>();
        allKeys.addAll(channelMap.keySet());
        for (String key : localMap.keySet()) {
            if (!channelMap.containsKey(key)) {
                allKeys.add(key);
            }
        }
        allKeys.sort(Comparator.naturalOrder());

        List<ReconciliationResultItem> items = new ArrayList<>();
        for (String businessId : allKeys) {
            items.add(classify(businessId, channelMap.get(businessId), localMap.get(businessId)));
        }

        int matched = (int) items.stream().filter(i -> i.differenceType() == DifferenceType.MATCHED).count();
        ReconciliationBatch batch = new ReconciliationBatch(
                command.batchId(), java.time.Instant.now(), List.copyOf(items),
                items.size(), matched, items.size() - matched, fingerprint);

        // 并发下同一 batchId 只有一个批次落库；输家回读赢家结论（已校验指纹一致）
        if (!batchRepository.save(batch)) {
            ReconciliationBatch winner = batchRepository.findByBatchId(command.batchId()).orElseThrow();
            if (!fingerprint.equals(winner.basisForReuse())) {
                throw new ConflictException("batchId=" + command.batchId()
                        + " 已被不同对账数据占用，禁止覆盖历史结论");
            }
            log.info("对账批次并发提交，回读已落库结论: batchId={}", command.batchId());
            return winner;
        }

        log.info("对账完成: batchId={}, 总数={}, 一致={}, 差异={}（分类: 金额不符={}, 状态不一致={}, 本地缺失={}, 渠道缺失={}）",
                batch.batchId(), batch.totalCount(), batch.matchedCount(), batch.differenceCount(),
                countOf(items, DifferenceType.AMOUNT_MISMATCH),
                countOf(items, DifferenceType.STATUS_MISMATCH),
                countOf(items, DifferenceType.LOCAL_MISSING),
                countOf(items, DifferenceType.CHANNEL_MISSING));
        return batch;
    }

    private ReconciliationResultItem classify(String businessId,
                                              ChannelSettlementRecord channel,
                                              PaymentInstruction local) {
        if (channel == null) {
            // 本地在核对范围内，渠道没有
            String basis = "本地状态=" + local.getStatus() + " 金额=" + local.getAmount()
                    + "；渠道侧无该 businessId 记录";
            return new ReconciliationResultItem(businessId, DifferenceType.CHANNEL_MISSING,
                    "本地有流水、渠道缺单", basis);
        }
        if (local == null) {
            String basis = "渠道状态=" + channel.status() + " 金额=" + normalize(channel.amount())
                    + "；本地无该 businessId 流水";
            return new ReconciliationResultItem(businessId, DifferenceType.LOCAL_MISSING,
                    "渠道有单、本地缺失", basis);
        }

        boolean statusConsistent = expectedChannelStatus(local) == channel.status();
        if (!statusConsistent) {
            String basis = "本地状态=" + local.getStatus() + "（期望渠道 "
                    + expectedChannelStatus(local) + "），实际渠道=" + channel.status();
            return new ReconciliationResultItem(businessId, DifferenceType.STATUS_MISMATCH,
                    "状态不一致: 本地 " + local.getStatus() + " / 渠道 " + channel.status(), basis);
        }

        BigDecimal localAmount = local.getAmount().amount();
        BigDecimal channelAmount = normalize(channel.amount());
        if (localAmount.compareTo(channelAmount) != 0) {
            String basis = "状态一致(" + channel.status() + ")；金额 本地="
                    + localAmount.toPlainString() + " 渠道=" + channelAmount.toPlainString();
            return new ReconciliationResultItem(businessId, DifferenceType.AMOUNT_MISMATCH,
                    "金额不符: 本地 " + localAmount.toPlainString()
                            + " / 渠道 " + channelAmount.toPlainString(), basis);
        }

        String basis = "状态一致(" + channel.status() + ") 且金额一致("
                + localAmount.toPlainString() + ")";
        return new ReconciliationResultItem(businessId, DifferenceType.MATCHED, "一致", basis);
    }

    /** 本地 -> 渠道 的状态口径映射；ACCEPTED 映射为 null 表示“不应出现已结算渠道单”。 */
    private ChannelStatus expectedChannelStatus(PaymentInstruction local) {
        return switch (local.getStatus()) {
            case POSTED -> ChannelStatus.SUCCESS;
            case FAILED -> ChannelStatus.FAILED;
            case ACCEPTED -> null;
        };
    }

    private void validate(ReconciliationCommand command) {
        if (command == null) {
            throw new InvalidParameterException("对账命令不能为空");
        }
        if (command.batchId() == null || command.batchId().isBlank()) {
            throw new InvalidParameterException("batchId 不能为空");
        }
        if (command.channelRecords() == null) {
            throw new InvalidParameterException("渠道结算数据列表不能为 null（无数据请传空列表）");
        }
        for (ChannelSettlementRecord r : command.channelRecords()) {
            if (r == null || r.businessId() == null || r.businessId().isBlank()) {
                throw new InvalidParameterException("渠道结算单 businessId 不能为空");
            }
            if (r.amount() == null || normalize(r.amount()).signum() <= 0) {
                throw new InvalidParameterException("渠道结算单金额必须为正: " + r.businessId());
            }
            if (r.status() == null) {
                throw new InvalidParameterException("渠道结算单状态不能为空: " + r.businessId());
            }
        }
    }

    private BigDecimal normalize(BigDecimal amount) {
        return com.github.h3yow.paymentledgerreconciliationjava.domain.Money.round2(amount);
    }

    /** 输入指纹：渠道记录 + 范围，排序后拼接，保证同输入同指纹、不同输入可区分。 */
    private String fingerprint(ReconciliationCommand command) {
        StringBuilder sb = new StringBuilder("CH[");
        command.channelRecords().stream()
                .sorted(Comparator.comparing(ChannelSettlementRecord::businessId))
                .forEach(r -> sb.append(r.businessId()).append('=')
                        .append(normalize(r.amount()).toPlainString()).append('/')
                        .append(r.status()).append(';'));
        sb.append("]SCOPE[");
        if (command.scopeBusinessIds() != null) {
            command.scopeBusinessIds().stream().sorted().forEach(k -> sb.append(k).append(','));
        }
        return sb.append(']').toString();
    }

    private long countOf(List<ReconciliationResultItem> items, DifferenceType type) {
        return items.stream().filter(i -> i.differenceType() == type).count();
    }

    public List<ReconciliationBatch> findAllBatches() {
        return batchRepository.findAll();
    }
}
