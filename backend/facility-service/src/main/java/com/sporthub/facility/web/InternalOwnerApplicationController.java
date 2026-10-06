package com.sporthub.facility.web;
import com.sporthub.facility.service.*;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.*;
import com.sporthub.common.exception.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/internal/owner-applications")
public class InternalOwnerApplicationController {
 private final OwnerSignupFacilityService signup;private final String secret;private final ObjectMapper json;private final FacilityService facilities;private final FacilityReviewService reviews;private final jakarta.validation.Validator validator;
 public InternalOwnerApplicationController(@Value("${SERVICE_CALL_SECRET:}") String secret,ObjectMapper json,FacilityService facilities,FacilityReviewService reviews,jakarta.validation.Validator validator,OwnerSignupFacilityService signup){this.signup=signup;this.secret=secret;this.json=json;this.facilities=facilities;this.reviews=reviews;this.validator=validator;}
 @PostMapping("/{applicationId}/{command}") public ApiResponse<?> command(@PathVariable UUID applicationId,@PathVariable String command,@RequestBody String body,HttpServletRequest r)throws Exception{
  ServiceCalls.verify(secret,r.getMethod(),r.getRequestURI(),r.getHeader("X-Service-Time"),body,r.getHeader("X-Service-Signature"));var input=json.readTree(body);UUID facility=UUID.fromString(input.path("facilityId").asText());UUID user=UUID.fromString(input.path("userId").asText());
  if(command.equals("signup-validate")){signup.validate(input);return ApiResponse.success(Map.of("valid",true));}
  if(command.equals("create")){var data=json.treeToValue(input.path("facility"),FacilityDtos.FacilityInput.class);if(!validator.validate(data).isEmpty())throw new IllegalArgumentException("Invalid first facility");return ApiResponse.success(facilities.createFirst(applicationId,facility,user,data));}
  if(!applicationId.equals(facilities.applicationId(facility))||!user.equals(facilities.find(facility).getOwnerId()))throw new ForbiddenException("Application facility binding differs");
  var applicant=new RemoteIdentity.Caller(user,"Applicant",Set.of("CUSTOMER"),Map.of(facility,Set.of("APPLICATION_READ","APPLICATION_EDIT")));
  if(command.equals("signup-prepare")){signup.prepare(applicationId,facility,user,input);return ApiResponse.success(Map.of("prepared",true));}
  return switch(command){case "own-view"->ApiResponse.success(Map.of("facility",facilities.ownedDetail(facility,applicant),"courts",facilities.ownedCourts(facility,applicant),"reviews",reviews.ownHistory(facility,applicant)));case "submit"->ApiResponse.success(reviews.submitFirst(facility,applicant,r.getHeader("Authorization")));case "view"->{UUID actor=UUID.fromString(input.path("actorId").asText());yield ApiResponse.success(reviews.detail(facility,new RemoteIdentity.Caller(actor,"Reviewer",Set.of("ADMIN"),Map.of())));}case "APPROVE","REJECT","SUPPLEMENT_REQUIRED"->{UUID actor=UUID.fromString(input.path("actorId").asText());yield ApiResponse.success(reviews.decideFirst(facility,command,input.path("reason").asText(),new RemoteIdentity.Caller(actor,"Reviewer",Set.of("ADMIN"),Map.of())));}default->throw new IllegalArgumentException("Unsupported application command");};
 }
}
