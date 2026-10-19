package com.sporthub.facility.web;
import com.sporthub.facility.service.DiscoveryCatalog;
import com.sporthub.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1/facilities")
public class DiscoveryCatalogController {
    private final DiscoveryCatalog catalog;
    public DiscoveryCatalogController(DiscoveryCatalog catalog) { this.catalog=catalog; }
    @GetMapping("/search-catalog")
    public ApiResponse<List<DiscoveryCatalog.Entry>> search(@RequestParam(required=false) String q,@RequestParam(required=false) String provinceCode,
        @RequestParam(required=false) String wardCode,@RequestParam(required=false) UUID sportCategoryId,@RequestParam(required=false) UUID facilityId) {
        return ApiResponse.success(catalog.search(q,provinceCode,wardCode,sportCategoryId,facilityId));
    }
    @GetMapping("/courts/contexts")
    public ApiResponse<List<FacilityDtos.CourtContext>> contexts(@RequestParam List<UUID> ids) { return ApiResponse.success(catalog.contexts(ids)); }
}
