package com.github.h3yow.paymentledgerreconciliationjava.web;

import com.github.h3yow.paymentledgerreconciliationjava.service.ConflictException;
import com.github.h3yow.paymentledgerreconciliationjava.service.DuplicateRequestException;
import com.github.h3yow.paymentledgerreconciliationjava.service.InvalidParameterException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常映射：三类拒绝原因对应三个可区分错误码与 HTTP 状态，
 * 响应体带 code/reason，保证“为什么被拒绝”可解释。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数非法：400 + INVALID_PARAMETER。 */
    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<Map<String, Object>> handleInvalid(InvalidParameterException ex) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", ex.getMessage(), null);
    }

    /** 重复请求：409 + DUPLICATE_REQUEST。 */
    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateRequestException ex) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", ex.getMessage(),
                Map.of("existingStatus", ex.getExistingStatus()));
    }

    /** 状态/内容冲突：409 + STATUS_CONFLICT。 */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException ex) {
        return build(HttpStatus.CONFLICT, "STATUS_CONFLICT", ex.getMessage(), null);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code,
                                                      String reason, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("code", code);
        body.put("reason", reason);
        if (extra != null) {
            body.putAll(extra);
        }
        return ResponseEntity.status(status).body(body);
    }
}
