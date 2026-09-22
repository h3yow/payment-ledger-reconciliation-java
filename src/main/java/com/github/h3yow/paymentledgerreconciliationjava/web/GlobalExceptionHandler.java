package com.github.h3yow.paymentledgerreconciliationjava.web;

import com.github.h3yow.paymentledgerreconciliationjava.exception.AccountNotFoundException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.DuplicateRequestException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.InvalidParameterException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.PaymentException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.PostingException;
import com.github.h3yow.paymentledgerreconciliationjava.exception.StateConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一错误响应：业务错误码（可区分）+ 明确原因 + HTTP 语义。
 *
 * <pre>
 *  INVALID_PARAMETER  400 参数非法（空值/格式/精度/借贷不平衡/账户不存在 等）
 *  DUPLICATE_REQUEST  409 重复或并发重复提交（bizId/postingId/batchId 已存在）
 *  STATE_CONFLICT     409 状态冲突（状态机非法流转、批次重复处理）
 *  POSTING_FAILED     500 过账中途失败（已保证不留半成品）
 *  ACCOUNT_NOT_FOUND  400 账户不存在
 * </pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<Map<String, Object>> handleInvalid(InvalidParameterException ex) {
        return error(ex, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleAccount(AccountNotFoundException ex) {
        return error(ex, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateRequestException ex) {
        return error(ex, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(StateConflictException.class)
    public ResponseEntity<Map<String, Object>> handleState(StateConflictException ex) {
        return error(ex, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(PostingException.class)
    public ResponseEntity<Map<String, Object>> handlePosting(PostingException ex) {
        return error(ex, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(PaymentException ex) {
        return error(ex, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<Map<String, Object>> error(PaymentException ex, HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of(
                "code", ex.getCode(),
                "message", ex.getMessage() == null ? "" : ex.getMessage(),
                "httpStatus", status.value()
        ));
    }
}
