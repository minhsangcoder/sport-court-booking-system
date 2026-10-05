package com.sporthub.identity.web;
import com.sporthub.identity.service.*;
import com.sporthub.identity.domain.*;
import com.sporthub.identity.security.AuthenticatedUser;
import static com.sporthub.identity.web.dto.AdminAccountDtos.*;
import com.sporthub.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/accounts")
public class AdminAccountController {
 private final AdminAccountService service;public AdminAccountController(AdminAccountService service){this.service=service;}
 @GetMapping public ApiResponse<List<Account>> search(@RequestParam(required=false) String q,@RequestParam(required=false) AccountStatus status,@RequestParam(required=false) Role role,@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.success(service.search(q,status,role,actor));}
 @GetMapping("/statistics") public ApiResponse<List<Map<String,Object>>> stats(@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.success(service.statistics(actor));}
 @GetMapping("/{id}") public ApiResponse<Detail> detail(@PathVariable UUID id,@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.success(service.detail(id,actor));}
 @PostMapping("/{id}/lock") public ApiResponse<Account> lock(@PathVariable UUID id,@Valid @RequestBody LockInput input,@AuthenticationPrincipal AuthenticatedUser actor,HttpServletRequest request){return ApiResponse.success(service.lock(id,input,actor,RequestMetadata.from(request)));}
 @PostMapping("/{id}/unlock") public ApiResponse<Account> unlock(@PathVariable UUID id,@Valid @RequestBody Reason input,@AuthenticationPrincipal AuthenticatedUser actor,HttpServletRequest request){return ApiResponse.success(service.unlock(id,input,actor,RequestMetadata.from(request)));}
 @PutMapping("/{id}/roles") public ApiResponse<Account> roles(@PathVariable UUID id,@Valid @RequestBody RolesInput input,@AuthenticationPrincipal AuthenticatedUser actor,HttpServletRequest request){return ApiResponse.success(service.roles(id,input,actor,RequestMetadata.from(request)));}
}
