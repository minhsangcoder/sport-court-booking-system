package com.sporthub.booking.web;
import com.sporthub.booking.service.*;
import static com.sporthub.booking.web.BookingDtos.*;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class OperationalBookingController {
 private final OperationalBookingService operations;private final RemoteIdentity identity;
 public OperationalBookingController(OperationalBookingService operations,RemoteIdentity identity){this.operations=operations;this.identity=identity;}
 @GetMapping("/bookings/{id}/operations") public ApiResponse<Operations> operations(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(operations.operations(id,identity.current(r),r.getHeader("Authorization")));}
 @GetMapping("/bookings/management/search") public ApiResponse<Page> search(@RequestParam(required=false) UUID facilityId,@RequestParam(required=false) UUID courtId,@RequestParam(required=false) String status,@RequestParam(required=false) String source,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(required=false) String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest r){return ApiResponse.success(operations.search(new Search(facilityId,courtId,status,source,from,to,q,page,size),identity.current(r),r.getHeader("Authorization")));}
}
