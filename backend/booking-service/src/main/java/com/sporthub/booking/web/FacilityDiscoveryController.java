package com.sporthub.booking.web;
import com.sporthub.booking.service.FacilityDiscoveryService;
import com.sporthub.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/bookings/search/facilities")
public class FacilityDiscoveryController {
    private final FacilityDiscoveryService service;
    public FacilityDiscoveryController(FacilityDiscoveryService service) {this.service=service;}
    @GetMapping public ApiResponse<FacilityDiscoveryService.Page> search(@ModelAttribute FacilityDiscoveryService.Criteria criteria) {return ApiResponse.success(service.search(criteria));}
}
