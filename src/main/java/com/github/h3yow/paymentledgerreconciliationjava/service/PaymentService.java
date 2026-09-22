package com.github.h3yow.paymentledgerreconciliationjava.service;

import com.github.h3yow.paymentledgerreconciliationjava.config.DataInitializer;
import com.github.h3yow.paymentledgerreconciliationjava.domain.Money;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentOrder;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentStatus;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PostingLine;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PostingRequest;
import com.github.h3yow.paymentledgerreconciliationjava.exception.DuplicateRequestException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.InvalidParameterException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.PostingException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.StateConflictException;
import com.github.h3yow.paymentledgerreconciliationjava.repository.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 支付受理服务。
 *
 * <p><b>幂等与状态机口径</b>：
 * <ul>
 *   <li>bizId 是唯一业务标识：受理阶段用 insertIfAbsent 原子占坑，
 *       重复提交、并发提交都只有一笔产生有效结果，其余以 {@code DUPLICATE_REQUEST} 拒绝；</li>
 *   <li>同 bizId 重复但金额不同，仍判定为重复（身份冲突），原因里附金额差异，不覆盖既有单；</li>
 *   <li>合法流转 PENDING→SUCCESS / PENDING→FAILED，终态再推进以 {@code STATE_CONFLICT} 拒绝；</li>
 *   <li>状态推进与账本过账绑定：过账失败则状态不推进；已推进后过账失败则红字冲销过账并维持状态一致。
 *       任何异常路径都不会留下"状态变了但账没动"或"账动了但状态没变"的半成品。</li>
 * </ul>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentOrderRepository orderRepository;
    private final LedgerService ledgerService;

    public PaymentService(PaymentOrderRepository orderRepository, LedgerService ledgerService) {
        this.orderRepository = orderRepository;
        this.ledgerService = ledgerService;
    }

    /**
     * 受理支付指令（幂等）。
     */
    public PaymentOrder submit(String bizId, String amountRaw) {
        long t0 = System.currentTimeMillis();
        if (bizId == null || bizId.isBlank()) {
            throw new InvalidParameterException("业务标识 bizId 不能为空");
        }
        Money amount = Money.of(amountRaw);
        if (!amount.isGreaterThan(Money.zero())) {
            throw new InvalidParameterException("支付金额必须大于 0: " + amountRaw);
        }

        PaymentOrder existing = orderRepository.findByBizId(bizId);
        if (existing != null) {
            throw new DuplicateRequestException("bizId 已受理，拒绝重复提交: " + bizId
                    + "（既有金额=" + existing.getAmount()
                    + (existing.getAmount().equals(amount) ? "，金额一致" : "，金额不一致=" + amount)
                    + "，状态=" + existing.getStatus() + "）");
        }

        PaymentOrder order = new PaymentOrder();
        order.setBizId(bizId);
        order.setAmount(amount);
        order.setStatus(PaymentStatus.PENDING);
        order.setCreatedAt(System.currentTimeMillis());
        order.setUpdatedAt(order.getCreatedAt());

        if (!orderRepository.insertIfAbsent(order)) {
            PaymentOrder winner = orderRepository.findByBizId(bizId);
            throw new DuplicateRequestException("并发提交同一 bizId，本请求落败，拒绝重复入账: "
                    + bizId + "（获胜单状态=" + (winner == null ? "未知" : winner.getStatus()) + "）");
        }

        // 受理即挂账：借 应收渠道清算款 / 贷 待清算客户资金
        PostingRequest posting = new PostingRequest(postingIdOf(bizId, "ACCEPT"), bizId, List.of(
                new PostingLine(DataInitializer.ACC_RECEIVABLE, true, amount, "支付受理-应收清算款"),
                new PostingLine(DataInitializer.ACC_CUSTOMER_PENDING, false, amount, "支付受理-待清算客户资金")
        ));
        try {
            ledgerService.post(posting);
        } catch (RuntimeException ex) {
            // 占坑成功但挂账失败：本本地模型中订单状态可回退（无外部参与），保证状态与账本一致
            orderRepository.compareAndUpdateStatus(
                    bizId, PaymentStatus.PENDING, PaymentStatus.FAILED, "受理挂账失败: " + ex.getMessage());
            log.warn("[受理失败] bizId={} amount={} cause={}", bizId, amount, ex.getMessage());
            throw ex;
        }
        log.info("[受理成功] bizId={} amount={} costMs={}", bizId, amount, System.currentTimeMillis() - t0);
        return orderRepository.findByBizId(bizId);
    }

    /**
     * 推进为成功：渠道确认结算（本地模拟）。PENDING→SUCCESS，借 备付金/贷 应收，
     * 同时客户资金从待清算转已清算（合并为一笔过账，保证借贷一次平衡）。
     */
    public PaymentOrder markSuccess(String bizId) {
        PaymentOrder order = requireOrder(bizId);
        Money amount = order.getAmount();
        if (order.getStatus() != PaymentStatus.PENDING) {
            throw new StateConflictException("当前状态=" + order.getStatus()
                    + " 不允许置为 SUCCESS，仅 PENDING 可推进: bizId=" + bizId);
        }
        PostingRequest settlePosting = new PostingRequest(postingIdOf(bizId, "SETTLE"), bizId, List.of(
                new PostingLine(DataInitializer.ACC_SETTLED_FUNDS, true, amount, "渠道结算-备付金入账"),
                new PostingLine(DataInitializer.ACC_RECEIVABLE, false, amount, "渠道结算-应收清算款转出"),
                new PostingLine(DataInitializer.ACC_CUSTOMER_PENDING, true, amount, "客户资金待清算转出"),
                new PostingLine(DataInitializer.ACC_CUSTOMER_SETTLED, false, amount, "客户资金已清算")
        ));
        // 先过账再推进状态：过账失败，状态原样保留，调用方可重试
        ledgerService.post(settlePosting);
        if (!orderRepository.compareAndUpdateStatus(bizId, PaymentStatus.PENDING, PaymentStatus.SUCCESS, null)) {
            // 极小竞态兜底：状态被其他线程改走，则红字冲销这笔结算过账，维持一致
            ledgerService.post(reversalOf(settlePosting, postingIdOf(bizId, "SETTLE-REV")));
            PaymentOrder current = orderRepository.findByBizId(bizId);
            throw new StateConflictException("状态已被并发推进，结算过账已冲销: bizId=" + bizId
                    + " 当前状态=" + (current == null ? "未知" : current.getStatus()));
        }
        log.info("[结算成功] bizId={} amount={}", bizId, amount);
        return orderRepository.findByBizId(bizId);
    }

    /**
     * 推进为失败：PENDING→FAILED，红字冲销受理分录（借贷方向相反、金额相等）。
     */
    public PaymentOrder markFailed(String bizId, String reason) {
        PaymentOrder order = requireOrder(bizId);
        if (reason == null || reason.isBlank()) {
            throw new InvalidParameterException("失败原因不能为空");
        }
        if (order.getStatus() != PaymentStatus.PENDING) {
            throw new StateConflictException("当前状态=" + order.getStatus()
                    + " 不允许置为 FAILED，仅 PENDING 可推进: bizId=" + bizId);
        }
        Money amount = order.getAmount();
        PostingRequest reversal = new PostingRequest(postingIdOf(bizId, "FAIL-REV"), bizId, List.of(
                new PostingLine(DataInitializer.ACC_CUSTOMER_PENDING, true, amount, "失败冲销-待清算客户资金转出"),
                new PostingLine(DataInitializer.ACC_RECEIVABLE, false, amount, "失败冲销-应收清算款转出")
        ));
        ledgerService.post(reversal);
        if (!orderRepository.compareAndUpdateStatus(
                bizId, PaymentStatus.PENDING, PaymentStatus.FAILED, reason)) {
            ledgerService.post(reversalOf(reversal, postingIdOf(bizId, "FAIL-REV-REV")));
            PaymentOrder current = orderRepository.findByBizId(bizId);
            throw new StateConflictException("状态已被并发推进，失败冲销分录已反向撤销: bizId=" + bizId
                    + " 当前状态=" + (current == null ? "未知" : current.getStatus()));
        }
        log.info("[支付失败] bizId={} amount={} reason={}", bizId, amount, reason);
        return orderRepository.findByBizId(bizId);
    }

    public PaymentOrder get(String bizId) {
        return requireOrder(bizId);
    }

    private PaymentOrder requireOrder(String bizId) {
        if (bizId == null || bizId.isBlank()) {
            throw new InvalidParameterException("业务标识 bizId 不能为空");
        }
        PaymentOrder order = orderRepository.findByBizId(bizId);
        if (order == null) {
            throw new InvalidParameterException("支付单不存在: " + bizId);
        }
        return order;
    }

    private static String postingIdOf(String bizId, String stage) {
        return bizId + ":" + stage;
    }

    /** 构造红字冲销过账：全部借贷方向反转。 */
    private static PostingRequest reversalOf(PostingRequest original, String reversalPostingId) {
        List<PostingLine> reversed = original.lines().stream()
                .map(l -> new PostingLine(l.getAccountCode(), !l.isDebit(), l.getAmount(),
                        "冲销-" + l.getSummary()))
                .toList();
        return new PostingRequest(reversalPostingId, original.bizId(), reversed);
    }
}
