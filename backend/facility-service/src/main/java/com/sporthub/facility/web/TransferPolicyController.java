package com.sporthub.facility.web;
import com.sporthub.facility.service.FacilityService;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1")
public class TransferPolicyController {
 public record Policy(boolean configured,boolean enabled,int minLeadSeconds){}
 public record PolicyInput(boolean enabled,@Min(0) @Max(7776000) int minLeadSeconds){}
 private final FacilityService facilities;private final RemoteIdentity identity;private final JdbcTemplate jdbc;
 public TransferPolicyController(FacilityService facilities,RemoteIdentity identity,JdbcTemplate jdbc){this.facilities=facilities;this.identity=identity;this.jdbc=jdbc;}
 @GetMapping("/facilities/{id}/transfer-policy") public ApiResponse<Policy> publicPolicy(@PathVariable UUID id){facilities.publicDetail(id);return ApiResponse.success(read(id));}
 @GetMapping("/owner/facilities/{id}/transfer-policy") public ApiResponse<Policy> ownPolicy(@PathVariable UUID id,HttpServletRequest r){facilities.ownedDetail(id,identity.current(r));return ApiResponse.success(read(id));}
 @PutMapping("/owner/facilities/{id}/transfer-policy") @Transactional public ApiResponse<Policy> update(@PathVariable UUID id,@Valid @RequestBody PolicyInput input,HttpServletRequest r){var caller=identity.current(r);facilities.ownedDetail(id,caller);
  jdbc.update("INSERT INTO facility_transfer_policies(facility_id,enabled,min_lead_seconds,updated_by) VALUES(?,?,?,?) ON CONFLICT(facility_id) DO UPDATE SET enabled=EXCLUDED.enabled,min_lead_seconds=EXCLUDED.min_lead_seconds,updated_by=EXCLUDED.updated_by,updated_at=NOW()",id,input.enabled(),input.minLeadSeconds(),caller.id());
  jdbc.update("INSERT INTO facility_audit(id,facility_id,actor_id,action,details) VALUES(?,?,?,'TRANSFER_POLICY_UPDATED',jsonb_build_object('enabled',?::boolean,'minLeadSeconds',?::int))",UUID.randomUUID(),id,caller.id(),input.enabled(),input.minLeadSeconds());return ApiResponse.success(read(id));}
 private Policy read(UUID id){return jdbc.query("SELECT * FROM facility_transfer_policies WHERE facility_id=?",(r,n)->new Policy(true,r.getBoolean("enabled"),r.getInt("min_lead_seconds")),id).stream().findFirst().orElse(new Policy(false,false,0));}
}
