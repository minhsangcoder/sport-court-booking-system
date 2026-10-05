package com.sporthub.identity.service;

import com.sporthub.identity.domain.*;
import com.sporthub.identity.security.AuthenticatedUser;
import com.sporthub.common.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.*;

@Service @Transactional(readOnly=true)
public class StaffBindingService {
 public static final Set<String> PERMISSIONS=Set.of("BOOKING_READ","BOOKING_CREATE_COUNTER","BOOKING_CHECK_IN","BOOKING_COMPLETE","SCHEDULE_READ");
 private final JdbcTemplate jdbc;private final RestClient facility;
 public StaffBindingService(JdbcTemplate jdbc,@Value("${FACILITY_SERVICE_URL:http://localhost:8082}") String url){this.jdbc=jdbc;facility=RestClient.create(url);}
 public record Input(@NotBlank String identifier,@NotEmpty Set<@NotBlank String> permissions){}
 public record Binding(UUID id,UUID facilityId,UUID userId,String fullName,String email,boolean active,List<String> permissions){}
 private void owner(UUID id,AuthenticatedUser user,String token){if(!user.roles().contains(Role.OWNER))throw new ForbiddenException("Owner role required");try{facility.get().uri("/api/v1/owner/facilities/"+id).header("Authorization",token).retrieve().toBodilessEntity();}catch(org.springframework.web.client.HttpClientErrorException ex){throw new ForbiddenException("Facility is outside your ownership");}}
 public List<Binding> list(UUID id,AuthenticatedUser user,String token){owner(id,user,token);return bindings(id);}
 private List<Binding> bindings(UUID id){return jdbc.query("SELECT b.*,p.full_name,u.email FROM staff_facility_bindings b JOIN users u ON u.id=b.staff_user_id JOIN user_profiles p ON p.user_id=u.id WHERE b.facility_id=? ORDER BY p.full_name",(r,n)->{UUID binding=r.getObject("id",UUID.class);return new Binding(binding,id,r.getObject("staff_user_id",UUID.class),r.getString("full_name"),r.getString("email"),r.getBoolean("is_active"),jdbc.queryForList("SELECT permission FROM staff_permissions WHERE binding_id=? ORDER BY permission",String.class,binding));},id);}
 @Transactional public Binding assign(UUID id,Input input,AuthenticatedUser user,String token){owner(id,user,token);if(!PERMISSIONS.containsAll(input.permissions()))throw new IllegalArgumentException("Unsupported Staff permission");
  var users=jdbc.queryForList("SELECT id FROM users WHERE (lower(email)=? OR phone=?) AND status='ACTIVE'",input.identifier().trim().toLowerCase(Locale.ROOT),input.identifier().trim());if(users.size()!=1)throw new ResourceNotFoundException("Active staff account not found");UUID staff=(UUID)users.get(0).get("id");
  if(jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id=? AND role='STAFF'",Integer.class,staff)==0)throw new ConflictException("Account needs the approved STAFF role before assignment");
  jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,id.toString());
  UUID binding=UUID.randomUUID();jdbc.update("INSERT INTO staff_facility_bindings(id,staff_user_id,facility_id,assigned_by) VALUES(?,?,?,?) ON CONFLICT(staff_user_id,facility_id) DO UPDATE SET is_active=TRUE,assigned_by=EXCLUDED.assigned_by,updated_at=NOW()",binding,staff,id,user.userId());
  binding=jdbc.queryForObject("SELECT id FROM staff_facility_bindings WHERE staff_user_id=? AND facility_id=?",UUID.class,staff,id);jdbc.update("DELETE FROM staff_permissions WHERE binding_id=?",binding);
  for(String permission:input.permissions())jdbc.update("INSERT INTO staff_permissions(binding_id,permission) VALUES(?,?)",binding,permission);
  jdbc.update("INSERT INTO audit_log(user_id,action,entity_type,entity_id,new_value) VALUES(?,'STAFF_ASSIGNED','STAFF_BINDING',?,?::jsonb)",user.userId(),binding,"{\"facilityId\":\""+id+"\"}");
  final UUID result=binding;return bindings(id).stream().filter(b->b.id().equals(result)).findFirst().orElseThrow();
 }
 @Transactional public void revoke(UUID id,UUID binding,AuthenticatedUser user,String token){owner(id,user,token);int changed=jdbc.update("UPDATE staff_facility_bindings SET is_active=FALSE,updated_at=NOW() WHERE id=? AND facility_id=?",binding,id);if(changed==0)throw new ResourceNotFoundException("Staff binding not found");jdbc.update("INSERT INTO audit_log(user_id,action,entity_type,entity_id) VALUES(?,'STAFF_REVOKED','STAFF_BINDING',?)",user.userId(),binding);}
}
