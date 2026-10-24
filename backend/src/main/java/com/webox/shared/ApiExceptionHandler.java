package com.webox.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Problem> api(ApiException ex, HttpServletRequest request) {
        return response(ex.status(), ex.code(), ex.getMessage(), ex.fieldErrors(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Problem> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiException.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ApiException.FieldError(e.getField(), message(e))).toList();
        return response(400, "VALIDATION_FAILED", "The request contains invalid fields.", errors, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class})
    ResponseEntity<Problem> malformed(Exception ex, HttpServletRequest request) {
        return response(400, "VALIDATION_FAILED", "The request is invalid.", List.of(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Problem> integrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        return response(409, "RESOURCE_CONFLICT", "The requested change conflicts with existing data.", List.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Problem> missingResource(NoResourceFoundException ex, HttpServletRequest request) {
        return response(404, "RESOURCE_NOT_FOUND", "The requested resource was not found.", List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> unknown(Exception ex, HttpServletRequest request) {
        log.error("Unhandled API exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return response(500, "INTERNAL_ERROR", "The request could not be completed.", List.of(), request);
    }

    private ResponseEntity<Problem> response(int status, String code, String detail,
                                             List<ApiException.FieldError> errors, HttpServletRequest request) {
        String title = HttpStatus.valueOf(status).getReasonPhrase();
        Object attribute = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        String correlationId = attribute == null ? UUID.randomUUID().toString() : attribute.toString();
        Problem problem = new Problem("urn:webox:problem:" + code.toLowerCase(), title, status, detail, code,
                correlationId, errors == null || errors.isEmpty() ? null : errors, null);
        return ResponseEntity.status(status).contentType(MediaType.valueOf("application/problem+json")).body(problem);
    }

    private String message(FieldError error) {
        return switch (error.getCode() == null ? "" : error.getCode()) {
            case "NotBlank", "NotEmpty" -> "must not be blank";
            case "Email" -> "must be a valid email address";
            case "Size" -> "has an invalid length";
            case "Pattern" -> "has an invalid format";
            case "NotNull" -> "is required";
            default -> "is invalid";
        };
    }
}
