package com.sporthub.schedule.service;

import static com.sporthub.schedule.web.ScheduleDtos.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporthub.common.exception.ConflictException;
import com.sporthub.common.schedule.ConfigurationChanges;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import com.sporthub.schedule.repository.ScheduleRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FlexibleBookingPolicies {
  private final ScheduleRepository repo;
  private final ScheduleAccess access;
  private final ConfigurationChanges changes;
  private final FacilityClient facilities;
  private final ScheduleReads reads;
  private final ObjectMapper json;
  private final Clock clock;
  private final int advanceDays;
  private final FlexiblePricingEngine engine = new FlexiblePricingEngine();

  public FlexibleBookingPolicies(
      ScheduleRepository repo,
      ScheduleAccess access,
      ConfigurationChanges changes,
      FacilityClient facilities,
      ScheduleReads reads,
      ObjectMapper json,
      Clock clock,
      @Value("${booking.advance-days:90}") int advanceDays) {
    this.repo = repo;
    this.access = access;
    this.changes = changes;
    this.facilities = facilities;
    this.reads = reads;
    this.json = json;
    this.clock = clock;
    this.advanceDays = advanceDays;
  }

  public BookingPolicy read(UUID facility, Caller caller, String token) {
    access.reader(facility, caller, token);
    return repo.bookingPolicy(facility);
  }

  @Transactional
  public BookingPolicy publish(
      UUID facility, BookingPolicy input, String key, Caller caller, String token) {
    if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,100}"))
      throw new IllegalArgumentException("Cần mã yêu cầu hợp lệ.");
    if (input.version() < 0
        || input.selectionStepMinutes() != 30
        || input.minimumMinutes() != 30
        || input.maximumMinutes() != null)
      throw new IllegalArgumentException(
          "Giờ tự chọn dùng bước 30 phút, tối thiểu 30 phút và không giới hạn tối đa.");
    access.owner(facility, caller, token);
    String fingerprint = Signatures.sha256(facility + "|" + input);
    var previous =
        repo.jdbc()
            .queryForList(
                "SELECT snapshot,fingerprint FROM facility_booking_policy_versions WHERE actor_id=?"
                    + " AND idempotency_key=?",
                caller.id(),
                key);
    if (!previous.isEmpty()) {
      if (!fingerprint.equals(previous.getFirst().get("fingerprint")))
        throw new ConflictException("Mã yêu cầu đã dùng cho cấu hình khác.");
      return decode(previous.getFirst().get("snapshot").toString());
    }
    var current = repo.bookingPolicy(facility);
    if (current.version() != input.version())
      throw new ConflictException("Chính sách giờ đặt đã thay đổi. Vui lòng tải lại.");
    changes.begin(facility);
    if (input.enabled()) requireHourlyCoverage(facility, token);
    var result = new BookingPolicy(input.enabled(), current.version() + 1, 30, 30, null);
    repo.jdbc()
        .update(
            """
            INSERT INTO facility_booking_policies(facility_id,enabled,version,updated_by,updated_at)
            VALUES(?,?,?,?,?) ON CONFLICT(facility_id) DO UPDATE SET enabled=EXCLUDED.enabled,
            version=EXCLUDED.version,updated_by=EXCLUDED.updated_by,updated_at=EXCLUDED.updated_at
            """,
            facility,
            result.enabled(),
            result.version(),
            caller.id(),
            java.sql.Timestamp.from(clock.instant()));
    repo.jdbc()
        .update(
            """
INSERT INTO facility_booking_policy_versions(facility_id,version,snapshot,actor_id,idempotency_key,fingerprint)
VALUES(?,?,?::jsonb,?,?,?)
""",
            facility,
            result.version(),
            encode(result),
            caller.id(),
            key,
            fingerprint);
    access.audit(facility, caller, "FLEXIBLE_BOOKING_POLICY_PUBLISHED", result);
    return result;
  }

  private void requireHourlyCoverage(UUID facility, String token) {
    var courts =
        facilities.courts(facility, token).stream()
            .filter(FacilityClient.Context::enabled)
            .toList();
    var hours = repo.hours(facility);
    var exceptions = repo.exceptions(facility);
    var rules = repo.rules(facility);
    boolean bookable = false;
    for (var context : courts) {
      LocalDate today = LocalDate.now(clock.withZone(context.timezone()));
      var pricedContext =
          new FacilityClient.Context(
              context.facilityId(),
              context.courtId(),
              context.sportCategoryId(),
              context.timezone(),
              true,
              context.status(),
              List.of(),
              context.courtTypeId());
      for (int offset = 0; offset <= advanceDays; offset++) {
        LocalDate day = today.plusDays(offset);
        var calendar = reads.calendar(context.courtId(), day, hours, exceptions);
        if (calendar.closed()
            || engine.windows(calendar).stream()
                .noneMatch(w -> Duration.between(w.opensAt(), w.closesAt()).toMinutes() >= 60))
          continue;
        var preview =
            engine.preview(
                pricedContext, day, calendar, rules, new BookingPolicy(true, 0, 30, 30, null));
        var future =
            preview.slots().stream().filter(s -> s.endsAt().isAfter(clock.instant())).toList();
        if (future.stream().anyMatch(s -> !s.state().equals("ELIGIBLE")))
          throw new ConflictException(
              "Cần cấu hình đủ giá theo giờ cho sân "
                  + context.courtId()
                  + " ngày "
                  + day
                  + " trước khi bật giờ tự chọn.");
        bookable |= !future.isEmpty();
      }
    }
    if (!bookable)
      throw new ConflictException("Chưa có sân và lịch mở cửa phù hợp để bật giờ tự chọn.");
  }

  private String encode(BookingPolicy policy) {
    try {
      return json.writeValueAsString(policy);
    } catch (Exception error) {
      throw new IllegalStateException("Không ghi được phiên bản chính sách giờ đặt.", error);
    }
  }

  private BookingPolicy decode(String snapshot) {
    try {
      return json.readValue(snapshot, BookingPolicy.class);
    } catch (Exception error) {
      throw new IllegalStateException("Không đọc được phiên bản chính sách giờ đặt.", error);
    }
  }
}
