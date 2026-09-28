package com.github.h3yow.paymentledgerreconciliationjava.service;

import com.github.h3yow.paymentledgerreconciliationjava.domain.FeeRate;
import com.github.h3yow.paymentledgerreconciliationjava.domain.FeeSnapshot;
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
import java.util.ArrayList;
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
 *   <li>带手续费的净额结算：请求可显式携带费率（万分比）与手续费收款账户；
 *       均未携带时回落到 {@link FeePolicyService} 的本地默认策略（可在线调整）；
 *       解析出的费率在受理瞬间复制进 {@link FeeSnapshot} 随支付锁定，
 *       之后费率调整不影响本笔的手续费、净额与历史对账结论；</li>
 *   <li>幂等落指令：{@code businessId} 经原子 putIfAbsent 竞争，
 *       并发下只有一个线程成为新指令，其余全部走“已存在”分支；</li>
 *   <li>同一 businessId 的“受理 + 过账 + 终态落定”在按业务键分段的锁内串行完成，
 *       输家等待赢家走到终态后才得到拒绝结论，因此任何重复/冲突拒绝都基于
 *       POSTED/FAILED 终态判定，不会观察到 ACCEPTED 的瞬时中间态；</li>
 *   <li>已存在分支按内容区分：内容完全相同（含解析后费率/手续费账户）→
 *       {@link DuplicateRequestException}（重复请求），内容不同 → {@link ConflictException}；</li>
 *   <li>赢家紧接着调用 {@link LedgerService#postVoucher} 过账：
 *       无手续费为两行凭证；带手续费为三行凭证（借付款方本金，
 *       贷收款方净额、贷手续费账户手续费，{@code 本金 = 净额 + 手续费} 到分精确平衡）。
 *       成功标记 POSTED；失败（含中途故障）由账本保证余额全额冲正、无分录残留，
 *       指令标记 FAILED（终态），同一 businessId 不可再提，需换新业务键。</li>
 * </ol>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final FeePolicyService feePolicyService;

    /** 按 businessId 分段的锁：同键串行（含过账全程），异键互不阻塞。 */
    private final ConcurrentMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    @Autowired
    public PaymentService(PaymentRepository paymentRepository, LedgerService ledgerService,
                          FeePolicyService feePolicyService) {
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.feePolicyService = feePolicyService;
    }

    /** 无费率策略的装配（测试/旧用法）：等价于默认策略从未配置，仅支持无手续费支付。 */
    public PaymentService(PaymentRepository paymentRepository, LedgerService ledgerService) {
        this(paymentRepository, ledgerService, new FeePolicyService());
    }

    /** 受理前解析出的费用口径：无手续费时 snapshot 为 null。 */
    private record ResolvedFee(FeeSnapshot snapshot) {
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

        // 1.1 手续费口径解析（请求携带优先，否则回落本地默认策略；都没有则为无手续费支付）
        ResolvedFee resolved = resolveFee(command, amount, payer, payee);

        // 2. 同键串行：锁内完成幂等竞争与过账，保证拒绝方只观察到终态
        Object keyLock = keyLocks.computeIfAbsent(businessId, k -> new Object());
        synchronized (keyLock) {
            // 2.1 幂等竞争：原子 CAS 决定唯一有效指令
            PaymentInstruction instruction = new PaymentInstruction(
                    "PAY-" + UUID.randomUUID(), businessId, payer, payee, amount,
                    Instant.now(), resolved.snapshot());
            var existing = paymentRepository.insertIfAbsent(instruction);
            if (existing.isPresent()) {
                rejectDuplicate(existing.get(), payer, payee, amount, resolved.snapshot());
            }

            // 3. 赢家过账（凭证号复用 businessId，天然一一对应）
            List<LedgerService.VoucherLine> lines = voucherLines(payer, payee, amount, resolved);
            try {
                ledgerService.postVoucher(businessId, lines, "支付过账 " + businessId);
                instruction.markPosted(Instant.now());
                if (resolved.snapshot() == null) {
                    log.info("支付受理并过账成功: businessId={}, amount={}, {} -> {}",
                            businessId, amount, payer, payee);
                } else {
                    FeeSnapshot s = resolved.snapshot();
                    log.info("支付受理并净额过账成功: businessId={}, 本金={}, 费率={}, 手续费={}->{},"
                                    + " 净额={}->{}, 原始手续费={}, 尾差={}",
                            businessId, amount, s.rate(), s.fee(), s.feeAccountCode(),
                            s.netAmount(), payee, s.rawFee().toPlainString(), s.roundingDifference());
                }
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
     * 解析手续费口径（记账前完成，任何不满足都在产生账务前拒绝）：
     * <ul>
     *   <li>费率与账户都携带：以请求为准；只带一个 => 参数非法（原因可区分）；</li>
     *   <li>都不携带：回落默认策略；默认策略也没有 => 无手续费支付（老口径）；</li>
     *   <li>费率精度/范围非法 => “费率非法”；扣费后净额非正 => “净额不合理”；
     *       手续费账户不存在/与收付款方相同 => “账户”类原因，三类互斥可区分。</li>
     * </ul>
     */
    private ResolvedFee resolveFee(PaymentCommand command, Money amount, String payer, String payee) {
        boolean hasRate = command.feeRateText() != null && !command.feeRateText().isBlank();
        boolean hasAccount = command.feeAccountNo() != null && !command.feeAccountNo().isBlank();
        if (hasRate ^ hasAccount) {
            throw new InvalidParameterException(hasRate
                    ? "携带手续费费率时必须同时提供手续费收款账户 feeAccountNo"
                    : "携带手续费收款账户时必须同时提供手续费费率 feeRateText");
        }

        FeeRate rate;
        String feeAccount;
        if (hasRate) {
            try {
                rate = FeeRate.of(command.feeRateText());
            } catch (IllegalArgumentException e) {
                throw new InvalidParameterException("费率非法: " + e.getMessage());
            }
            feeAccount = command.feeAccountNo().trim();
        } else {
            var defaultRate = feePolicyService.getDefaultRate();
            var defaultAccount = feePolicyService.getDefaultFeeAccount();
            if (defaultRate.isEmpty() && defaultAccount.isEmpty()) {
                return new ResolvedFee(null);
            }
            if (defaultRate.isEmpty() || defaultAccount.isEmpty()) {
                // 本地策略配置本身不完整（理论上 updateDefault 原子成对写入，不会发生）
                throw new InvalidParameterException("本地默认手续费策略不完整：费率与收款账户必须同时配置");
            }
            rate = defaultRate.get();
            feeAccount = defaultAccount.get();
        }

        // 手续费账户前置校验：必须存在，且与付款方、收款方都不同
        // （手续费与净额必须分别落到不同账户，不能和收付款方混用）
        ledgerService.requireAccount(feeAccount);
        if (feeAccount.equals(payer) || feeAccount.equals(payee)) {
            throw new InvalidParameterException(
                    "手续费收款账户必须与付款方、收款方均不同: " + feeAccount);
        }

        final FeeSnapshot snapshot;
        try {
            snapshot = FeeSnapshot.calculate(amount, rate, feeAccount);
        } catch (IllegalArgumentException e) {
            // 扣费后净额不为正等“净额不合理”场景，原因串与费率非法、账户非法可区分
            throw new InvalidParameterException("扣费净额非法: " + e.getMessage());
        }
        return new ResolvedFee(snapshot);
    }

    /**
     * 组装凭证行：无手续费两行；带手续费通常三行（本金=净额+手续费，到分精确平衡）。
     * 费率合法但手续费舍入后为 0 时（如 0bp 或极小费率被舍去），不产生 0 金额分录行
     * （账本不允许借贷同时为零的行），按两行过账；费率快照仍随支付保留以便回溯。
     */
    private List<LedgerService.VoucherLine> voucherLines(String payer, String payee,
                                                         Money amount, ResolvedFee resolved) {
        List<LedgerService.VoucherLine> lines = new ArrayList<>();
        lines.add(new LedgerService.VoucherLine(payer, amount.amount(), BigDecimal.ZERO));
        if (resolved.snapshot() == null) {
            lines.add(new LedgerService.VoucherLine(payee, BigDecimal.ZERO, amount.amount()));
        } else {
            FeeSnapshot s = resolved.snapshot();
            BigDecimal feeCents = s.fee().amount();
            if (feeCents.signum() == 0) {
                // 到分手续费为 0：净额即本金，两行即可平衡，费用账户无发生额
                lines.add(new LedgerService.VoucherLine(payee, BigDecimal.ZERO, amount.amount()));
            } else {
                lines.add(new LedgerService.VoucherLine(payee, BigDecimal.ZERO, s.netAmount().amount()));
                lines.add(new LedgerService.VoucherLine(
                        s.feeAccountCode(), BigDecimal.ZERO, feeCents));
            }
        }
        return List.copyOf(lines);
    }

    /**
     * 已存在 businessId 时的可区分拒绝：
     * 内容一致（含解析后费率、手续费账户）=> 重复请求；任一字段不同 => 冲突。
     */
    private void rejectDuplicate(PaymentInstruction prior, String payer, String payee,
                                 Money amount, FeeSnapshot feeSnapshot) {
        boolean sameContent = Objects.equals(prior.getPayerAccountNo(), payer)
                && Objects.equals(prior.getPayeeAccountNo(), payee)
                && prior.getAmount().equals(amount)
                && sameFee(prior.getFeeSnapshot(), feeSnapshot);
        String state = prior.getStatus().name();
        if (!sameContent) {
            throw new ConflictException(String.format(
                    "businessId=%s 已存在但请求内容不同（已有: %s->%s %s, 费用口径=%s, 状态=%s），拒绝状态冲突",
                    prior.getBusinessId(), prior.getPayerAccountNo(), prior.getPayeeAccountNo(),
                    prior.getAmount(), describeFee(prior.getFeeSnapshot()), state));
        }
        throw new DuplicateRequestException(String.format(
                "businessId=%s 的支付请求已受理（状态=%s），重复提交被拒绝",
                prior.getBusinessId(), state), state);
    }

    /** 比较两次受理解析出的费用口径是否完全一致（费率值、手续费账户）。 */
    private boolean sameFee(FeeSnapshot a, FeeSnapshot b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.rate().equals(b.rate())
                && Objects.equals(a.feeAccountCode(), b.feeAccountCode());
    }

    private String describeFee(FeeSnapshot s) {
        if (s == null) {
            return "无手续费";
        }
        return String.format("费率=%s 手续费=%s 净额=%s 手续费账户=%s",
                s.rate(), s.fee(), s.netAmount(), s.feeAccountCode());
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
