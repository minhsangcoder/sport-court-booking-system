package com.sporthub.facility.web;
import com.sporthub.facility.service.FacilityDocumentService;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
@RestController @RequestMapping("/api/v1")
public class FacilityDocumentController {
 private final FacilityDocumentService service;private final RemoteIdentity identity;private final com.sporthub.facility.service.MediaService media;
 public FacilityDocumentController(FacilityDocumentService service,RemoteIdentity identity,com.sporthub.facility.service.MediaService media){this.service=service;this.identity=identity;this.media=media;}
 @GetMapping("/owner/facilities/{id}/documents") public ApiResponse<List<FacilityDocumentService.Document>> own(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.list(id,identity.current(r)));}
 @GetMapping("/admin/facilities/{id}/documents") public ApiResponse<List<FacilityDocumentService.Document>> admin(@PathVariable UUID id,HttpServletRequest r){var actor=identity.current(r);actor.requireRole("ADMIN");return ApiResponse.success(service.list(id,actor));}
 @GetMapping("/admin/facilities/{id}/images") public ApiResponse<List<com.sporthub.facility.service.MediaService.ImageView>> images(@PathVariable UUID id,HttpServletRequest r){var actor=identity.current(r);actor.requireRole("ADMIN");return ApiResponse.success(media.list(id,actor));}
 @PostMapping(value="/owner/facilities/{id}/documents",consumes="multipart/form-data") public ApiResponse<FacilityDocumentService.Document> upload(@PathVariable UUID id,@RequestPart MultipartFile file,HttpServletRequest r){return ApiResponse.success(service.upload(id,file,identity.current(r)));}
 @DeleteMapping("/owner/facilities/{id}/documents/{documentId}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id,@PathVariable UUID documentId,HttpServletRequest r){service.delete(id,documentId,identity.current(r));}
}
