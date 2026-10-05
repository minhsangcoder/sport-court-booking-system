package com.sporthub.booking.web;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class BookingDtos {
    private BookingDtos() {}
    public record HoldInput(@NotNull UUID courtId,@NotNull Instant startsAt,@NotNull Instant endsAt,
        @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal expectedAmount) {}
    public record Hold(UUID id,UUID courtId,UUID facilityId,UUID holderId,Instant startsAt,Instant endsAt,
        Instant expiresAt,String state,JsonNode quote) {}
    public record CreateInput(@NotNull UUID holdId,@Size(max=180) String guestName,@Size(max=30) String guestPhone) {}
    public record CancelInput(@NotBlank @Size(max=500) String reason) {}
    public record CheckinInput(@NotBlank @Size(max=500) String token) {}
    public record Booking(UUID id,UUID facilityId,UUID courtId,UUID customerId,UUID currentHolderId,UUID createdBy,
        Instant startsAt,Instant endsAt,String status,String source,String guestName,String guestPhone,
        BigDecimal amount,String currency,JsonNode priceSnapshot,Instant holdExpiresAt,UUID paymentId,
        Instant paidAt,Instant checkedInAt,Instant completedAt,long version,int checkinTokenVersion) {}
    public record History(UUID id,String action,UUID actorId,JsonNode details,Instant createdAt) {}
    public record Detail(Booking booking,List<History> history,String checkinToken,String checkinQrSvg) {}
    public record Payable(UUID bookingId,UUID payerId,BigDecimal amount,String currency,Instant expiresAt,String purpose,UUID memberId) {}
}
