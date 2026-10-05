package com.sporthub.booking.web;
import com.sporthub.booking.service.DiscoveryService;
import com.sporthub.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/bookings/search")
public class DiscoveryController {
    private final DiscoveryService discovery;
    public DiscoveryController(DiscoveryService discovery){this.discovery=discovery;}
    @GetMapping public ApiResponse<DiscoveryService.SearchResult> search(@RequestParam(required=false) String q,@RequestParam(required=false) String province,@RequestParam(required=false) String district,@RequestParam(required=false) UUID sportCategoryId,@RequestParam(required=false) String date,@RequestParam(required=false) String opensAfter,@RequestParam(required=false) String closesBefore,@RequestParam(required=false) BigDecimal minPrice,@RequestParam(required=false) BigDecimal maxPrice,@RequestParam(required=false) Double latitude,@RequestParam(required=false) Double longitude,@RequestParam(required=false) Double radiusKm,@RequestParam(required=false) String sort){return ApiResponse.success(discovery.search(new DiscoveryService.Criteria(q,province,district,sportCategoryId,date,opensAfter,closesBefore,minPrice,maxPrice,latitude,longitude,radiusKm,sort)));}
}
