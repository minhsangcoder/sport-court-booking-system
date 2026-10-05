package com.sporthub.facility.web;
import com.sporthub.facility.service.MediaService;
import com.sporthub.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
public class PublicMediaController {
 private final MediaService media;public PublicMediaController(MediaService media){this.media=media;}
 @GetMapping("/api/v1/facilities/{id}/images") public ApiResponse<List<MediaService.ImageView>> list(@PathVariable UUID id){return ApiResponse.success(media.list(id,null));}
}
