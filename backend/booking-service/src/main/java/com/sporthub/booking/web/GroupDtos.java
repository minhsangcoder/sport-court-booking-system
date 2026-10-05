package com.sporthub.booking.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class GroupDtos {
    private GroupDtos() {}
    public record CreateGroup(@NotNull UUID holdId,@Size(max=180) String name,
                              @Min(1) @Max(50) int maxMembers) {}
    public record JoinGroup(@NotBlank @Size(max=500) String code) {}
    public record Allocation(@NotNull UUID memberId,@NotNull @DecimalMin("0") @Digits(integer=10,fraction=0) BigDecimal amount) {}
    public record Split(@NotNull @Pattern(regexp="EQUAL|CUSTOM") String mode,
                        @Valid @Size(max=50) List<Allocation> allocations) {}
    public record Member(UUID id,UUID userId,String displayName,boolean active,BigDecimal amountDue,
                         BigDecimal amountPaid,String paymentState,Instant paymentRequestedAt) {}
    public record Group(UUID id,UUID ownerId,String name,String state,Instant deadline,int maxMembers,
                        boolean allocationsLocked,BookingDtos.Booking booking,List<Member> members,BigDecimal totalPaid) {}
    public record Invite(String code,String path,String qrSvg,String qrPngDataUrl,Instant expiresAt) {}
}
