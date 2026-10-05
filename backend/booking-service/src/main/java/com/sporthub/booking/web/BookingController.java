package com.sporthub.booking.web;
import static com.sporthub.booking.web.BookingDtos.*;
import com.sporthub.booking.service.BookingService;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController @RequestMapping("/api/v1/bookings")
public class BookingController {
    private final BookingService service;private final RemoteIdentity identity;
    public BookingController(BookingService service,RemoteIdentity identity){this.service=service;this.identity=identity;}
    @GetMapping("/availability") public ApiResponse<JsonNode> availability(@RequestParam UUID courtId,@RequestParam String date){return ApiResponse.success(service.availability(courtId,date));}
    @PostMapping("/holds") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Hold> hold(@Valid @RequestBody HoldInput input,@RequestHeader("Idempotency-Key") String key,@RequestParam(defaultValue="false") boolean counter,HttpServletRequest r){return ApiResponse.created(service.hold(input,key,identity.current(r),r.getHeader("Authorization"),counter));}
    @GetMapping("/holds/{id}") public ApiResponse<Hold> hold(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.ownHold(id,identity.current(r)));}
    @DeleteMapping("/holds/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void release(@PathVariable UUID id,HttpServletRequest r){service.releaseHold(id,identity.current(r));}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Booking> create(@Valid @RequestBody CreateInput input,@RequestHeader("Idempotency-Key") String key,@RequestParam(defaultValue="false") boolean counter,HttpServletRequest r){return ApiResponse.created(service.create(input,key,identity.current(r),r.getHeader("Authorization"),counter));}
    @GetMapping public ApiResponse<List<Booking>> mine(HttpServletRequest r){return ApiResponse.success(service.mine(identity.current(r)));}
    @GetMapping("/facility/{id}") public ApiResponse<List<Booking>> facility(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.facility(id,identity.current(r),r.getHeader("Authorization")));}
    @GetMapping("/{id}") public ApiResponse<Detail> detail(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.detail(id,identity.current(r),r.getHeader("Authorization")));}
    @GetMapping("/{id}/payable") public ApiResponse<Payable> payable(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.payable(id,identity.current(r)));}
    @PostMapping("/{id}/cancel") public ApiResponse<Booking> cancel(@PathVariable UUID id,@Valid @RequestBody CancelInput input,HttpServletRequest r){return ApiResponse.success(service.cancel(id,input,identity.current(r)));}
    @PostMapping("/{id}/check-in") public ApiResponse<Booking> checkin(@PathVariable UUID id,@Valid @RequestBody CheckinInput input,HttpServletRequest r){return ApiResponse.success(service.checkin(id,input,identity.current(r),r.getHeader("Authorization")));}
    @PostMapping("/{id}/complete") public ApiResponse<Booking> complete(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.complete(id,identity.current(r),r.getHeader("Authorization")));}
}
