package com.sporthub.payment.web;
import static com.sporthub.payment.web.PaymentDtos.*;
import com.sporthub.payment.service.PaymentService;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController @RequestMapping("/api/v1/payments")
public class PaymentController {
 private final PaymentService service;private final RemoteIdentity identity;
 public PaymentController(PaymentService service,RemoteIdentity identity){this.service=service;this.identity=identity;}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Payment> create(@Valid @RequestBody CreatePayment input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return ApiResponse.created(service.create(input,key,identity.current(r),r.getHeader("Authorization")));}
 @GetMapping public ApiResponse<List<Payment>> mine(HttpServletRequest r){return ApiResponse.success(service.mine(identity.current(r)));}
 @GetMapping("/{id}") public ApiResponse<Payment> detail(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.own(id,identity.current(r)));}
 @PostMapping("/{id}/demo/complete") public ApiResponse<Payment> demo(@PathVariable UUID id,@Valid @RequestBody DemoOutcome input,HttpServletRequest r){return ApiResponse.success(service.demo(id,input,identity.current(r)));}
 @PostMapping("/providers/demo/callback") public ApiResponse<Payment> callback(@Valid @RequestBody Callback input,@RequestHeader("X-Provider-Signature") String signature){return ApiResponse.success(service.callback(input,signature));}
 @PostMapping("/{id}/refunds") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Refund> refund(@PathVariable UUID id,@Valid @RequestBody RefundInput input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return ApiResponse.created(service.requestRefund(id,input,key,identity.current(r)));}
}
