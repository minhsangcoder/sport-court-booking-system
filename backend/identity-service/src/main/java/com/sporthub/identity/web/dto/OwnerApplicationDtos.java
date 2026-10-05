package com.sporthub.identity.web.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import java.time.Instant;
public final class OwnerApplicationDtos {
 private OwnerApplicationDtos(){}
 public record Legal(@NotBlank @Size(max=180) String representativeName,@NotBlank @Size(max=50) String identityNumber,
  @NotBlank @Size(max=180) String businessName,@Size(max=100) String businessLicense,@Size(max=50) String taxCode,
  @NotBlank @Size(max=100) String bankName,@NotBlank @Size(max=100) String bankAccountHolder,@NotBlank @Size(max=50) String bankAccountNumber){}
 public record Facility(@NotBlank @Size(max=180) String name,@NotBlank @Size(max=30) String phone,
  @NotBlank @Size(max=500) String addressLine,@NotBlank @Size(max=100) String province,@NotBlank @Size(max=100) String district,@NotBlank @Size(max=100) String ward,
  @Size(max=5000) String description,@NotBlank @Size(max=80) String timezone,
  @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,@NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
  @Size(max=30) Set<@NotBlank @Size(max=100) String> amenities){}
 public record Create(@NotNull @Valid Legal legal,@NotNull @Valid Facility facility){}
 public record Decision(@Pattern(regexp="APPROVE|REJECT|SUPPLEMENT_REQUIRED") @NotBlank String action,@NotBlank @Size(max=1200) String reason,
  @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal commissionPercent){}
 public record Summary(UUID id,UUID userId,UUID facilityId,String businessName,String facilityName,String state,Instant submittedAt,String reason,BigDecimal commissionPercent,String lastError){}
 public record Detail(Summary application,Legal legal,JsonNode facility,List<Map<String,Object>> history,List<Map<String,Object>> audit){}
}
