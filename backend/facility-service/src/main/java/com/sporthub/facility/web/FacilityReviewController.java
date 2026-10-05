package com.sporthub.facility.web;
import com.sporthub.facility.service.*;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1")
public class FacilityReviewController {
 public record Decision(@NotNull @Pattern(regexp="APPROVE|REJECT|SUPPLEMENT_REQUIRED") String action,@NotBlank @Size(max=2000) String reason){}
 private final FacilityReviewService service;private final RemoteIdentity identity;
 public FacilityReviewController(FacilityReviewService service,RemoteIdentity identity){this.service=service;this.identity=identity;}
 @PostMapping("/owner/facilities/{id}/submit") public ApiResponse<FacilityReviewService.Review> submit(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.submit(id,identity.current(r),r.getHeader("Authorization")));}
 @GetMapping("/owner/facilities/{id}/reviews") public ApiResponse<List<FacilityReviewService.Review>> history(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.ownHistory(id,identity.current(r)));}
 @GetMapping("/admin/facilities") public ApiResponse<List<FacilityDtos.FacilityView>> search(@RequestParam(required=false) String q,@RequestParam(required=false) String status,HttpServletRequest r){return ApiResponse.success(service.search(q,status,identity.current(r)));}
 @GetMapping("/admin/facilities/{id}") public ApiResponse<FacilityReviewService.Detail> detail(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.detail(id,identity.current(r)));}
 @PostMapping("/admin/facilities/{id}/decision") public ApiResponse<FacilityDtos.FacilityView> decide(@PathVariable UUID id,@Valid @RequestBody Decision input,HttpServletRequest r){return ApiResponse.success(service.decide(id,input.action(),input.reason(),identity.current(r)));}
}
