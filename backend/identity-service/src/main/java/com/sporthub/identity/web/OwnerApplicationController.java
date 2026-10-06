package com.sporthub.identity.web;
import static com.sporthub.identity.web.dto.OwnerApplicationDtos.*;
import com.sporthub.identity.service.OwnerApplicationService;
import com.sporthub.identity.security.AuthenticatedUser;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.ServiceCalls;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1")
public class OwnerApplicationController {
 private final OwnerApplicationService service;private final String secret;private final ObjectMapper json;
 public OwnerApplicationController(OwnerApplicationService service,@Value("${SERVICE_CALL_SECRET:}") String secret,ObjectMapper json){this.service=service;this.secret=secret;this.json=json;}
 @ModelAttribute public void preventCaching(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
 @GetMapping("/owner-applications") public ApiResponse<List<Summary>> own(@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.success(service.own(actor));}
 @PostMapping("/owner-applications") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public ApiResponse<Summary> create(@Valid @RequestBody Create input,@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.created(service.create(input,actor));}
 @GetMapping("/owner-applications/{id}") public ApiResponse<Detail> detail(@PathVariable UUID id,@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.success(service.detail(id,actor,false));}
 @PostMapping("/owner-applications/{id}/initialize") public ApiResponse<Summary> initialize(@PathVariable UUID id,@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.success(service.initialize(id,actor));}
 @PutMapping("/owner-applications/{id}/legal") public ApiResponse<Summary> legal(@PathVariable UUID id,@Valid @RequestBody Legal input,@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.success(service.save(id,input,actor));}
 @PostMapping("/owner-applications/{id}/submit") public ApiResponse<Summary> submit(@PathVariable UUID id,@AuthenticationPrincipal AuthenticatedUser actor,HttpServletRequest r){return ApiResponse.success(service.submit(id,actor,r.getHeader("Authorization")));}
 @GetMapping("/admin/owner-applications") public ApiResponse<List<Summary>> search(@RequestParam(required=false) String q,@RequestParam(required=false) String state,@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.success(service.search(q,state,actor));}
 @GetMapping("/admin/owner-applications/page") public ApiResponse<SearchPage> page(@ModelAttribute SearchCriteria criteria,@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.success(service.search(criteria,actor));}
 @GetMapping("/admin/owner-applications/{id}") public ApiResponse<Detail> adminDetail(@PathVariable UUID id,@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.success(service.detail(id,actor,true));}
 @GetMapping("/admin/owner-applications/{id}/history") public ApiResponse<HistoryPage> history(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.success(service.history(id,page,size,actor));}
 @PostMapping("/admin/owner-applications/{id}/decision") public ApiResponse<Summary> decide(@PathVariable UUID id,@Valid @RequestBody Decision input,@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.success(service.decide(id,input,actor));}
 @PostMapping("/admin/owner-applications/{id}/retry") public ApiResponse<Summary> retry(@PathVariable UUID id,@AuthenticationPrincipal AuthenticatedUser actor){return ApiResponse.success(service.retry(id,actor));}
 @PostMapping("/internal/owner-applications/committed") public ApiResponse<List<UUID>> committed(@RequestBody String body,HttpServletRequest r)throws Exception{ServiceCalls.verify(secret,r.getMethod(),r.getRequestURI(),r.getHeader("X-Service-Time"),body,r.getHeader("X-Service-Signature"));var ids=new ArrayList<UUID>();for(var id:json.readTree(body).path("applicationIds"))ids.add(UUID.fromString(id.asText()));return ApiResponse.success(service.committed(ids));}
}
