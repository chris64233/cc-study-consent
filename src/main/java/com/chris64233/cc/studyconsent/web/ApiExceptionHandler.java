package com.chris64233.cc.studyconsent.web;

import com.chris64233.cc.studyconsent.error.ActivityNotAuthorizedException;
import com.chris64233.cc.studyconsent.error.BusinessConflictException;
import com.chris64233.cc.studyconsent.error.BusinessValidationException;
import com.chris64233.cc.studyconsent.error.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(ResourceNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BusinessValidationException.class)
    public ResponseEntity<Map<String, Object>> badRequest(BusinessValidationException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(BusinessConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(BusinessConflictException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ActivityNotAuthorizedException.class)
    public ResponseEntity<Map<String, Object>> notAuthorized(ActivityNotAuthorizedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("allowed", false);
        body.put("reason", e.getDecision().reason());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
