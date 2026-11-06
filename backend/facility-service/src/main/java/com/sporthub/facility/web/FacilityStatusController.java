package com.sporthub.facility.web;
import com.sporthub.facility.service.FacilityStatusService;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1")
public class FacilityStatusController {
 private final FacilityStatusService service;private final RemoteIdentity identity;
 public FacilityStatusController(FacilityStatusService service,RemoteIdentity identity){this.service=service;this.identity=identity;}
 @GetMapping("/admin/facilities/{id}/status-impact") public ApiResponse<FacilityStatusService.Preview> preview(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.preview(id,identity.current(r)));}
 @PostMapping("/admin/facilities/{id}/status-decisions") public ApiResponse<FacilityDtos.FacilityView> decide(@PathVariable UUID id,@Valid @RequestBody FacilityStatusService.Decision input,HttpServletRequest r){return ApiResponse.success(service.decide(id,input,identity.current(r)));}
 @GetMapping("/admin/facilities/{id}/status-history") public ApiResponse<List<Map<String,Object>>> adminHistory(@PathVariable UUID id,HttpServletRequest r){identity.current(r).requireRole("ADMIN");return ApiResponse.success(service.history(id,identity.current(r)));}
 @GetMapping("/owner/facilities/{id}/status-history") public ApiResponse<List<Map<String,Object>>> ownHistory(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.history(id,identity.current(r)));}
 @GetMapping("/admin/facilities/court-directory") public ApiResponse<List<Map<String,Object>>> courtDirectory(HttpServletRequest r){return ApiResponse.success(service.courtDirectory(identity.current(r)));}
 @GetMapping("/admin/facilities/statistics") public ApiResponse<Map<String,Object>> statistics(HttpServletRequest r){return ApiResponse.success(service.statistics(identity.current(r)));}
}
