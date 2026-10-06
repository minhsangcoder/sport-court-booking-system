package com.sporthub.identity.exception;

import com.sporthub.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class IdentityExceptionHandler {
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.multipart.MaxUploadSizeExceededException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> handleSignupInput(Exception exception,HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ErrorResponse.builder().status(400).code("IDENTITY-VALIDATION")
                .message(exception instanceof org.springframework.web.multipart.MaxUploadSizeExceededException?"Each file must be at most 10 MB; request must be at most 31 MB":"Malformed registration data or missing request part")
                .traceId(request.getHeader("X-Correlation-Id")).build());
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<ErrorResponse> handle(IdentityException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus())
                .body(ErrorResponse.builder()
                        .status(exception.getStatus().value())
                        .code(exception.getCode())
                        .message(exception.getMessage())
                        .fieldErrors(exception instanceof OwnerSignupInputException input?input.fields():null)
                        .traceId(request.getHeader("X-Correlation-Id"))
                        .build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<com.sporthub.common.dto.FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> com.sporthub.common.dto.FieldError.builder()
                        .field(error.getField())
                        .code(error.getCode())
                        .message(error.getDefaultMessage())
                        .build())
                .toList();
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .status(400)
                .code("IDENTITY-VALIDATION")
                .message("Request validation failed")
                .traceId(request.getHeader("X-Correlation-Id"))
                .fieldErrors(fields)
                .build());
    }
}
