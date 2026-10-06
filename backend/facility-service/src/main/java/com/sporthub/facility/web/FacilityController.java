package com.sporthub.facility.web;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.facility.service.FacilityService;
import static com.sporthub.facility.web.FacilityDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1")
public class FacilityController {
 private final FacilityService service; private final RemoteIdentity identity;
 public FacilityController(FacilityService service,RemoteIdentity identity){this.service=service;this.identity=identity;}
 @GetMapping("/owner/facilities") public ApiResponse<List<FacilityProfileView>> owned(HttpServletRequest r){return ApiResponse.success(service.owned(identity.current(r)));}
 @PostMapping("/owner/facilities") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public ApiResponse<FacilityProfileView> create(@Valid @RequestBody FacilityInput i,HttpServletRequest r){return ApiResponse.created(service.create(i,identity.current(r)));}
 @GetMapping("/owner/facilities/{id}") public ApiResponse<FacilityProfileView> detail(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.ownedDetail(id,identity.current(r)));}
 @GetMapping("/owner/facilities/{id}/write-access") public ApiResponse<FacilityProfileView> writeAccess(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.writeAccess(id,identity.current(r)));}
 @PutMapping("/owner/facilities/{id}") public ApiResponse<FacilityProfileView> update(@PathVariable UUID id,@Valid @RequestBody FacilityInput i,HttpServletRequest r){return ApiResponse.success(service.update(id,i,identity.current(r)));}
 @GetMapping("/owner/facilities/{id}/courts") public ApiResponse<List<CourtView>> courts(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.ownedCourts(id,identity.current(r)));}
 @PostMapping("/owner/facilities/{id}/courts") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public ApiResponse<CourtView> addCourt(@PathVariable UUID id,@Valid @RequestBody CourtInput i,HttpServletRequest r){return ApiResponse.created(service.createCourt(id,i,identity.current(r)));}
 @PutMapping("/owner/courts/{id}") public ApiResponse<CourtView> updateCourt(@PathVariable UUID id,@Valid @RequestBody CourtInput i,HttpServletRequest r){return ApiResponse.success(service.updateCourt(id,i,identity.current(r)));}
 @GetMapping("/owner/courts/{id}") public ApiResponse<CourtView> ownedCourt(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.ownedCourt(id,identity.current(r)));}
 @GetMapping("/owner/courts/{id}/context") public ApiResponse<CourtContext> ownerContext(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.context(id,identity.current(r)));}
 @GetMapping("/facilities/courts/{id}/context") public ApiResponse<CourtContext> publicContext(@PathVariable UUID id){return ApiResponse.success(service.context(id,null));}
 @GetMapping("/owner/courts/{id}/maintenance") public ApiResponse<List<MaintenanceView>> windows(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.windows(id,identity.current(r)));}
 @PostMapping("/owner/courts/{id}/maintenance") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public ApiResponse<MaintenanceView> maintenance(@PathVariable UUID id,@Valid @RequestBody MaintenanceInput i,HttpServletRequest r){return ApiResponse.created(service.addWindow(id,i,identity.current(r)));}
 @DeleteMapping("/owner/maintenance/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void cancelWindow(@PathVariable UUID id,HttpServletRequest r){service.cancelWindow(id,identity.current(r));}
 @GetMapping("/facilities") public ApiResponse<List<FacilityView>> search(@RequestParam(required=false) String q,@RequestParam(required=false) String province,@RequestParam(required=false) String district,@RequestParam(required=false) UUID sportCategoryId){return ApiResponse.success(service.search(q,province,district,sportCategoryId));}
 @GetMapping("/facilities/{id}") public ApiResponse<FacilityView> publicDetail(@PathVariable UUID id){return ApiResponse.success(service.publicDetail(id));}
 @GetMapping("/facilities/{id}/courts") public ApiResponse<List<CourtView>> publicCourts(@PathVariable UUID id){return ApiResponse.success(service.publicCourts(id));}
 @GetMapping("/sport-categories") public ApiResponse<List<CategoryView>> categories(){return ApiResponse.success(service.categories());}
 @PostMapping("/admin/sport-categories") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public ApiResponse<CategoryView> addCategory(@Valid @RequestBody CategoryInput i,HttpServletRequest r){return ApiResponse.created(service.createCategory(i,identity.current(r)));}
}
