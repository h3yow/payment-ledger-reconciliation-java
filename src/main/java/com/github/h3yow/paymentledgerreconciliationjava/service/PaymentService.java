package com.github.h3yow.paymentledgerreconciliationjava.service;

import com.github.h3yow.paymentledgerreconciliationjava.domain.FeeSettlement;
import com.github.h3yow.paymentledgerreconciliationjava.domain.Money;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentInstruction;
import com.github.h3yow.paymentledgerreconciliationjava.domain.PaymentStatus;
import com.github.h3yow.paymentledgerreconciliationjava.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 *       收付款账户存在且不同 —— 任一不满足抛 {@link InvalidParameterException}；
 *       带手续费时还要校验费率合法（万分比 [0,10000)）、手续费账户存在且与收付账户不同、
 *       扣费后净额为正 —— 这些校验全部发生在任何账务产生之前，且原因文案彼此可区分；</li>
 *   <li>幂等落指令：{@code businessId} 经原子 putIfAbsent 竞争，
 *       并发下只有一个线程成为新指令，其余全部走“已存在”分支；</li>
 *   <li>同一 businessId 的“受理 + 过账 + 终态落定”在按业务键分段的锁内串行完成，
 *       输家等待赢家走到终态后才得到拒绝结论，因此任何重复/冲突拒绝都基于
 *       POSTED/FAILED 终态判定，不会观察到 ACCEPTED 的瞬时中间态；</li>
 *   <li>已存在分支按内容区分：内容完全相同（含费率/手续费账户）→ {@link DuplicateRequestException}，
 *       内容不同 → {@link ConflictException}；二者错误原因可区分；</li>
 *   <li>赢家紧接着调用 {@link LedgerService#postVoucher} 过账：
 *       费率未生效时两行凭证；入账手续费为正时三行凭证（借付款方总额；贷收款方净额；贷手续费账户手续费）；
 *       费率生效但入账手续费为 0.00 时按两行凭证（借付款方总额；贷收款方净额=总额），
 *       不产生 0 金额分录行，但费率/0.00 手续费/净额快照仍随指令保留；
 *       成功标记 POSTED；失败（含中途故障）由账本保证余额全额冲正、无分录残留，
 *       指令标记 FAILED（终态），同一 businessId 不可再提，需换新业务键。</li>
 * </ol>
 *
 * <p>费率快照：每笔支付实际使用的费率、手续费账户、理论/入账手续费、尾差与净额
 * 以 {@link FeeSettlement} 固化在指令上；之后费率配置如何调整都不再影响该笔支付。
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final FeeConfiguration feeConfiguration;

    /** 按 businessId 分段的锁：同键串行（含过账全程），异键互不阻塞。 */
    private final ConcurrentMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    @Autowired
    public PaymentService(PaymentRepository paymentRepository, LedgerService ledgerService,
                          FeeConfiguration feeConfiguration) {
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.feeConfiguration = feeConfiguration;
    }

    /** 仅供未装配费率配置的旧式直接构造（测试/兼容）：默认配置关闭手续费。 */
    public PaymentService(PaymentRepository paymentRepository, LedgerService ledgerService) {
        this(paymentRepository, ledgerService, new FeeConfiguration());
    }

    /** 受理要素：校验后的账户、金额与手续费快照（无手续费时为 null）。 */
    private record AcceptedFee(String feeAccountNo, FeeSettlement settlement) {
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

        // 1.1 手续费要素解析与校验（同样在任何账务之前）
        AcceptedFee fee = resolveFee(command, amount, payer, payee);

        // 2. 同键串行：锁内完成幂等竞争与过账，保证拒绝方只观察到终态
        Object keyLock = keyLocks.computeIfAbsent(businessId, k -> new Object());
        synchronized (keyLock) {
            // 2.1 幂等竞争：原子 CAS 决定唯一有效指令
            PaymentInstruction instruction = new PaymentInstruction(
                    "PAY-" + UUID.randomUUID(), businessId, payer, payee, amount, Instant.now(),
                    fee == null ? null : fee.settlement());
            var existing = paymentRepository.insertIfAbsent(instruction);
            if (existing.isPresent()) {
                rejectDuplicate(existing.get(), command, payer, payee, amount, fee);
            }

            // 3. 赢家过账（凭证号复用 businessId，天然一一对应）
            // 费率生效但入账手续费为 0.00 时，账务等价于无手续费：只出两行凭证
            // （不产生 0 金额的手续费分录行），净额=总额；但费率快照仍挂在指令上留痕。
            List<LedgerService.VoucherLine> lines;
            if (fee == null) {
                lines = List.of(
                        new LedgerService.VoucherLine(payer, amount.amount(), BigDecimal.ZERO),
                        new LedgerService.VoucherLine(payee, BigDecimal.ZERO, amount.amount()));
            } else if (fee.settlement().feeAmount().signum() == 0) {
                lines = List.of(
                        new LedgerService.VoucherLine(payer, amount.amount(), BigDecimal.ZERO),
                        new LedgerService.VoucherLine(payee, BigDecimal.ZERO,
                                fee.settlement().netAmount()));
            } else {
                lines = List.of(
                        new LedgerService.VoucherLine(payer, amount.amount(), BigDecimal.ZERO),
                        new LedgerService.VoucherLine(payee, BigDecimal.ZERO,
                                fee.settlement().netAmount()),
                        new LedgerService.VoucherLine(fee.feeAccountNo(), BigDecimal.ZERO,
                                fee.settlement().feeAmount()));
            }
            try {
                ledgerService.postVoucher(businessId, lines, "支付过账 " + businessId);
                instruction.markPosted(Instant.now());
                log.info("支付受理并过账成功: businessId={}, amount={}, 费率={}bp, 手续费={}, 净额={}, {} -> {}({})",
                        businessId, amount,
                        fee == null ? "-" : fee.settlement().feeRateBp(),
                        fee == null ? "-" : fee.settlement().feeAmount().toPlainString(),
                        fee == null ? "-" : fee.settlement().netAmount().toPlainString(),
                        payer, payee, fee == null ? "-" : fee.feeAccountNo());
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
     * 解析本笔支付实际生效的手续费要素：请求显式费率优先，其次本地默认配置，都没有则无手续费。
     * 所有非法情形在此抛出 {@link InvalidParameterException}，且在任何账务之前。
     */
    private AcceptedFee resolveFee(PaymentCommand command, Money amount, String payer, String payee) {
        Integer requestRate = command.feeRateBp();
        String requestFeeAccount = command.feeAccountNo() == null
                || command.feeAccountNo().isBlank() ? null : command.feeAccountNo().trim();

        Integer effectiveRateBp;
        String feeAccountNo;
        if (requestRate != null) {
            effectiveRateBp = requestRate;
            feeAccountNo = requestFeeAccount != null
                    ? requestFeeAccount : feeConfiguration.snapshot().feeAccountNo();
        } else {
            if (requestFeeAccount != null) {
                throw new InvalidParameterException(
                        "指定了手续费收款账户但未提供费率，无法计算手续费");
            }
            FeeConfiguration.Snapshot config = feeConfiguration.snapshot();
            if (!config.enabled()) {
                return null;
            }
            effectiveRateBp = config.feeRateBp();
            feeAccountNo = config.feeAccountNo();
        }

        if (effectiveRateBp < 0 || effectiveRateBp >= FeeConfiguration.MAX_RATE_BP_EXCLUSIVE) {
            throw new InvalidParameterException(
                    "费率非法：万分比必须在 [0," + FeeConfiguration.MAX_RATE_BP_EXCLUSIVE
                            + ") 之间，实际=" + effectiveRateBp);
        }
        if (feeAccountNo == null || feeAccountNo.isBlank()) {
            throw new InvalidParameterException("手续费收款账户不能为空");
        }
        if (feeAccountNo.equals(payer) || feeAccountNo.equals(payee)) {
            throw new InvalidParameterException(
                    "手续费收款账户不能与付款方或收款方账户相同: " + feeAccountNo);
        }
        // 手续费账户存在性前置校验（不存在原因与其它非法项可区分）
        ledgerService.requireAccount(feeAccountNo);

        BigDecimal gross = amount.amount();
        // 万分比精确计算：gross * bp / 10000；理论手续费可能带分位以外的小数（尾差来源）
        BigDecimal rawFee = gross.multiply(BigDecimal.valueOf(effectiveRateBp))
                .divide(BigDecimal.valueOf(10_000L), java.math.MathContext.DECIMAL128)
                .stripTrailingZeros();
        // 固定分位规则：HALF_UP 处理到分；尾差 = 入账手续费 − 理论手续费，
        // 舍去时尾差为负、进位时为正、整除时为 0；尾差保留精确小数值，
        // 使“入账手续费 = 理论手续费 + 尾差”恒等式严格成立、每笔尾差（含舍去的负尾差）可解释
        BigDecimal feeAmount = Money.round2(rawFee);
        BigDecimal roundingAdjustment = feeAmount.subtract(rawFee);
        BigDecimal netAmount = gross.subtract(feeAmount).setScale(Money.SCALE);
        if (netAmount.signum() <= 0) {
            throw new InvalidParameterException(String.format(
                    "扣费后净额不合理（必须为正）: 总额=%s, 费率=%dbp, 理论手续费=%s, 净额=%s",
                    gross.toPlainString(), effectiveRateBp, rawFee.toPlainString(),
                    netAmount.toPlainString()));
        }
        // 只要费率要素实际生效（请求显式带费率，或本地默认配置启用），即使入账手续费被
        // HALF_UP 处理为 0.00（费率为 0，或费率极小、总额极小导致），也照样固化快照：
        // 实际费率、0.00 手续费、精确尾差与净额（=总额）随支付永久留痕，单独查询可查；
        // 账务侧则由调用方按净额=总额的两行凭证入账，不产生 0 金额分录行。
        // 理论手续费与尾差保留精确小数值（BigDecimal 精确），用于解释每一笔尾差去了哪里
        FeeSettlement settlement = new FeeSettlement(
                effectiveRateBp, feeAccountNo, feeAmount, rawFee, roundingAdjustment, netAmount);
        return new AcceptedFee(feeAccountNo, settlement);
    }

    /**
     * 已存在 businessId 时的可区分拒绝：
     * 内容一致（含费率/手续费账户）=> 重复请求；任一字段不同 => 冲突。
     */
    private void rejectDuplicate(PaymentInstruction prior, PaymentCommand command,
                                 String payer, String payee, Money amount, AcceptedFee fee) {
        Integer effectiveRateBp = fee == null ? null : fee.settlement().feeRateBp();
        String feeAccountNo = fee == null ? null : fee.feeAccountNo();
        Integer priorRateBp = prior.getFeeSettlement() == null
                ? null : prior.getFeeSettlement().feeRateBp();
        String priorFeeAccount = prior.getFeeSettlement() == null
                ? null : prior.getFeeSettlement().feeAccountNo();
        boolean sameContent = Objects.equals(prior.getPayerAccountNo(), payer)
                && Objects.equals(prior.getPayeeAccountNo(), payee)
                && prior.getAmount().equals(amount)
                && Objects.equals(priorRateBp, effectiveRateBp)
                && Objects.equals(priorFeeAccount, feeAccountNo);
        String state = prior.getStatus().name();
        if (!sameContent) {
            throw new ConflictException(String.format(
                    "businessId=%s 已存在但请求内容不同（已有: %s->%s %s, 费率=%s bps, 手续费账户=%s, 状态=%s），拒绝状态冲突",
                    prior.getBusinessId(), prior.getPayerAccountNo(), prior.getPayeeAccountNo(),
                    prior.getAmount(), priorRateBp, priorFeeAccount, state));
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

    /**
     * 单独查某笔支付的手续费结算快照（实际费率/手续费/净额/尾差）。
     * 只要费率要素实际生效就有快照——即使入账手续费为 0.00（费率为 0 或极小费率被舍入为 0），
     * 仍能查到实际费率、0.00 手续费与等于总额的净额；只有费率从未生效（无手续费要素）时才为 null。
     */
    public FeeSettlement getFeeSettlement(String businessId) {
        return getByBusinessId(businessId).getFeeSettlement();
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
