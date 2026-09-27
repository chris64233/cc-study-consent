package com.chris64233.cc.studyconsent.web;

import com.chris64233.cc.studyconsent.service.exception.ActivityNotAuthorizedException;
import com.chris64233.cc.studyconsent.service.exception.BusinessRuleException;
import com.chris64233.cc.studyconsent.service.exception.ConflictException;
import com.chris64233.cc.studyconsent.service.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler({BusinessRuleException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex, HttpServletRequest req) {
        String message = ex instanceof MethodArgumentNotValidException manv
                ? manv.getBindingResult().getFieldErrors().stream()
                .findFirst().map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("请求参数不合法")
                : ex.getMessage();
        return build(HttpStatus.BAD_REQUEST, message, req);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(ActivityNotAuthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ActivityNotAuthorizedException ex,
                                                               HttpServletRequest req) {
        Map<String, Object> body = baseBody(HttpStatus.FORBIDDEN, ex.getMessage(), req);
        body.put("denialReason", ex.getRefusal().reason().name());
        body.put("effectiveVersionNo", ex.getRefusal().effectiveVersionNo());
        body.put("consentEventId", ex.getRefusal().consentEventId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimistic(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "并发冲突，请重试", req);
    }

    /** 数据库唯一约束兜底（如并发使用同一外部事件号）。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "数据唯一性冲突（外部事件号可能已被占用）", req);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message, HttpServletRequest req) {
        return ResponseEntity.status(status).body(baseBody(status, message, req));
    }

    private Map<String, Object> baseBody(HttpStatus status, String message, HttpServletRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", req.getRequestURI());
        return body;
    }
}
