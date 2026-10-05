package com.sporthub.schedule.web;

import static com.sporthub.schedule.web.ScheduleDtos.*;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.schedule.service.ScheduleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {
    private final ScheduleService service;
    private final RemoteIdentity identity;
    public ScheduleController(ScheduleService service,RemoteIdentity identity) {this.service=service;this.identity=identity;}
    @GetMapping("/facilities/{id}/hours") public ApiResponse<List<Hours>> hours(@PathVariable UUID id,HttpServletRequest r) {return ApiResponse.success(service.hours(id,identity.current(r),r.getHeader("Authorization")));}
    @PutMapping("/facilities/{id}/hours") public ApiResponse<List<Hours>> saveHours(@PathVariable UUID id,@Valid @RequestBody HoursInput i,HttpServletRequest r) {return ApiResponse.success(service.replaceHours(id,i,identity.current(r),r.getHeader("Authorization")));}
    @GetMapping("/facilities/{id}/exceptions") public ApiResponse<List<ExceptionView>> exceptions(@PathVariable UUID id,HttpServletRequest r) {return ApiResponse.success(service.exceptions(id,identity.current(r),r.getHeader("Authorization")));}
    @PostMapping("/facilities/{id}/exceptions") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<ExceptionView> exception(@PathVariable UUID id,@Valid @RequestBody ExceptionInput i,HttpServletRequest r) {return ApiResponse.created(service.addException(id,i,identity.current(r),r.getHeader("Authorization")));}
    @DeleteMapping("/facilities/{id}/exceptions/{exceptionId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void removeException(@PathVariable UUID id,@PathVariable UUID exceptionId,HttpServletRequest r) {service.removeException(id,exceptionId,identity.current(r),r.getHeader("Authorization"));}
    @GetMapping("/facilities/{id}/pricing") public ApiResponse<List<PriceRule>> rules(@PathVariable UUID id,HttpServletRequest r) {return ApiResponse.success(service.rules(id,identity.current(r),r.getHeader("Authorization")));}
    @PostMapping("/facilities/{id}/pricing") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<PriceRule> price(@PathVariable UUID id,@Valid @RequestBody PriceInput i,HttpServletRequest r) {return ApiResponse.created(service.addPrice(id,i,identity.current(r),r.getHeader("Authorization")));}
    @DeleteMapping("/facilities/{id}/pricing/{ruleId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void disablePrice(@PathVariable UUID id,@PathVariable UUID ruleId,HttpServletRequest r) {service.disablePrice(id,ruleId,identity.current(r),r.getHeader("Authorization"));}
    @GetMapping("/facilities/{id}/preview") public ApiResponse<Preview> ownerPreview(@PathVariable UUID id,@RequestParam UUID courtId,@RequestParam LocalDate date,HttpServletRequest r) {return ApiResponse.success(service.ownerPreview(id,courtId,date,identity.current(r),r.getHeader("Authorization")));}
    @GetMapping("/public/courts/{id}") public ApiResponse<Preview> preview(@PathVariable UUID id,@RequestParam LocalDate date) {return ApiResponse.success(service.publicPreview(id,date));}
    @PostMapping("/public/quote") public ApiResponse<Quote> quote(@Valid @RequestBody QuoteInput input) {return ApiResponse.success(service.quote(input));}
}
