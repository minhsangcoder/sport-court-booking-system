package com.sporthub.facility.web;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
public final class FacilityDtos {
 private FacilityDtos(){}
 public record FacilityInput(@NotBlank @Size(max=180) String name,@NotBlank @Size(max=30) String phone,
  @NotBlank @Size(max=500) String addressLine,@NotBlank @Size(max=100) String province,
  @NotBlank @Size(max=100) String district,@NotBlank @Size(max=100) String ward,
  @Size(max=5000) String description,@NotBlank @Size(max=80) String timezone,
  @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,@DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
  @Size(max=30) Set<@NotBlank @Size(max=100) String> amenities){}
 public record FacilityView(UUID id,UUID ownerId,String name,String phone,String addressLine,String province,String district,String ward,
  String description,String timezone,BigDecimal latitude,BigDecimal longitude,String status,Set<String> amenities,long version){}
 public record CourtInput(@NotBlank @Size(max=50) String code,@NotBlank @Size(max=180) String name,@NotNull UUID sportCategoryId,
  @Size(max=5000) String description,boolean enabled){}
 public record CourtView(UUID id,UUID facilityId,UUID sportCategoryId,String code,String name,String description,boolean enabled,long version){}
 public record CourtContext(FacilityView facility,CourtView court,List<MaintenanceView> maintenance){}
 public record MaintenanceInput(@NotNull @FutureOrPresent Instant startsAt,@NotNull @Future Instant endsAt,@NotBlank @Size(max=1200) String reason){}
 public record MaintenanceView(UUID id,UUID courtId,Instant startsAt,Instant endsAt,String reason,boolean cancelled){}
 public record CategoryInput(@NotBlank @Size(max=100) String name,boolean active){}
 public record CategoryView(UUID id,String name,boolean active){}
}
