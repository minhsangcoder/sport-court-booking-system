package com.sporthub.identity.web.dto;
import com.sporthub.identity.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
public final class AdminAccountDtos {
 private AdminAccountDtos(){}
 public record Account(UUID id,String fullName,String email,String phone,boolean emailVerified,boolean phoneVerified,
  Set<Role> roles,AccountStatus status,Instant createdAt,Instant lastLoginAt,Instant lockedAt,Instant lockedUntil,String lockReason){}
 public record LockInput(@NotBlank @Size(max=500) String reason,@Pattern(regexp="SEVEN_DAYS|THIRTY_DAYS|PERMANENT") @NotNull String duration){}
 public record Reason(@NotBlank @Size(max=500) String reason){}
 public record RolesInput(@NotEmpty Set<@NotNull Role> roles,@NotBlank @Size(max=500) String reason){}
 public record Session(UUID id,String deviceInfo,String ipAddress,Instant createdAt,Instant expiresAt,boolean revoked){}
 public record Audit(UUID id,UUID actorId,String action,JsonNode oldValue,JsonNode newValue,Instant createdAt){}
 public record Detail(Account account,List<Session> sessions,List<Audit> audit){}
}
