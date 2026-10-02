package com.expensepilot.api;

import org.springframework.http.HttpStatus;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** API 稳定错误语义。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(
            IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "BAD_REQUEST",
                "message", safeMessage(ex)
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(
            IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "STATE_CONFLICT",
                "message", safeMessage(ex)
        ));
    }

    @ExceptionHandler(TaskRejectedException.class)
    public ResponseEntity<Map<String, Object>> overloaded(
            TaskRejectedException ex) {
        return ResponseEntity.status(
                HttpStatus.SERVICE_UNAVAILABLE
        ).body(Map.of(
                "code", "EXECUTOR_SATURATED",
                "message", "当前任务执行队列已满，请稍后重试"
        ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> forbidden(
            AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", "FORBIDDEN",
                "message", safeMessage(ex)
        ));
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }
}
