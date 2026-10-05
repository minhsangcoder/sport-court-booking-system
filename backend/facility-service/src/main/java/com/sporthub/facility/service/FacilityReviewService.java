package com.sporthub.facility.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.event.*;
import com.sporthub.facility.domain.Facility;
import static com.sporthub.facility.web.FacilityDtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.util.*;

@Service @Transactional(readOnly=true)
public class FacilityReviewService {
 public record Review(UUID id,UUID facilityId,UUID ownerId,String state,JsonNode snapshot,Instant submittedAt,Instant reviewedAt,UUID reviewedBy,String reason){}
 public record Detail(FacilityView facility,List<CourtView> courts,List<Review> reviews,List<Map<String,Object>> audit,List<FacilityView> possibleDuplicates){}
 private final FacilityService facilities;private final JdbcTemplate jdbc;private final ObjectMapper json;private final ReliableOutbox outbox;private final FacilityReviewDependencies dependencies;
 public FacilityReviewService(FacilityService facilities,JdbcTemplate jdbc,ObjectMapper json,ReliableOutbox outbox,FacilityReviewDependencies dependencies){this.facilities=facilities;this.jdbc=jdbc;this.json=json;this.outbox=outbox;this.dependencies=dependencies;}
 @Transactional public Review submit(UUID id,Caller actor,String token){
  if(facilities.applicationId(id)!=null)throw new ConflictException("Submit the linked Owner application instead");actor.requireRole("OWNER");return submitInternal(id,actor,token,false);
 }
 @Transactional public Review submitFirst(UUID id,Caller actor,String token){return submitInternal(id,actor,token,true);}
 private Review submitInternal(UUID id,Caller actor,String token,boolean first){
  lock(id);var f=facilities.ownedEntity(id,actor);if(first&&f.getStatus().equals("PENDING_APPROVAL"))return reviews(id).stream().filter(r->r.state().equals("PENDING_APPROVAL")).findFirst().orElseThrow();if(!f.getStatus().equals("DRAFT"))throw new ConflictException("Only a DRAFT facility can be submitted");
  var courts=facilities.ownedCourts(id,actor);if(courts.stream().noneMatch(CourtView::enabled))throw new ConflictException("Add at least one enabled court before submitting");
  JsonNode configuration=first?firstSnapshot(id,actor):null;JsonNode hours;try{hours=first?configuration.path("hours"):dependencies.hours(id,token);}catch(org.springframework.web.client.HttpClientErrorException ex){throw new ConflictException("Operating hours could not be verified");}
  if(!hours.isArray()||hours.isEmpty())throw new ConflictException("Configure operating hours before submitting");
  JsonNode prices=first?configuration.path("prices"):json.createArrayNode();
  UUID review=UUID.randomUUID();var snapshot=json.valueToTree(Map.of("facility",facilities.view(f),"courts",courts,"hours",hours,"documents",jdbc.queryForList("SELECT id,name,content_type,size_bytes FROM facility_documents WHERE facility_id=? AND NOT archived ORDER BY created_at",id),"images",jdbc.queryForList("SELECT id,court_id,content_type,size_bytes FROM facility_images WHERE facility_id=? ORDER BY created_at",id)));
  ((com.fasterxml.jackson.databind.node.ObjectNode)snapshot).set("prices",prices);jdbc.update("INSERT INTO facility_reviews(id,facility_id,owner_id,snapshot) VALUES(?,?,?,?::jsonb)",review,id,actor.id(),snapshot.toString());f.setStatus("PENDING_APPROVAL");audit(f,actor,"FACILITY_SUBMITTED",Map.of("reviewId",review));return reviews(id).stream().filter(r->r.id().equals(review)).findFirst().orElseThrow();
 }
 public List<Review> reviews(UUID id){return jdbc.query("SELECT * FROM facility_reviews WHERE facility_id=? ORDER BY submitted_at DESC",(r,n)->new Review(r.getObject("id",UUID.class),r.getObject("facility_id",UUID.class),r.getObject("owner_id",UUID.class),r.getString("state"),read(r.getString("snapshot")),r.getTimestamp("submitted_at").toInstant(),r.getTimestamp("reviewed_at")==null?null:r.getTimestamp("reviewed_at").toInstant(),r.getObject("reviewed_by",UUID.class),r.getString("reason")),id);}
 public List<Review> ownHistory(UUID id,Caller actor){facilities.ownedEntity(id,actor);return reviews(id);}
 public List<FacilityView> search(String q,String status,Caller actor){actor.requireRole("ADMIN");String pattern="%"+(q==null?"":q.trim())+"%";return jdbc.queryForList("SELECT id FROM facilities WHERE (name ILIKE ? OR phone ILIKE ? OR address_line ILIKE ?) AND (?::varchar IS NULL OR status=?::varchar) ORDER BY COALESCE((SELECT min(submitted_at) FROM facility_reviews r WHERE r.facility_id=facilities.id AND r.state='PENDING_APPROVAL'),created_at) LIMIT 100",UUID.class,pattern,pattern,pattern,status,status).stream().map(id->facilities.view(facilities.find(id))).toList();}
 @Transactional public Detail detail(UUID id,Caller actor){actor.requireRole("ADMIN");var f=facilities.find(id);audit(f,actor,"ADMIN_FACILITY_VIEWED",Map.of());var duplicate=jdbc.queryForList("SELECT id FROM facilities WHERE id<>? AND lower(address_line)=lower(?) AND lower(province)=lower(?) AND lower(district)=lower(?)",UUID.class,id,f.getAddressLine(),f.getProvince(),f.getDistrict()).stream().map(other->facilities.view(facilities.find(other))).toList();return new Detail(facilities.view(f),jdbc.queryForList("SELECT id FROM courts WHERE facility_id=? ORDER BY name",UUID.class,id).stream().map(c->facilities.courtView(facilities.courtEntity(c))).toList(),reviews(id),jdbc.queryForList("SELECT id,actor_id,action,occurred_at,details FROM facility_audit WHERE facility_id=? ORDER BY occurred_at DESC LIMIT 100",id),duplicate);}
 @Transactional public FacilityView decide(UUID id,String action,String reason,Caller actor){
  if(facilities.applicationId(id)!=null)throw new ConflictException("Decide the linked Owner application instead");return decideInternal(id,action,reason,actor,false);
 }
 @Transactional public FacilityView decideFirst(UUID id,String action,String reason,Caller actor){actor.requireRole("ADMIN");lock(id);var f=facilities.find(id);if(action.equals("APPROVE")&&f.getStatus().equals("ACTIVE"))return facilities.view(f);if(action.equals("SUPPLEMENT_REQUIRED")&&f.getStatus().equals("DRAFT"))return facilities.view(f);if(action.equals("REJECT")&&f.getStatus().equals("REJECTED"))return facilities.view(f);if(action.equals("APPROVE"))firstSnapshot(id,new Caller(f.getOwnerId(),"Applicant",Set.of("CUSTOMER"),Map.of(id,Set.of("APPLICATION_READ"))));else dependencies.applicationSnapshot(facilities.applicationId(id),id,f.getTimezone(),List.of(),true);return decideInternal(id,action,reason,actor,true);}
 private FacilityView decideInternal(UUID id,String action,String reason,Caller actor,boolean first){
  actor.requireRole("ADMIN");lock(id);var f=facilities.find(id);String before=f.getStatus();String state;
  switch(action){
   case "APPROVE","REJECT","SUPPLEMENT_REQUIRED"->{if(!before.equals("PENDING_APPROVAL"))throw new ConflictException("Facility is not pending review");if(!action.equals("APPROVE")&&reason.trim().length()<20)throw new IllegalArgumentException("Rejection/supplement reason must contain at least 20 characters");state=action.equals("APPROVE")?"ACTIVE":action.equals("REJECT")?"REJECTED":"DRAFT";var pending=jdbc.queryForList("SELECT id FROM facility_reviews WHERE facility_id=? AND state='PENDING_APPROVAL' FOR UPDATE",UUID.class,id);if(pending.size()!=1)throw new ConflictException("Pending review missing");UUID review=pending.getFirst();String result=action.equals("APPROVE")?"APPROVED":action;jdbc.update("UPDATE facility_reviews SET state=?,reviewed_at=NOW(),reviewed_by=?,reason=? WHERE id=?",result,actor.id(),reason,review);if(!first)outbox.record("sporthub.facility",DomainEvent.create("facility.reviewed",1,"facility-service",id,Map.of("reviewId",review,"facilityId",id,"ownerId",f.getOwnerId(),"facilityName",f.getName(),"state",result,"reason",reason)));}
   default->throw new IllegalArgumentException("Unsupported review action");
  }
  f.setStatus(state);audit(f,actor,"ADMIN_FACILITY_"+action,Map.of("before",before,"after",state,"reason",reason));return facilities.view(f);
 }
 private JsonNode firstSnapshot(UUID id,Caller actor){
  var f=facilities.ownedEntity(id,actor);if(jdbc.queryForObject("SELECT count(*) FROM facility_documents WHERE facility_id=? AND NOT archived",Integer.class,id)<2)throw new ConflictException("Attach identity and location legal documents before submitting");
  if(jdbc.queryForObject("SELECT count(*) FROM facility_images WHERE facility_id=?",Integer.class,id)<1)throw new ConflictException("Attach a facility image before submitting");
  var contexts=facilities.ownedCourts(id,actor).stream().map(c->{var context=facilities.context(c.id(),actor);return Map.<String,Object>of("courtId",c.id(),"sportCategoryId",c.sportCategoryId(),"enabled",c.enabled(),"maintenance",context.maintenance());}).toList();
  return dependencies.applicationSnapshot(facilities.applicationId(id),id,f.getTimezone(),contexts,false);
 }
 private void lock(UUID id){if(jdbc.queryForList("SELECT id FROM facilities WHERE id=? FOR UPDATE",UUID.class,id).isEmpty())throw new ResourceNotFoundException("Facility not found");}
 private void audit(Facility f,Caller actor,String action,Object details){jdbc.update("INSERT INTO facility_audit(id,actor_id,facility_id,action,details) VALUES(?,?,?,?,?)",UUID.randomUUID(),actor.id(),f.getId(),action,json.valueToTree(details).toString());}
 private JsonNode read(String text){try{return json.readTree(text);}catch(Exception ex){throw new IllegalStateException(ex);}}
}
