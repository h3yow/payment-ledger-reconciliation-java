package com.github.h3yow.paymentledgerreconciliationjava.service;

import com.github.h3yow.paymentledgerreconciliationjava.domain.ChannelSettlementRecord;

import java.util.List;

/**
 * 对账命令（服务层输入）。
 *
 * @param batchId             批次号，必填；相同批次号重复提交直接返回历史结论
 * @param channelRecords      渠道结算数据（本地模拟）
 * @param scopeBusinessIds    本地核对范围（渠道缺单的检测口径）；为空表示以全部本地流水为范围
 */
public record ReconciliationCommand(
        String batchId,
        List<ChannelSettlementRecord> channelRecords,
        List<String> scopeBusinessIds
) {
}
