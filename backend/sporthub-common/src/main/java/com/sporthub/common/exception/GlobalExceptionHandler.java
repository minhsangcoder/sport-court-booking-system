package com.sporthub.common.exception;

import com.sporthub.common.dto.ErrorResponse;
import com.sporthub.common.dto.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts technical exceptions to the shared {@link ErrorResponse} contract. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        log.warn("[{}] {} | URI: {}", ex.getErrorCode().getCode(), ex.getMessage(), request.getRequestURI());

        Map<String, Object> details = new LinkedHashMap<>();
        if (ex.getResourceType() != null) {
            details.put("resourceType", ex.getResourceType());
        }
        if (ex.getIdentifier() != null) {
            details.put("identifier", ex.getIdentifier());
        }

        return response(HttpStatus.NOT_FOUND, ex.getErrorCode(), ex.getMessage(), request,
                null, details.isEmpty() ? null : details);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(
            ConflictException ex, HttpServletRequest request) {
        log.warn("[{}] {} | URI: {}", ex.getErrorCode().getCode(), ex.getMessage(), request.getRequestURI());
        return response(HttpStatus.CONFLICT, ex.getErrorCode(), ex.getMessage(), request, null, null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("[{}] Optimistic lock conflict | URI: {} | Entity: {}",
                ErrorCode.OPTIMISTIC_LOCK_CONFLICT.getCode(), request.getRequestURI(), ex.getPersistentClassName());
        return response(HttpStatus.CONFLICT, ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                ErrorCode.OPTIMISTIC_LOCK_CONFLICT.getDefaultMessage(), request, null, null);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(
            UnauthorizedException ex, HttpServletRequest request) {
        log.warn("[{}] {} | URI: {}", ex.getErrorCode().getCode(), ex.getMessage(), request.getRequestURI());
        return response(HttpStatus.UNAUTHORIZED, ex.getErrorCode(), ex.getMessage(), request, null, null);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(
            ForbiddenException ex, HttpServletRequest request) {
        log.warn("[{}] {} | URI: {}", ex.getErrorCode().getCode(), ex.getMessage(), request.getRequestURI());
        return response(HttpStatus.FORBIDDEN, ex.getErrorCode(), ex.getMessage(), request, null, null);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRule(
            BusinessRuleException ex, HttpServletRequest request) {
        log.warn("[{}] {} | URI: {}", ex.getErrorCode().getCode(), ex.getMessage(), request.getRequestURI());
        return response(HttpStatus.UNPROCESSABLE_ENTITY, ex.getErrorCode(), ex.getMessage(), request, null, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> FieldError.builder()
                        .field(error.getField())
                        .code(error.getCode())
                        .message(error.getDefaultMessage())
                        .build())
                .toList();
        log.warn("[{}] Validation failed: {}", ErrorCode.VALIDATION_ERROR.getCode(), fieldErrors);
        return response(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.getDefaultMessage(), request, fieldErrors, null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        String message = "Missing required parameter: " + ex.getParameterName();
        log.warn("[{}] {}", ErrorCode.VALIDATION_ERROR.getCode(), message);
        return response(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message, request, null, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = String.format("Parameter '%s' must be of type %s",
                ex.getName(), ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");
        log.warn("[{}] {}", ErrorCode.VALIDATION_ERROR.getCode(), message);
        return response(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message, request, null, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage(), request, null, null);
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataConflict(org.springframework.dao.DataIntegrityViolationException ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "A resource with these values already exists or is referenced", request, null, null);
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleHttpFailure(org.springframework.web.server.ResponseStatusException ex, HttpServletRequest request) {
        return response(HttpStatus.valueOf(ex.getStatusCode().value()), ErrorCode.INTERNAL_ERROR, ex.getReason(), request, null, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("[{}] Unexpected error at URI: {} | Error: {}",
                ErrorCode.INTERNAL_ERROR.getCode(), request.getRequestURI(), ex.getMessage(), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Please try again later.", request, null, null);
    }

    private ResponseEntity<ErrorResponse> response(
            HttpStatus status,
            ErrorCode errorCode,
            String message,
            HttpServletRequest request,
            List<FieldError> fieldErrors,
            Map<String, Object> details) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.builder()
                        .status(status.value())
                        .code(errorCode.getCode())
                        .message(message)
                        .traceId(request.getHeader(CORRELATION_ID_HEADER))
                        .fieldErrors(fieldErrors)
                        .details(details)
                        .build());
    }
}
