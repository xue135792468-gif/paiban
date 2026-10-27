package com.paiban.common.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        Map<String, Object> payload = errorPayload(ex.getStatus(), ex.getMessage());
        payload.putAll(ex.getDetails());
        return ResponseEntity.status(ex.getStatus()).body(payload);
    }

    @ExceptionHandler(ImportValidationException.class)
    public ResponseEntity<Map<String, Object>> handleImportValidation(ImportValidationException ex) {
        Map<String, Object> payload = errorPayload(HttpStatus.BAD_REQUEST, ex.getMessage());
        payload.put("errors", ex.getErrors());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(payload);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.put(error.getField(), error.getDefaultMessage());
        }
        Map<String, Object> payload = errorPayload(HttpStatus.BAD_REQUEST, "参数校验失败");
        payload.put("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(payload);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleDenied(AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, "没有权限执行该操作");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException ex) {
        return body(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Two writers based on the same configuration version: the loser of the real race
     * must not see a generic 500. Nothing of its transaction was written.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex) {
        return body(HttpStatus.CONFLICT,
                "约束配置刚被他人保存，本次修改未写入；请核对最新配置后再保存");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * Anything unexpected must be a 500 with a log line, never an error dispatch that
     * security turns into a misleading 401.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("未处理的服务端异常", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(errorPayload(status, message));
    }

    private Map<String, Object> errorPayload(HttpStatus status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status.value());
        payload.put("message", message);
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }
}
