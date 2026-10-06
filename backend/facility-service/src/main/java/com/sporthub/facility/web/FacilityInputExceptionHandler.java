package com.sporthub.facility.web;

import com.sporthub.common.dto.ErrorResponse;
import com.sporthub.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/** Reject malformed JSON/numbers without logging or echoing the request body. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={FacilityController.class,InternalOwnerApplicationController.class})
public class FacilityInputExceptionHandler {
 @ExceptionHandler(HttpMessageNotReadableException.class)
 public ResponseEntity<ErrorResponse> malformed(HttpServletRequest request){
  return ResponseEntity.badRequest().body(ErrorResponse.builder().status(400).code(ErrorCode.VALIDATION_ERROR.getCode())
   .message("Invalid facility JSON; coordinates must be numeric")
   .traceId(request.getHeader("X-Correlation-Id")).build());
 }
}
