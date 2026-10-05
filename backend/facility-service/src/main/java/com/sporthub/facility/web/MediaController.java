package com.sporthub.facility.web;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.facility.service.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
@RestController @RequestMapping("/api/v1/owner/facilities/{facilityId}/images")
public class MediaController {
 private final MediaService media;private final RemoteIdentity identity;
 public MediaController(MediaService media,RemoteIdentity identity){this.media=media;this.identity=identity;}
 @GetMapping public ApiResponse<List<MediaService.ImageView>> list(@PathVariable UUID facilityId,HttpServletRequest r){return ApiResponse.success(media.list(facilityId,identity.current(r)));}
 @PostMapping(consumes="multipart/form-data") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
 public ApiResponse<MediaService.ImageView> upload(@PathVariable UUID facilityId,@RequestParam(required=false) UUID courtId,@RequestPart MultipartFile file,HttpServletRequest r){return ApiResponse.created(media.upload(facilityId,courtId,file,identity.current(r)));}
 @PutMapping(value="/{imageId}",consumes="multipart/form-data")
 public ApiResponse<MediaService.ImageView> replace(@PathVariable UUID facilityId,@PathVariable UUID imageId,@RequestPart MultipartFile file,HttpServletRequest r){return ApiResponse.success(media.replace(facilityId,imageId,file,identity.current(r)));}
 @DeleteMapping("/{imageId}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
 public void delete(@PathVariable UUID facilityId,@PathVariable UUID imageId,HttpServletRequest r){media.delete(facilityId,imageId,identity.current(r));}
}
