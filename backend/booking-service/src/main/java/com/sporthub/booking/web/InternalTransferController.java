package com.sporthub.booking.web;

import com.sporthub.booking.service.TransferLeaseService;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.ServiceCalls;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/internal/bookings")
public class InternalTransferController {
    private final TransferLeaseService service;private final ObjectMapper json;private final String secret;
    public InternalTransferController(TransferLeaseService service,ObjectMapper json,@Value("${SERVICE_CALL_SECRET}") String secret){this.service=service;this.json=json;this.secret=secret;}
    @GetMapping("/{id}") public ApiResponse<com.fasterxml.jackson.databind.JsonNode> inspect(@PathVariable UUID id,HttpServletRequest request){verify(request,"");return ApiResponse.success(service.inspect(id));}
    @PostMapping("/transfer/{action}") public ApiResponse<BookingDtos.Booking> command(@PathVariable String action,@RequestBody String body,HttpServletRequest request)throws Exception {
        verify(request,body);return ApiResponse.success(service.command(action,json.readValue(body,TransferLeaseService.Command.class)));
    }
    private void verify(HttpServletRequest r,String body){ServiceCalls.verify(secret,r.getMethod(),r.getRequestURI(),r.getHeader("X-Service-Time"),body,r.getHeader("X-Service-Signature"));}
}
