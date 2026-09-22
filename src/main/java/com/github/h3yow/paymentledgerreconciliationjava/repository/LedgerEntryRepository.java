package com.github.h3yow.paymentledgerreconciliationjava.repository;

import com.github.h3yow.paymentledgerreconciliationjava.domain.LedgerEntry;

import java.util.List;

/** 账本分录仓储。同一凭证号只能写入一次（幂等防线）。 */
public interface LedgerEntryRepository {

    /** 凭证号未占用时写入全部行并返回 true；已占用返回 false。 */
    boolean saveVoucher(String voucherNo, List<LedgerEntry> entries);

    List<LedgerEntry> findByVoucherNo(String voucherNo);

    List<LedgerEntry> findAll();
}
