package com.github.h3yow.paymentledgerreconciliationjava.service;

import com.github.h3yow.paymentledgerreconciliationjava.domain.Money;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentInstruction;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentStatus;
import com.github.h3yow.paymentledgerreconciliationjava.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 支付受理服务（本地内存，无外部依赖）。
 *
 * <p>受理与过账口径：
 * <ol>
 *   <li>参数全部校验通过后才落指令：必填校验、金额口径（正、最多 2 位小数）、
 *       收付款账户存在且不同 —— 任一不满足抛 {@link InvalidParameterException}；</li>
 *   <li>幂等落指令：{@code businessId} 经原子 putIfAbsent 竞争，
 *       并发下只有一个线程成为新指令，其余全部走“已存在”分支；</li>
 *   <li>同一 businessId 的“受理 + 过账 + 终态落定”在按业务键分段的锁内串行完成，
 *       输家等待赢家走到终态后才得到拒绝结论，因此任何重复/冲突拒绝都基于
 *       POSTED/FAILED 终态判定，不会观察到 ACCEPTED 的瞬时中间态；</li>
 *   <li>已存在分支按内容区分：内容完全相同 → {@link DuplicateRequestException}（重复请求），
 *       内容不同 → {@link ConflictException}（状态/内容冲突）；二者错误原因可区分；</li>
 *   <li>赢家紧接着调用 {@link LedgerService#postVoucher} 过账：
 *       成功标记 POSTED；失败（含中途故障）由账本保证余额全额冲正、无分录残留，
 *       指令标记 FAILED（终态），同一 businessId 不可再提，需换新业务键。</li>
 * </ol>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;

    /** 按 businessId 分段的锁：同键串行（含过账全程），异键互不阻塞。 */
    private final ConcurrentMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    public PaymentService(PaymentRepository paymentRepository, LedgerService ledgerService) {
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
    }

    public PaymentInstruction submit(PaymentCommand command) {
        // 1. 参数校验（不通过直接拒绝，不产生任何数据，也无需加锁）
        String businessId = requireText(command.businessId(), "businessId");
        String payer = requireText(command.payerAccountNo(), "payerAccountNo");
        String payee = requireText(command.payeeAccountNo(), "payeeAccountNo");
        Money amount = parsePositiveAmount(command.amountText());
        if (payer.equals(payee)) {
            throw new InvalidParameterException("收付款账户不能相同: " + payer);
        }
        // 账户存在性前置校验：避免给不存在的账户产生指令
        ledgerService.requireAccount(payer);
        ledgerService.requireAccount(payee);

        // 2. 同键串行：锁内完成幂等竞争与过账，保证拒绝方只观察到终态
        Object keyLock = keyLocks.computeIfAbsent(businessId, k -> new Object());
        synchronized (keyLock) {
            // 2.1 幂等竞争：原子 CAS 决定唯一有效指令
            PaymentInstruction instruction = new PaymentInstruction(
                    "PAY-" + UUID.randomUUID(), businessId, payer, payee, amount, Instant.now());
            var existing = paymentRepository.insertIfAbsent(instruction);
            if (existing.isPresent()) {
                rejectDuplicate(existing.get(), command, payer, payee, amount);
            }

            // 3. 赢家过账（凭证号复用 businessId，天然一一对应）
            try {
                ledgerService.postVoucher(
                        businessId,
                        List.of(
                                new LedgerService.VoucherLine(payer, amount.amount(), BigDecimal.ZERO),
                                new LedgerService.VoucherLine(payee, BigDecimal.ZERO, amount.amount())),
                        "支付过账 " + businessId);
                instruction.markPosted(Instant.now());
                log.info("支付受理并过账成功: businessId={}, amount={}, {} -> {}",
                        businessId, amount, payer, payee);
                return instruction;
            } catch (RuntimeException ex) {
                // 账本侧已保证：余额全额冲正、凭证未落库。这里只固化失败终态。
                instruction.markFailed(Instant.now(), ex.getMessage());
                log.warn("支付过账失败，指令标记 FAILED，账本无半成品: businessId={}, 原因={}",
                        businessId, ex.getMessage());
                throw ex;
            }
        }
    }

    /**
     * 已存在 businessId 时的可区分拒绝：
     * 内容一致 => 重复请求；任一字段不同 => 冲突。终态/非终态信息写入原因串便于排查。
     */
    private void rejectDuplicate(PaymentInstruction prior, PaymentCommand command,
                                 String payer, String payee, Money amount) {
        boolean sameContent = Objects.equals(prior.getPayerAccountNo(), payer)
                && Objects.equals(prior.getPayeeAccountNo(), payee)
                && prior.getAmount().equals(amount);
        String state = prior.getStatus().name();
        if (!sameContent) {
            throw new ConflictException(String.format(
                    "businessId=%s 已存在但请求内容不同（已有: %s->%s %s, 状态=%s），拒绝状态冲突",
                    prior.getBusinessId(), prior.getPayerAccountNo(), prior.getPayeeAccountNo(),
                    prior.getAmount(), state));
        }
        throw new DuplicateRequestException(String.format(
                "businessId=%s 的支付请求已受理（状态=%s），重复提交被拒绝",
                prior.getBusinessId(), state), state);
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidParameterException(field + " 不能为空");
        }
        return value.trim();
    }

    private Money parsePositiveAmount(String amountText) {
        Money money;
        try {
            money = Money.of(amountText);
        } catch (IllegalArgumentException e) {
            // 金额口径非法统一映射为参数非法拒绝，原因沿用原始解释
            throw new InvalidParameterException("金额非法: " + e.getMessage());
        }
        if (!money.isPositive()) {
            throw new InvalidParameterException("支付金额必须为正数: " + money);
        }
        return money;
    }

    public PaymentInstruction getByBusinessId(String businessId) {
        return paymentRepository.findByBusinessId(requireText(businessId, "businessId"))
                .orElseThrow(() -> new InvalidParameterException("支付指令不存在: " + businessId));
    }

    public List<PaymentInstruction> findAll() {
        return paymentRepository.findAll();
    }

    /** 测试/对账便捷判断。 */
    public boolean isPosted(String businessId) {
        return paymentRepository.findByBusinessId(businessId)
                .map(p -> p.getStatus() == PaymentStatus.POSTED)
                .orElse(false);
    }
}
