package com.sporthub.transfer.web;
import com.sporthub.transfer.service.*;
import static com.sporthub.transfer.web.TransferDtos.*;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
@RestController @RequestMapping("/api/v1/transfers")
public class TransferController {
 private final TransferService service;private final TransferWorkflow workflow;private final RemoteIdentity identity;
 public TransferController(TransferService service,TransferWorkflow workflow,RemoteIdentity identity){this.service=service;this.workflow=workflow;this.identity=identity;}
 @GetMapping public ApiResponse<List<Listing>> market(@RequestParam(required=false) String q,@RequestParam(required=false) UUID category,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant after,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant before,@RequestParam(required=false) BigDecimal maxPrice,@RequestParam(defaultValue="start") String sort,HttpServletRequest r){return ApiResponse.success(service.market(q,category,after,before,maxPrice,sort,identity.current(r)));}
 @GetMapping("/mine") public ApiResponse<List<Listing>> mine(HttpServletRequest r){return ApiResponse.success(service.mine(identity.current(r)));}
 @GetMapping("/acquisitions") public ApiResponse<List<Acquisition>> purchases(HttpServletRequest r){return ApiResponse.success(service.purchases(identity.current(r)));}
 @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public ApiResponse<Listing> create(@Valid @RequestBody Create input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){var caller=identity.current(r);var id=service.create(input,key,caller);workflow.sync(id);return ApiResponse.created(service.detail(id,caller));}
 @GetMapping("/{id}") public ApiResponse<Listing> detail(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.detail(id,identity.current(r)));}
 @PutMapping("/{id}") public ApiResponse<Listing> edit(@PathVariable UUID id,@Valid @RequestBody Edit input,HttpServletRequest r){var caller=identity.current(r);service.edit(id,input,caller);workflow.sync(id);return ApiResponse.success(service.detail(id,caller));}
 @PostMapping("/{id}/withdraw") public ApiResponse<Listing> withdraw(@PathVariable UUID id,HttpServletRequest r){var caller=identity.current(r);service.withdraw(id,caller);workflow.sync(id);return ApiResponse.success(service.detail(id,caller));}
 @PostMapping("/{id}/acquisitions") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public ApiResponse<Acquisition> acquire(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){var caller=identity.current(r);var acquisition=service.acquire(id,key,caller);workflow.sync(id);return ApiResponse.created(service.acquisition(acquisition,caller));}
 @GetMapping("/acquisitions/{id}") public ApiResponse<Acquisition> acquisition(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.acquisition(id,identity.current(r)));}
 @GetMapping("/acquisitions/{id}/payable") public ApiResponse<Payable> payable(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.payable(id,identity.current(r)));}
 @PostMapping("/acquisitions/{id}/cancel") public ApiResponse<Acquisition> cancel(@PathVariable UUID id,HttpServletRequest r){var caller=identity.current(r);var a=service.acquisition(id,caller);service.cancel(id,caller);workflow.sync(a.listingId());return ApiResponse.success(service.acquisition(id,caller));}
}
