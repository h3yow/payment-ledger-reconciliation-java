package com.github.h3yow.paymentledgerreconciliationjava.domain;

import java.util.List;

/**
 * 一笔过账请求：postingId 为过账幂等键，lines 借贷合计必须相等。
 */
public record PostingRequest(String postingId, String bizId, List<PostingLine> lines) {
}
