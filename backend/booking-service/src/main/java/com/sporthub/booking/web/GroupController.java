package com.sporthub.booking.web;

import static com.sporthub.booking.web.GroupDtos.*;
import com.sporthub.booking.web.BookingDtos.*;
import com.sporthub.booking.service.GroupService;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController @RequestMapping("/api/v1/groups")
public class GroupController {
    private final GroupService service;private final RemoteIdentity identity;
    public GroupController(GroupService service,RemoteIdentity identity){this.service=service;this.identity=identity;}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Group> create(@Valid @RequestBody CreateGroup input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return ApiResponse.created(service.create(input,key,identity.current(r),r.getHeader("Authorization")));}
    @GetMapping public ApiResponse<List<Group>> mine(HttpServletRequest r){return ApiResponse.success(service.mine(identity.current(r)));}
    @GetMapping("/{id}") public ApiResponse<Group> detail(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.detail(id,identity.current(r)));}
    @GetMapping("/{id}/invite") public ApiResponse<Invite> invite(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.invite(id,identity.current(r)));}
    @GetMapping("/invitations") public ApiResponse<Group> invitation(@RequestParam String code,HttpServletRequest r){return ApiResponse.success(service.invitation(code,identity.current(r)));}
    @PostMapping("/join") public ApiResponse<Group> join(@Valid @RequestBody JoinGroup input,HttpServletRequest r){return ApiResponse.success(service.join(input,identity.current(r)));}
    @PostMapping("/{id}/leave") public ApiResponse<Group> leave(@PathVariable UUID id,HttpServletRequest r){return ApiResponse.success(service.leave(id,identity.current(r)));}
    @PutMapping("/{id}/allocations") public ApiResponse<Group> split(@PathVariable UUID id,@Valid @RequestBody Split input,HttpServletRequest r){return ApiResponse.success(service.split(id,input,identity.current(r)));}
    @GetMapping("/{id}/members/{memberId}/payable") public ApiResponse<Payable> payable(@PathVariable UUID id,@PathVariable UUID memberId,HttpServletRequest r){return ApiResponse.success(service.payable(id,memberId,identity.current(r)));}
    @PostMapping("/{id}/cancel") public ApiResponse<Group> cancel(@PathVariable UUID id,@Valid @RequestBody CancelInput input,HttpServletRequest r){return ApiResponse.success(service.cancel(id,input,identity.current(r)));}
}
