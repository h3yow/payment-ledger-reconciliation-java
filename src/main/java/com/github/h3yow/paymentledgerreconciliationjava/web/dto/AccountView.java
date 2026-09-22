package com.github.h3yow.paymentledgerreconciliationjava.web.dto;

/** 账户余额视图。 */
public record AccountView(String accountCode, String accountName,
                         String debitBalance, String creditBalance, String signedBalance,
                         long version) {
}
