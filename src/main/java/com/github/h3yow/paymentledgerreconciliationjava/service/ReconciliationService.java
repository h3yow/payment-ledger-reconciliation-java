package com.github.h3yow.paymentledgerreconciliationjava.service;

import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelSettlementRecord;
import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelStatus;
import com.github.h3yow.paymentledgerreconciliationjava.domain.DifferenceType;
import com.github.h3yow.paymentledgerreconciliationjava.domain.Money;
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
 * <p>核对口径（对每个 businessId 恰好产出一条结论，结论互斥，差异不静默、不合并、不重复处理）：
 * <ul>
 *   <li>{@code LOCAL_MISSING}：渠道有单，本地流水不存在；</li>
 *   <li>{@code CHANNEL_MISSING}：在本地核对范围内、本地有流水，但渠道无单；</li>
 *   <li>{@code STATUS_MISMATCH}：双方都有，但成功/失败口径不一致；</li>
 * </ul>
 * 双方都有且状态一致时，按固定优先级逐项比较，命中即出结论（依据里同时列出三项双方实际值）：
 * <ol>
 *   <li><b>手续费</b>（仅当渠道单携带手续费/净额信息时才比较，口径见下）：不一致 → {@code FEE_MISMATCH}；</li>
 *   <li><b>净额</b>：不一致 → {@code NET_MISMATCH}；</li>
 *   <li><b>总额（本金）</b>：不一致 → {@code AMOUNT_MISMATCH}（老五类结论之一，语义不变）；</li>
 *   <li>全部一致 → {@code MATCHED}。</li>
 * </ol>
 *
 * <p><b>费用信息兼容口径</b>：
 * <ul>
 *   <li>渠道单<b>未携带</b>手续费与净额（老请求）：完全按老口径只比状态与总额，行为与扩展前一致，
 *       不出 FEE/NET 类结论，历史批次不受影响；</li>
 *   <li>渠道单<b>携带</b>费用明细（手续费、净额必须成对出现）：三项分别精确比较。
 *       本地无手续费快照的老流水按“手续费 0.00、净额=本金”作为本地实际值参与比较，
 *       依据中会注明该口径，不会被静默当成一致；</li>
 *   <li>金额一律规范到 2 位小数后用 compareTo 精确比较：表示法差异（1.00 与 1.0）判一致，
 *       真实值差异（0.03 与 0.030...）绝不因“精度处理方式不同”被归成一致；
 *       超过 2 位小数的外部金额在校验阶段即以 {@code INVALID_PARAMETER} 拒绝（原因可区分），
 *       绝不隐式舍入后继续对账。</li>
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

        log.info("对账完成: batchId={}, 总数={}, 一致={}, 差异={}（分类: 净额不符={}, 手续费不符={},"
                        + " 金额不符={}, 状态不一致={}, 本地缺失={}, 渠道缺失={}）",
                batch.batchId(), batch.totalCount(), batch.matchedCount(), batch.differenceCount(),
                countOf(items, DifferenceType.NET_MISMATCH),
                countOf(items, DifferenceType.FEE_MISMATCH),
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
                    + describeLocalFee(local) + "；渠道侧无该 businessId 记录";
            return new ReconciliationResultItem(businessId, DifferenceType.CHANNEL_MISSING,
                    "本地有流水、渠道缺单", basis);
        }
        if (local == null) {
            String basis = "渠道状态=" + channel.status() + " 金额=" + normalize(channel.amount())
                    + describeChannelFee(channel) + "；本地无该 businessId 流水";
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
        BigDecimal localFee = local.getFee().amount();
        BigDecimal localNet = local.getNetAmount().amount();
        BigDecimal channelFee = channel.channelFee() == null ? null : normalize(channel.channelFee());
        BigDecimal channelNet = channel.channelNetAmount() == null ? null : normalize(channel.channelNetAmount());

        // 三项实际值对照口径（无论命中哪类结论都完整写出，保证可解释、可复现）
        String valuesBasis = "状态一致(" + channel.status() + ")；"
                + "总额 本地=" + localAmount.toPlainString() + " 渠道=" + channelAmount.toPlainString()
                + (channel.hasFeeDetail()
                        ? "；手续费 本地=" + localFee.toPlainString() + " 渠道=" + channelFee.toPlainString()
                        + (local.getFeeSnapshot() == null ? "（本地无手续费口径，按0比较）" : "")
                        + "；净额 本地=" + localNet.toPlainString() + " 渠道=" + channelNet.toPlainString()
                        + (local.getFeeSnapshot() == null ? "（本地无手续费口径，净额按本金比较）" : "")
                        : "；渠道单未携带手续费/净额信息，按老口径仅比较总额");

        if (channel.hasFeeDetail()) {
            // 固定优先级：先手续费、再净额、最后总额。命中即出，依据中仍保留三项双方实际值。
            if (localFee.compareTo(channelFee) != 0) {
                return new ReconciliationResultItem(businessId, DifferenceType.FEE_MISMATCH,
                        "手续费不符: 本地 " + localFee.toPlainString()
                                + " / 渠道 " + channelFee.toPlainString(), valuesBasis);
            }
            if (localNet.compareTo(channelNet) != 0) {
                return new ReconciliationResultItem(businessId, DifferenceType.NET_MISMATCH,
                        "净额不符: 本地 " + localNet.toPlainString()
                                + " / 渠道 " + channelNet.toPlainString(), valuesBasis);
            }
        }
        if (localAmount.compareTo(channelAmount) != 0) {
            return new ReconciliationResultItem(businessId, DifferenceType.AMOUNT_MISMATCH,
                    "金额不符: 本地 " + localAmount.toPlainString()
                            + " / 渠道 " + channelAmount.toPlainString(), valuesBasis);
        }

        return new ReconciliationResultItem(businessId, DifferenceType.MATCHED, "一致", valuesBasis);
    }

    private String describeLocalFee(PaymentInstruction local) {
        if (local.getFeeSnapshot() == null) {
            return "（无手续费口径）";
        }
        var s = local.getFeeSnapshot();
        return "（费率=" + s.rate() + " 手续费=" + s.fee() + " 净额=" + s.netAmount() + "）";
    }

    private String describeChannelFee(ChannelSettlementRecord channel) {
        if (!channel.hasFeeDetail()) {
            return "（未携带手续费/净额）";
        }
        return "（渠道手续费=" + normalize(channel.channelFee()).toPlainString()
                + " 渠道净额=" + normalize(channel.channelNetAmount()).toPlainString() + "）";
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
            BigDecimal amount = strictScale2(r.amount(), "渠道结算金额", r.businessId());
            if (amount == null || amount.signum() <= 0) {
                throw new InvalidParameterException("渠道结算单金额必须为正: " + r.businessId());
            }
            if (r.status() == null) {
                throw new InvalidParameterException("渠道结算单状态不能为空: " + r.businessId());
            }
            // 手续费/净额必须成对出现；只带一个无法构成完整费用口径，按参数非法拒绝
            if ((r.channelFee() == null) != (r.channelNetAmount() == null)) {
                throw new InvalidParameterException(
                        "渠道手续费与渠道净额必须成对提供（要么都给，要么都不给）: " + r.businessId());
            }
            if (r.hasFeeDetail()) {
                BigDecimal fee = strictScale2(r.channelFee(), "渠道手续费", r.businessId());
                BigDecimal net = strictScale2(r.channelNetAmount(), "渠道净额", r.businessId());
                if (fee.signum() < 0) {
                    throw new InvalidParameterException("渠道手续费不能为负: " + r.businessId());
                }
                if (net.signum() <= 0) {
                    throw new InvalidParameterException("渠道净额必须为正: " + r.businessId());
                }
                // 结算单自身必须满足“总额=手续费+净额”（到分精确），否则该单数据自相矛盾
                if (amount.compareTo(fee.add(net)) != 0) {
                    throw new InvalidParameterException(String.format(
                            "渠道结算单不满足 总额=手续费+净额: businessId=%s 总额=%s 手续费=%s 净额=%s",
                            r.businessId(), amount.toPlainString(), fee.toPlainString(), net.toPlainString()));
                }
            }
        }
    }

    /**
     * 外部金额精度契约：必须非空，且<b>真实精度</b>不超过 2 位小数；超过即拒绝（绝不隐式舍入）。
     * “真实精度”按去尾零后的实际值判断：{@code 9.970} 等价于 {@code 9.97}（仅写法不同，
     * 不含分位以下信息），接受并规范到分；{@code 9.971} 带分位以外小数，拒绝。
     * 通过则返回规范到 scale=2 的值用于后续精确比较。
     */
    private BigDecimal strictScale2(BigDecimal value, String field, String businessId) {
        if (value == null) {
            throw new InvalidParameterException(field + "不能为空: " + businessId);
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > Money.SCALE) {
            throw new InvalidParameterException(field + "精度超过 " + Money.SCALE
                    + " 位小数，拒绝隐式舍入: " + businessId + "=" + value.toPlainString());
        }
        return Money.round2(value);
    }

    private BigDecimal normalize(BigDecimal amount) {
        return Money.round2(amount);
    }

    /** 输入指纹：渠道记录（含手续费/净额，null 显式区分）+ 范围，排序后拼接。 */
    private String fingerprint(ReconciliationCommand command) {
        StringBuilder sb = new StringBuilder("CH[");
        command.channelRecords().stream()
                .sorted(Comparator.comparing(ChannelSettlementRecord::businessId))
                .forEach(r -> sb.append(r.businessId()).append('=')
                        .append(normalize(r.amount()).toPlainString()).append('/')
                        .append(r.status()).append('/')
                        .append(r.channelFee() == null ? "-" : normalize(r.channelFee()).toPlainString())
                        .append('/')
                        .append(r.channelNetAmount() == null ? "-" : normalize(r.channelNetAmount()).toPlainString())
                        .append(';'));
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
