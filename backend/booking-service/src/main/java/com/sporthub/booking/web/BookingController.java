package com.sporthub.booking.web;

import static com.sporthub.booking.web.BookingDtos.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.booking.service.BookingService;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {
  private final BookingService service;
  private final RemoteIdentity identity;

  public BookingController(BookingService service, RemoteIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/availability")
  public ApiResponse<JsonNode> availability(@RequestParam UUID courtId, @RequestParam String date) {
    return ApiResponse.success(service.availability(courtId, date));
  }

  @PostMapping("/holds")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<Hold> hold(
      @Valid @RequestBody HoldInput input,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestParam(defaultValue = "false") boolean counter,
      HttpServletRequest r) {
    return ApiResponse.created(
        service.hold(input, key, identity.current(r), r.getHeader("Authorization"), counter));
  }

  @GetMapping("/holds/{id}")
  public ApiResponse<Hold> hold(@PathVariable UUID id, HttpServletRequest r) {
    return ApiResponse.success(
        service.ownHold(id, identity.current(r), r.getHeader("Authorization")));
  }

  @DeleteMapping("/holds/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@PathVariable UUID id, HttpServletRequest r) {
    service.releaseHold(id, identity.current(r), r.getHeader("Authorization"));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<Booking> create(
      @Valid @RequestBody CreateInput input,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestParam(defaultValue = "false") boolean counter,
      HttpServletRequest r) {
    return ApiResponse.created(
        view(
            service.create(input, key, identity.current(r), r.getHeader("Authorization"), counter),
            r));
  }

  @PostMapping("/counter")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<Booking> counter(
      @Valid @RequestBody CounterInput input,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest r) {
    return ApiResponse.created(
        view(service.counter(input, key, identity.current(r), r.getHeader("Authorization")), r));
  }

  @PostMapping("/facility/{id}/check-in-by-code")
  public ApiResponse<Booking> code(
      @PathVariable UUID id, @Valid @RequestBody CodeInput input, HttpServletRequest r) {
    return ApiResponse.success(
        view(
            service.checkinByCode(id, input, identity.current(r), r.getHeader("Authorization")),
            r));
  }

  @GetMapping
  public ApiResponse<List<Booking>> mine(HttpServletRequest r) {
    return ApiResponse.success(service.mine(identity.current(r), r.getHeader("Authorization")));
  }

  @GetMapping("/facility/{id}")
  public ApiResponse<List<Booking>> facility(@PathVariable UUID id, HttpServletRequest r) {
    return ApiResponse.success(
        service.facility(id, identity.current(r), r.getHeader("Authorization")));
  }

  @GetMapping("/{id}")
  public ApiResponse<Detail> detail(@PathVariable UUID id, HttpServletRequest r) {
    return ApiResponse.success(
        service.detail(id, identity.current(r), r.getHeader("Authorization")));
  }

  @GetMapping("/{id}/payable")
  public ApiResponse<Payable> payable(@PathVariable UUID id, HttpServletRequest r) {
    return ApiResponse.success(
        service.payable(id, identity.current(r), r.getHeader("Authorization")));
  }

  @PostMapping("/{id}/cancel")
  public ApiResponse<Booking> cancel(
      @PathVariable UUID id, @Valid @RequestBody CancelInput input, HttpServletRequest r) {
    return ApiResponse.success(
        view(service.cancel(id, input, identity.current(r), r.getHeader("Authorization")), r));
  }

  @PostMapping("/{id}/check-in")
  public ApiResponse<Booking> checkin(
      @PathVariable UUID id, @Valid @RequestBody CheckinInput input, HttpServletRequest r) {
    return ApiResponse.success(
        view(service.checkin(id, input, identity.current(r), r.getHeader("Authorization")), r));
  }

  @PostMapping("/{id}/complete")
  public ApiResponse<Booking> complete(@PathVariable UUID id, HttpServletRequest r) {
    return ApiResponse.success(
        view(service.complete(id, identity.current(r), r.getHeader("Authorization")), r));
  }

  @PostMapping("/{id}/check-in-guest")
  public ApiResponse<Booking> guest(@PathVariable UUID id, HttpServletRequest r) {
    return ApiResponse.success(
        view(service.checkinGuest(id, identity.current(r), r.getHeader("Authorization")), r));
  }

  private Booking view(Booking b, HttpServletRequest r) {
    var caller = identity.current(r);
    return caller.hasRole("CUSTOMER") && Objects.equals(b.currentHolderId(), caller.id())
        ? b
        : b.withBookingCode(null);
  }
}
