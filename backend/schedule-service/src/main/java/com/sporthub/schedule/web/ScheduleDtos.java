package com.sporthub.schedule.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class ScheduleDtos {
    private ScheduleDtos() {}
    public record Interval(@Min(1) @Max(7) int dayOfWeek,@NotNull LocalTime opensAt,
        @NotNull LocalTime closesAt,@Min(5) @Max(720) int slotMinutes) {}
    public record HoursInput(UUID courtId,@NotNull @Size(max=100) List<@Valid Interval> intervals) {}
    public record Hours(UUID id,UUID facilityId,UUID courtId,int dayOfWeek,LocalTime opensAt,
        LocalTime closesAt,int slotMinutes,long version) {}
    public record ExceptionInput(UUID courtId,@NotNull LocalDate date,
        @NotNull @Pattern(regexp="CLOSED|SPECIAL_HOURS") String type,LocalTime opensAt,
        LocalTime closesAt,@NotBlank @Size(max=500) String reason) {}
    public record ExceptionView(UUID id,UUID facilityId,UUID courtId,LocalDate date,String type,
        LocalTime opensAt,LocalTime closesAt,String reason) {}
    public record PriceInput(UUID courtId,UUID sportCategoryId,@Min(1) @Max(7) Integer dayOfWeek,
        LocalDate specificDate,@NotNull LocalTime startsAt,@NotNull LocalTime endsAt,
        @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=10,fraction=2) BigDecimal pricePerSlot,
        @Min(0) @Max(10000) int priority,@NotBlank @Size(max=200) String label,
        @NotNull LocalDate effectiveFrom,LocalDate effectiveTo,@Pattern(regexp="VND") @NotNull String currency) {}
    public record PriceRule(UUID id,UUID facilityId,UUID courtId,UUID sportCategoryId,Integer dayOfWeek,
        LocalDate specificDate,LocalTime startsAt,LocalTime endsAt,BigDecimal pricePerSlot,int priority,
        String label,LocalDate effectiveFrom,LocalDate effectiveTo,String currency,boolean active,long version) {}
    public record QuoteInput(@NotNull UUID courtId,@NotNull Instant startsAt,@NotNull Instant endsAt) {}
    public record Slot(Instant startsAt,Instant endsAt,String state,String reason,BigDecimal amount,
        String currency,UUID ruleId,long ruleVersion,String ruleLabel) {}
    public record Preview(UUID facilityId,UUID courtId,LocalDate date,String timezone,List<Slot> slots) {}
    public record Quote(UUID facilityId,UUID courtId,Instant startsAt,Instant endsAt,BigDecimal amount,
        String currency,String timezone,List<Slot> segments) {}
}
