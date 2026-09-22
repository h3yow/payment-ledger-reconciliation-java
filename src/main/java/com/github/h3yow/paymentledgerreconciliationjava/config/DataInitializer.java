package com.github.h3yow.paymentledgerreconciliationjava.config;

import com.github.h3yow.paymentledgerreconciliationjava.repository.LedgerAccountRepository;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 启动时初始化本地标准账户（全部数据仅在内存，无外部依赖）。
 *
 * <pre>
 * 科目（借贷方向）：
 *   1101 RECEIVABLE_CLEARING 应收渠道清算款（资产，正常借方余额）
 *   1102 SETTLED_FUNDS       已清算备付金    （资产，正常借方余额）
 *   2101 CUSTOMER_PENDING    待清算客户资金  （负债，正常贷方余额）
 *   2102 CUSTOMER_SETTLED    已清算客户资金  （负债，正常贷方余额）
 * </pre>
 *
 * 恒等关系（每笔过账后成立）：1101+1102 借方合计 == 2101+2102 贷方合计。
 */
@Configuration
public class DataInitializer {

    public static final String ACC_RECEIVABLE = "1101";
    public static final String ACC_SETTLED_FUNDS = "1102";
    public static final String ACC_CUSTOMER_PENDING = "2101";
    public static final String ACC_CUSTOMER_SETTLED = "2102";

    private final LedgerAccountRepository accountRepository;

    public DataInitializer(LedgerAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @PostConstruct
    public void init() {
        accountRepository.createIfAbsent(ACC_RECEIVABLE, "应收渠道清算款");
        accountRepository.createIfAbsent(ACC_SETTLED_FUNDS, "已清算备付金");
        accountRepository.createIfAbsent(ACC_CUSTOMER_PENDING, "待清算客户资金");
        accountRepository.createIfAbsent(ACC_CUSTOMER_SETTLED, "已清算客户资金");
    }
}
