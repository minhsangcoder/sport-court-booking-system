package com.sporthub.facility.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporthub.common.event.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.schedule.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import static com.sporthub.common.schedule.ConfigurationGuardProtocol.*;
import static com.sporthub.facility.web.FacilityDtos.*;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @Transactional(readOnly=true)
public class FacilityStatusService {
 public record Preview(UUID facilityId,String status,long version,List<Reservation> reservations,String impactFingerprint,String bookingPolicy){}
 public record Decision(@NotNull @Pattern(regexp="SUSPEND|RESTORE") String action,@NotBlank @Size(min=20,max=2000) String reason,@NotNull @Min(0) Long expectedVersion,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String impactFingerprint){}
 private final FacilityService facilities;private final ConfigurationChanges changes;private final ConfigurationGuardTransport transport;private final OwnerApplicationGate gate;private final JdbcTemplate jdbc;private final ObjectMapper json;private final ReliableOutbox outbox;private final jakarta.validation.Validator validator;
 @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entities;
 public FacilityStatusService(FacilityService facilities,ConfigurationChanges changes,ConfigurationGuardTransport transport,OwnerApplicationGate gate,JdbcTemplate jdbc,ObjectMapper json,ReliableOutbox outbox,jakarta.validation.Validator validator){this.facilities=facilities;this.changes=changes;this.transport=transport;this.gate=gate;this.jdbc=jdbc;this.json=json;this.outbox=outbox;this.validator=validator;}
 public Preview preview(UUID id,Caller actor){actor.requireRole("ADMIN");var f=facilities.find(id);var reservations=transport.impact(id);return new Preview(id,f.getStatus(),f.getVersion(),reservations,fingerprint(reservations),"PRESERVE_EXISTING_BOOKINGS; compensation and affected-customer notification require review");}
 @Transactional public FacilityView decide(UUID id,Decision input,Caller actor){
  actor.requireRole("ADMIN");if(!validator.validate(input).isEmpty()||input.reason().trim().length()<20)throw new IllegalArgumentException("Invalid facility status decision");
  if(jdbc.queryForList("SELECT id FROM facilities WHERE id=? FOR UPDATE",UUID.class,id).isEmpty())throw new ResourceNotFoundException("Facility not found");
  var f=facilities.find(id);entities.refresh(f);if(f.getVersion()!=input.expectedVersion())throw new ConflictException("Facility changed; preview the impact again");
  String before=f.getStatus(),after=input.action().equals("SUSPEND")?"SUSPENDED":"ACTIVE";
  if(!before.equals(input.action().equals("SUSPEND")?"ACTIVE":"SUSPENDED"))throw new ConflictException("Invalid facility status transition");
  UUID application=facilities.applicationId(id);if(application!=null&&!gate.approved(List.of(application)).contains(application))throw new ConflictException("First-facility Owner approval has not committed");
  if(input.action().equals("RESTORE")&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM facility_status_decisions WHERE facility_id=? AND action='SUSPEND')",Boolean.class,id)))throw new ConflictException("Legacy suspension needs an audited review before restoration");
  var reservations=changes.begin(id);if(!input.impactFingerprint().equals(fingerprint(reservations)))throw new ConflictException("Booking impact changed; preview and confirm again");
  UUID decision=UUID.randomUUID();String impact=json.valueToTree(reservations).toString();
  f.setStatus(after);entities.flush();
  jdbc.update("INSERT INTO facility_status_decisions(id,facility_id,actor_id,action,reason,before_status,after_status,facility_version,impact) VALUES(?,?,?,?,?,?,?,?,?::jsonb)",decision,id,actor.id(),input.action(),input.reason().trim(),before,after,f.getVersion(),impact);
  jdbc.update("INSERT INTO facility_audit(id,actor_id,facility_id,action,details) VALUES(?,?,?,?,?)",UUID.randomUUID(),actor.id(),id,"ADMIN_FACILITY_"+input.action(),json.valueToTree(Map.of("decisionId",decision,"before",before,"after",after,"reason",input.reason().trim(),"affectedReservations",reservations.size(),"bookingPolicy","PRESERVE_EXISTING_BOOKINGS")).toString());
  outbox.record("sporthub.facility",DomainEvent.create("facility.status.changed",1,"facility-service",id,Map.of("decisionId",decision,"facilityId",id,"ownerId",f.getOwnerId(),"facilityName",f.getName(),"state",after,"reason",input.reason().trim())));
  return facilities.view(f);
 }
 public List<Map<String,Object>> history(UUID id,Caller actor){if(!actor.hasRole("ADMIN"))facilities.ownedEntity(id,actor);else facilities.find(id);return jdbc.queryForList("SELECT * FROM facility_status_decisions WHERE facility_id=? ORDER BY occurred_at,id",id);}
 public Map<String,Object> statistics(Caller actor){actor.requireRole("ADMIN");return Map.of("dateBasis","CURRENT_STATE","regions",jdbc.queryForList("SELECT province_code,status,count(*) AS facility_count FROM facilities GROUP BY province_code,status ORDER BY province_code,status"),"publicationPolicy","First facilities require a committed Owner application; ACTIVE state alone is not proof of publication");}
 /** Every court with its sport and facility name/address: lets the admin report label booking figures per sport and facility. */
 public List<Map<String,Object>> courtDirectory(Caller actor){actor.requireRole("ADMIN");return jdbc.queryForList("SELECT c.id AS court_id,c.facility_id,f.name AS facility_name,f.address_line,f.ward,f.province,f.status AS facility_status,s.name AS sport FROM courts c JOIN facilities f ON f.id=c.facility_id JOIN sport_categories s ON s.id=c.sport_category_id ORDER BY f.name,c.name");}
 private String fingerprint(List<Reservation> rows){var sorted=rows.stream().sorted(Comparator.comparing(r->r.reservationId().toString())).toList();return Signatures.sha256(json.valueToTree(sorted).toString());}
}
