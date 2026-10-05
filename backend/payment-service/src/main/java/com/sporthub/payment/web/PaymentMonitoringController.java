package com.sporthub.payment.web;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.common.exception.*;
import com.sporthub.payment.web.PaymentDtos.Payment;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/payments")
public class PaymentMonitoringController {
 private final PaymentRepository repo;private final RemoteIdentity identity;
 public PaymentMonitoringController(PaymentRepository repo,RemoteIdentity identity){this.repo=repo;this.identity=identity;}
 @GetMapping public ApiResponse<List<Payment>> list(@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String purpose,HttpServletRequest r){identity.current(r).requireRole("ADMIN");String pattern="%"+(q==null?"":q.trim())+"%";return ApiResponse.success(repo.list("SELECT * FROM payment_orders WHERE (id::text ILIKE ? OR booking_id::text ILIKE ? OR provider_reference ILIKE ?) AND (?::varchar IS NULL OR status=?::varchar) AND (?::varchar IS NULL OR purpose=?::varchar) ORDER BY created_at DESC LIMIT 200",pattern,pattern,pattern,status,status,purpose,purpose));}
 @GetMapping("/{id}") public ApiResponse<Map<String,Object>> detail(@PathVariable UUID id,HttpServletRequest r){identity.current(r).requireRole("ADMIN");var payment=repo.find(id);return ApiResponse.success(Map.of("payment",payment,"audit",repo.jdbc().queryForList("SELECT id,actor_id,action,details,created_at FROM payment_audit WHERE payment_id=? ORDER BY created_at",id),"callbacks",repo.jdbc().queryForList("SELECT provider,transaction_id,fingerprint,body,received_at FROM provider_callbacks WHERE payment_id=? ORDER BY received_at",id),"requests",repo.jdbc().queryForList("SELECT payer_id,idempotency_key,created_at FROM payment_requests WHERE payment_id=? ORDER BY created_at",id),"refunds",repo.jdbc().queryForList("SELECT * FROM refunds WHERE payment_id=? ORDER BY created_at",id),"escrow",repo.jdbc().queryForList("SELECT * FROM transfer_escrow WHERE payment_id=?",id)));}
 @GetMapping("/statistics") public ApiResponse<Map<String,Object>> statistics(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,HttpServletRequest r){identity.current(r).requireRole("ADMIN");Instant end=to==null?Instant.now():to,start=from==null?end.minusSeconds(30*86400L):from;if(!start.isBefore(end)||Duration.between(start,end).toDays()>366)throw new IllegalArgumentException("Invalid report range");return ApiResponse.success(Map.of("from",start,"to",end,"dateBasis","createdAt","orders",repo.jdbc().queryForList("SELECT purpose,status,currency,count(*) AS count,COALESCE(sum(amount),0) AS amount FROM payment_orders WHERE created_at>=? AND created_at<? GROUP BY purpose,status,currency ORDER BY purpose,status",Timestamp.from(start),Timestamp.from(end)),"escrow",repo.jdbc().queryForList("SELECT state,currency,count(*) AS count,COALESCE(sum(amount),0) AS amount FROM transfer_escrow WHERE created_at>=? AND created_at<? GROUP BY state,currency",Timestamp.from(start),Timestamp.from(end)),"refundReviews",repo.jdbc().queryForList("SELECT state,count(*) AS count FROM refunds WHERE created_at>=? AND created_at<? GROUP BY state",Timestamp.from(start),Timestamp.from(end))));}
}
