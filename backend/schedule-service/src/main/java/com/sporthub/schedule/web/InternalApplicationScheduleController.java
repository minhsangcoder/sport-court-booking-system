package com.sporthub.schedule.web;
import com.sporthub.schedule.service.*;
import com.sporthub.common.security.ServiceCalls;
import com.sporthub.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
@RestController
public class InternalApplicationScheduleController {
 private final ScheduleService service;private final ObjectMapper json;private final String secret;
 public InternalApplicationScheduleController(ScheduleService service,ObjectMapper json,@Value("${SERVICE_CALL_SECRET:}") String secret){this.service=service;this.json=json;this.secret=secret;}
 @PostMapping("/api/v1/internal/schedules/application-snapshot") public ApiResponse<?> snapshot(@RequestBody String body,HttpServletRequest r)throws Exception{
  ServiceCalls.verify(secret,r.getMethod(),r.getRequestURI(),r.getHeader("X-Service-Time"),body,r.getHeader("X-Service-Signature"));var data=json.readTree(body);UUID facility=UUID.fromString(data.path("facilityId").asText()),application=UUID.fromString(data.path("applicationId").asText());
  if(data.path("release").asBoolean())return ApiResponse.success(service.releaseApplication(facility,application));
  var contexts=new ArrayList<FacilityClient.Context>();for(var c:data.path("courts")){var windows=new ArrayList<FacilityClient.Window>();for(var m:c.path("maintenance"))windows.add(new FacilityClient.Window(Instant.parse(m.path("startsAt").asText()),Instant.parse(m.path("endsAt").asText())));contexts.add(new FacilityClient.Context(facility,UUID.fromString(c.path("courtId").asText()),UUID.fromString(c.path("sportCategoryId").asText()),ZoneId.of(data.path("timezone").asText()),c.path("enabled").asBoolean(),"DRAFT",windows));}
  if(contexts.isEmpty()||contexts.size()>200)throw new IllegalArgumentException("Choose between 1 and 200 courts");return ApiResponse.success(service.freezeApplication(facility,application,contexts));
 }
}
