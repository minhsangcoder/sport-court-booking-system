package com.sporthub.booking.web;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.service.BookingDependencies;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.common.exception.*;
import static com.sporthub.booking.web.BookingDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
@RestController @RequestMapping("/api/v1")
public class BookingMonitoringController {
 private final BookingRepository repo;private final RemoteIdentity identity;private final BookingDependencies dependencies;
 public BookingMonitoringController(BookingRepository repo,RemoteIdentity identity,BookingDependencies dependencies){this.repo=repo;this.identity=identity;this.dependencies=dependencies;}
 @GetMapping("/admin/bookings") public ApiResponse<List<Booking>> list(@RequestParam(required=false) UUID id,@RequestParam(required=false) UUID facilityId,@RequestParam(required=false) String status,HttpServletRequest r){identity.current(r).requireRole("ADMIN");return ApiResponse.success(repo.list("SELECT * FROM bookings WHERE (?::uuid IS NULL OR id=?::uuid) AND (?::uuid IS NULL OR facility_id=?::uuid) AND (?::varchar IS NULL OR status=?::varchar) ORDER BY created_at DESC LIMIT 200",id,id,facilityId,facilityId,status,status));}
 @GetMapping("/admin/bookings/{id}") public ApiResponse<Detail> detail(@PathVariable UUID id,HttpServletRequest r){identity.current(r).requireRole("ADMIN");return ApiResponse.success(new Detail(repo.find(id),repo.history(id),null,null));}
 @GetMapping("/admin/bookings/reconciliation") public ApiResponse<List<Map<String,Object>>> reconciliation(HttpServletRequest r){identity.current(r).requireRole("ADMIN");return ApiResponse.success(repo.jdbc().queryForList("SELECT * FROM payment_reconciliation ORDER BY created_at DESC LIMIT 200"));}
 @GetMapping("/admin/bookings/statistics") public ApiResponse<Map<String,Object>> statistics(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,HttpServletRequest r){identity.current(r).requireRole("ADMIN");var range=range(from,to);return ApiResponse.success(Map.of("from",range[0],"to",range[1],"dateBasis","createdAt","states",repo.jdbc().queryForList("SELECT status,count(*) AS count,COALESCE(sum(amount),0) AS booking_value FROM bookings WHERE created_at>=? AND created_at<? GROUP BY status ORDER BY status",Timestamp.from(range[0]),Timestamp.from(range[1]))));}
 @GetMapping("/bookings/reports/facility/{id}") public ApiResponse<Map<String,Object>> ownerReport(@PathVariable UUID id,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,HttpServletRequest r){var caller=identity.current(r);caller.requireRole("OWNER");dependencies.facilityPermission(id,caller,r.getHeader("Authorization"),"OWNER_REPORT");var range=range(from,to);var rows=repo.jdbc().queryForList("SELECT court_id,status,count(*) AS count,COALESCE(sum(amount),0) AS booking_value,COALESCE(sum(EXTRACT(EPOCH FROM ends_at-starts_at)/60),0) AS reserved_minutes FROM bookings WHERE facility_id=? AND starts_at>=? AND starts_at<? GROUP BY court_id,status ORDER BY court_id,status",id,Timestamp.from(range[0]),Timestamp.from(range[1]));return ApiResponse.success(Map.of("facilityId",id,"from",range[0],"to",range[1],"dateBasis","startsAt","courts",rows,"financialPolicy","BLOCKED_RULE: booking value is not a settled owner payout"));}
 private Instant[] range(Instant from,Instant to){Instant end=to==null?Instant.now():to,start=from==null?end.minusSeconds(30*86400L):from;if(!start.isBefore(end)||Duration.between(start,end).toDays()>366)throw new IllegalArgumentException("Report range must be positive and no more than 366 days");return new Instant[]{start,end};}
}
