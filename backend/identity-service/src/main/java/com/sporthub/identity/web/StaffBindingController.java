package com.sporthub.identity.web;
import com.sporthub.identity.service.StaffBindingService;
import com.sporthub.identity.security.AuthenticatedUser;
import com.sporthub.common.dto.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
@RestController @RequestMapping("/api/v1/staff-bindings/facilities/{facilityId}")
public class StaffBindingController {
 private final StaffBindingService service;public StaffBindingController(StaffBindingService service){this.service=service;}
 @GetMapping public ApiResponse<List<StaffBindingService.Binding>> list(@PathVariable UUID facilityId,@AuthenticationPrincipal AuthenticatedUser user,HttpServletRequest r){return ApiResponse.success(service.list(facilityId,user,r.getHeader("Authorization")));}
 @PostMapping public ApiResponse<StaffBindingService.Binding> assign(@PathVariable UUID facilityId,@Valid @RequestBody StaffBindingService.Input input,@AuthenticationPrincipal AuthenticatedUser user,HttpServletRequest r){return ApiResponse.success(service.assign(facilityId,input,user,r.getHeader("Authorization")));}
 @DeleteMapping("/{bindingId}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void revoke(@PathVariable UUID facilityId,@PathVariable UUID bindingId,@AuthenticationPrincipal AuthenticatedUser user,HttpServletRequest r){service.revoke(facilityId,bindingId,user,r.getHeader("Authorization"));}
}
