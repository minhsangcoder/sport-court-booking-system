package com.sporthub.identity.service;
import static com.sporthub.identity.web.dto.OwnerApplicationDtos.*;
import com.sporthub.identity.domain.*;
import com.sporthub.identity.repository.OwnerApplicationSearchRepository;
import com.sporthub.identity.security.AuthenticatedUser;
import com.sporthub.identity.exception.IdentityException;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;
import java.time.Instant;

/** Durable coordinator. Identity commit is the publication gate; prepared Facility/Payment resources stay private. */
@Service
public class OwnerApplicationService {
 private final OwnerApplicationHistory historyReader;private final OwnerApplicationSearchRepository searchRepository;private final JdbcTemplate jdbc;private final ObjectMapper json;private final OwnerApplicationCipher cipher;private final OwnerApplicationDependencies dependencies;private final TransactionTemplate tx;private final jakarta.validation.Validator validator;
 public OwnerApplicationService(JdbcTemplate jdbc,ObjectMapper json,OwnerApplicationCipher cipher,OwnerApplicationDependencies dependencies,PlatformTransactionManager manager,jakarta.validation.Validator validator,OwnerApplicationSearchRepository searchRepository,OwnerApplicationHistory historyReader){this.historyReader=historyReader;this.searchRepository=searchRepository;this.validator=validator;this.jdbc=jdbc;this.json=json;this.cipher=cipher;this.dependencies=dependencies;tx=new TransactionTemplate(manager);}
 public List<Summary> own(AuthenticatedUser actor){return jdbc.queryForList("SELECT id FROM owner_applications WHERE user_id=? ORDER BY created_at DESC",UUID.class,actor.userId()).stream().map(id->summary(row(id))).toList();}
 public List<Summary> search(String q,String state,AuthenticatedUser actor){admin(actor);return searchRepository.legacy(OwnerApplicationSearch.legacy(q,state));}
 public SearchPage search(SearchCriteria criteria,AuthenticatedUser actor){admin(actor);return searchRepository.search(OwnerApplicationSearch.parse(criteria));}
 public Detail detail(UUID id,AuthenticatedUser actor,boolean adminView){if(adminView)admin(actor);var data=row(id);if(!adminView)own(data,actor);if(adminView)tx.executeWithoutResult(status->audit(id,actor.userId(),"ADMIN_OWNER_APPLICATION_VIEWED",Map.of()));var facility=dependencies.facility(id,adminView?"view":"own-view",command(data,actor.userId()),null);
  var history=jdbc.query("SELECT id,submitted_at,facility_snapshot FROM owner_application_submissions WHERE application_id=? ORDER BY submitted_at DESC,id DESC",(r,n)->Map.<String,Object>of("id",r.getObject("id",UUID.class),"submittedAt",r.getTimestamp("submitted_at").toInstant(),"facilitySnapshot",read(r.getString("facility_snapshot"))),id);
  var audits=jdbc.query("SELECT id,user_id,action,created_at,new_value FROM audit_log WHERE entity_type='OWNER_APPLICATION' AND entity_id=? ORDER BY created_at DESC LIMIT 100",(r,n)->Map.<String,Object>of("id",r.getObject("id",UUID.class),"actorId",r.getObject("user_id",UUID.class),"action",r.getString("action"),"createdAt",r.getTimestamp("created_at").toInstant(),"details",read(r.getString("new_value"))),id);
  var applicant=adminView?jdbc.queryForObject("SELECT u.id,p.full_name,u.email,u.phone,u.status FROM users u JOIN user_profiles p ON p.user_id=u.id WHERE u.id=?",(r,n)->new Applicant(r.getObject("id",UUID.class),r.getString("full_name"),r.getString("email"),r.getString("phone"),r.getString("status")),data.get("user_id")):null;
  return new Detail(summary(data),legal(data),facility,history,audits,applicant,history.isEmpty()?null:(JsonNode)history.getFirst().get("facilitySnapshot"));
 }
 public HistoryPage history(UUID id,int page,int size,AuthenticatedUser actor){admin(actor);row(id);var result=historyReader.read(id,page,size);audit(id,actor.userId(),"ADMIN_OWNER_APPLICATION_HISTORY_VIEWED",Map.of());return result;}
 public Summary create(Create input,AuthenticatedUser actor){
  validate(input);UUID id=tx.execute(status->{active(actor.userId());var roles=jdbc.queryForList("SELECT role FROM user_roles WHERE user_id=?",String.class,actor.userId());if(!roles.contains("CUSTOMER")||roles.contains("OWNER"))throw fail(HttpStatus.FORBIDDEN,"A verified Customer without Owner role must apply");
   if(!jdbc.queryForList("SELECT id FROM owner_applications WHERE user_id=? AND state<>'REJECTED'",UUID.class,actor.userId()).isEmpty())throw conflict("An open Owner application already exists");
   UUID next=UUID.randomUUID();jdbc.update("INSERT INTO owner_applications(id,user_id,facility_id,business_name,facility_name,private_payload,initial_facility) VALUES(?,?,?,?,?,?,?::jsonb)",next,actor.userId(),UUID.randomUUID(),input.legal().businessName().trim(),input.facility().name().trim(),cipher.seal(write(input.legal()),next),write(input.facility()));audit(next,actor.userId(),"OWNER_APPLICATION_CREATED",null,Map.of("state","DRAFT"));return next;
  });try{ensureFacility(row(id));}catch(RuntimeException ex){jdbc.update("UPDATE owner_applications SET last_error='Facility setup is pending; initialize again safely' WHERE id=?",id);}return summary(row(id));
 }
 // Package-private trusted registration entry point: no generic Guest application API.
 UUID createForRegistration(Create input,UUID user){
  validate(input);return tx.execute(status->{
   var account=jdbc.queryForMap("SELECT status FROM users WHERE id=? FOR UPDATE",user);
   if(!account.get("status").equals("PENDING_VERIFICATION"))throw conflict("Registration account must await verification");
   UUID id=UUID.randomUUID();jdbc.update("INSERT INTO owner_applications(id,user_id,facility_id,business_name,facility_name,private_payload,initial_facility) VALUES(?,?,?,?,?,?,?::jsonb)",id,user,UUID.randomUUID(),input.legal().businessName().trim(),input.facility().name().trim(),cipher.seal(write(input.legal()),id),write(input.facility()));
   audit(id,user,"OWNER_APPLICATION_CREATED",null,Map.of("state","DRAFT","entryPoint","REGISTRATION"));return id;
  });
 }
 Summary registrationSummary(UUID id){return summary(row(id));}
 void prepareRegistration(UUID id,Map<String,Object> payload){var data=row(id);ensureFacility(data);var input=command(data,(UUID)data.get("user_id"));input.putAll(payload);dependencies.facility(id,"signup-prepare",input,null);}
 void registrationSetupFailed(UUID id){jdbc.update("UPDATE owner_applications SET last_error='Owner signup setup is incomplete; initialize the private facility and finish the draft or retry the same registration request' WHERE id=? AND state='DRAFT'",id);}
 Summary submitRegistration(UUID id){
  tx.executeWithoutResult(status->{var data=lock(id);if(!state(data).equals("DRAFT"))return;
   // A pending account may enter review; the existing active() guard still protects approval.
   jdbc.update("UPDATE owner_applications SET submission_origin='DRAFT',state='SUBMITTING',next_attempt_at=NOW(),last_error=NULL,updated_at=NOW() WHERE id=?",id);
  });process(id);return summary(row(id));
 }
 public Summary initialize(UUID id,AuthenticatedUser actor){var data=row(id);own(data,actor);ensureFacility(data);return summary(data);}
 public Summary save(UUID id,Legal input,AuthenticatedUser actor){validate(input);return tx.execute(status->{var data=lock(id);own(data,actor);editable(data);var before=json.valueToTree(legal(data));var after=json.valueToTree(input);var changed=new ArrayList<String>();after.fieldNames().forEachRemaining(field->{if(!Objects.equals(before.get(field),after.get(field)))changed.add(field);});if(changed.isEmpty())return summary(data);jdbc.update("UPDATE owner_applications SET business_name=?,private_payload=?,updated_at=NOW() WHERE id=?",input.businessName().trim(),cipher.seal(write(input),id),id);audit(id,actor.userId(),"OWNER_APPLICATION_UPDATED",Map.of("state",state(data)),Map.of("state",state(data),"changedFields",changed));return summary(row(id));});}
 public Summary submit(UUID id,AuthenticatedUser actor,String token){
  tx.executeWithoutResult(status->{var data=lock(id);own(data,actor);active(actor.userId());if(state(data).equals("PENDING_APPROVAL"))return;if(state(data).equals("SUBMITTING")){jdbc.update("UPDATE owner_applications SET next_attempt_at=NOW() WHERE id=?",id);return;}editable(data);jdbc.update("UPDATE owner_applications SET submission_origin=state,state='SUBMITTING',decision_action=NULL,next_attempt_at=NOW(),updated_at=NOW(),last_error=NULL WHERE id=?",id);});
  process(id);var result=summary(row(id));if(Set.of("DRAFT","SUPPLEMENT_REQUIRED").contains(result.state())&&result.lastError()!=null)throw conflict(result.lastError());return result;
 }
 public Summary decide(UUID id,Decision input,AuthenticatedUser actor){
  admin(actor);validate(input);if(!input.action().equals("APPROVE")&&input.reason().trim().length()<20)throw fail(HttpStatus.BAD_REQUEST,"Rejection/supplement reason must contain at least 20 characters");if(input.action().equals("APPROVE")&&input.commissionPercent()==null)throw fail(HttpStatus.BAD_REQUEST,"Choose an explicit platform commission percentage");
  tx.executeWithoutResult(status->{globalAccountLock();var data=lock(id);if(!state(data).equals("PENDING_APPROVAL")){boolean same=input.action().equals(data.get("decision_action"))&&(input.action().equals("APPROVE")?input.commissionPercent().compareTo((java.math.BigDecimal)data.get("commission_percent"))==0:input.reason().trim().equals(data.get("reason")));if(same&&Set.of("APPROVING","DECIDING","APPROVED","REJECTED","SUPPLEMENT_REQUIRED").contains(state(data)))return;throw conflict("Application is not pending review or has a different decision");}
   if(input.action().equals("APPROVE"))active((UUID)data.get("user_id"));jdbc.update("UPDATE owner_applications SET state=?,decision_action=?,reviewed_by=?,reason=?,commission_percent=?,next_attempt_at=NOW(),updated_at=NOW(),last_error=NULL WHERE id=?",input.action().equals("APPROVE")?"APPROVING":"DECIDING",input.action(),actor.userId(),input.reason().trim(),input.commissionPercent(),id);audit(id,actor.userId(),"OWNER_APPLICATION_DECISION_REQUESTED",Map.of("state",state(data)),Map.of("state",input.action().equals("APPROVE")?"APPROVING":"DECIDING","action",input.action(),"reason",input.reason().trim()));
  });process(id);return summary(row(id));
 }
 public Summary retry(UUID id,AuthenticatedUser actor){admin(actor);var data=row(id);if(!Set.of("SUBMITTING","APPROVING","DECIDING").contains(state(data)))throw conflict("No activation/decision is pending");jdbc.update("UPDATE owner_applications SET next_attempt_at=NOW() WHERE id=?",id);process(id);return summary(row(id));}
 @Scheduled(fixedDelayString="${sporthub.identity.application-delay-ms:5000}",initialDelayString="${sporthub.identity.application-delay-ms:5000}") public void reconcile(){for(var id:jdbc.queryForList("SELECT id FROM owner_applications WHERE state IN ('SUBMITTING','APPROVING','DECIDING') AND next_attempt_at<=NOW() AND (lease_until IS NULL OR lease_until<NOW()) ORDER BY next_attempt_at LIMIT 10",UUID.class))process(id);}
 public void process(UUID id){
  UUID lease=UUID.randomUUID();var data=tx.execute(status->{var current=lock(id);if(!Set.of("SUBMITTING","APPROVING","DECIDING").contains(state(current)))return null;if(current.get("lease_until")!=null&&instant(current.get("lease_until")).isAfter(Instant.now()))return null;jdbc.update("UPDATE owner_applications SET lease_until=NOW()+INTERVAL '60 seconds',lease_token=? WHERE id=?",lease,id);return current;});if(data==null)return;boolean prepared=false;
  try{
   if(state(data).equals("SUBMITTING")){ensureFacility(data);var review=dependencies.facility(id,"submit",command(data,(UUID)data.get("user_id")),null);tx.executeWithoutResult(status->{var current=lock(id);if(!lease.equals(current.get("lease_token")))return;var changes=contactChanges(id,review.path("snapshot"));UUID submission=UUID.randomUUID();jdbc.update("INSERT INTO owner_application_submissions(id,application_id,private_snapshot,facility_snapshot) VALUES(?,?,?,?::jsonb)",submission,id,current.get("private_payload"),review.path("snapshot").toString());jdbc.update("UPDATE owner_applications SET state='PENDING_APPROVAL',facility_name=?,submitted_at=NOW(),reviewed_at=NULL,reviewed_by=NULL,reason=NULL,commission_percent=NULL,lease_until=NULL,lease_token=NULL,last_error=NULL,updated_at=NOW() WHERE id=?",review.path("snapshot").path("facility").path("name").asText(),id);audit(id,(UUID)current.get("user_id"),"OWNER_APPLICATION_SUBMITTED",Map.of("state",current.get("submission_origin")),Map.of("state","PENDING_APPROVAL","submissionOrigin",current.get("submission_origin"),"submissionId",submission,"changedFields",changes));});return;}
   String action=(String)data.get("decision_action");dependencies.facility(id,action,command(data,(UUID)data.get("reviewed_by")),null);prepared=true;if(action.equals("APPROVE"))dependencies.wallet(id,(UUID)data.get("user_id"),(java.math.BigDecimal)data.get("commission_percent"));
   tx.executeWithoutResult(status->{globalAccountLock();var current=lock(id);if(!lease.equals(current.get("lease_token")))return;UUID user=(UUID)current.get("user_id");String next;if(action.equals("APPROVE")){active(user);jdbc.update("INSERT INTO user_roles(user_id,role) VALUES(?,'OWNER') ON CONFLICT DO NOTHING",user);jdbc.update("INSERT INTO owner_profiles(user_id,business_name,approval_status,approved_by,approved_at) VALUES(?,?,'APPROVED',?,NOW()) ON CONFLICT(user_id) DO UPDATE SET business_name=EXCLUDED.business_name,approval_status='APPROVED',approved_by=EXCLUDED.approved_by,approved_at=NOW(),updated_at=NOW()",user,current.get("business_name"),current.get("reviewed_by"));jdbc.update("UPDATE refresh_tokens SET revoked=true,revoked_at=NOW() WHERE user_id=? AND NOT revoked",user);next="APPROVED";}else next=action.equals("REJECT")?"REJECTED":action;jdbc.update("UPDATE owner_applications SET state=?,reviewed_at=NOW(),lease_until=NULL,lease_token=NULL,last_error=NULL,updated_at=NOW() WHERE id=?",next,id);audit(id,(UUID)current.get("reviewed_by"),"OWNER_APPLICATION_"+next,Map.of("state",state(current)),Map.of("state",next,"facilityId",current.get("facility_id"),"reason",current.get("reason"),"commissionPercent",Objects.toString(current.get("commission_percent"),"")));notice(user,next,(String)current.get("reason"));});
  }catch(RuntimeException ex){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Owner application {} operation failed at {} ({})",id,ex.getStackTrace()[0],ex.getClass().getSimpleName());boolean checksFailed=!prepared&&ex instanceof org.springframework.web.client.HttpClientErrorException;String message=ex instanceof IdentityException?"Applicant is locked or unverified; activation is paused":checksFailed?"Facility review checks failed; correct the draft or request a supplement":"A dependency is unavailable; the operation will retry safely";tx.executeWithoutResult(status->{var current=lock(id);if(!lease.equals(current.get("lease_token")))return;String recovered=state(current);if(checksFailed&&recovered.equals("SUBMITTING"))recovered=(String)current.get("submission_origin");else if(checksFailed&&recovered.equals("APPROVING"))recovered="PENDING_APPROVAL";jdbc.update("UPDATE owner_applications SET state=?,lease_until=NULL,lease_token=NULL,next_attempt_at=NOW()+INTERVAL '30 seconds',last_error=? WHERE id=?",recovered,message,id);});}
 }
 public List<UUID> committed(List<UUID> ids){if(ids==null||ids.size()>200)throw fail(HttpStatus.BAD_REQUEST,"At most 200 application ids are allowed");return ids.stream().distinct().filter(id->Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM owner_applications WHERE id=? AND state='APPROVED')",Boolean.class,id))).toList();}
 private List<String> contactChanges(UUID id,JsonNode snapshot){
  var previous=jdbc.queryForList("SELECT facility_snapshot::text FROM owner_application_submissions WHERE application_id=? ORDER BY submitted_at DESC,id DESC LIMIT 1",String.class,id);
  if(previous.isEmpty())return List.of();
  var before=read(previous.getFirst()).path("facility");
  // Legacy submissions did not capture this field; do not invent a historical diff.
  return before.has("contactEmail")&&!Objects.equals(before.get("contactEmail"),snapshot.path("facility").get("contactEmail"))?List.of("contactEmail"):List.of();
 }
 private void ensureFacility(Map<String,Object> data){var input=command(data,(UUID)data.get("user_id"));input.put("facility",read(data.get("initial_facility").toString()));dependencies.facility((UUID)data.get("id"),"create",input,null);}
 private Map<String,Object> command(Map<String,Object> data,UUID actor){var input=new LinkedHashMap<String,Object>();input.put("facilityId",data.get("facility_id"));input.put("userId",data.get("user_id"));input.put("actorId",actor);input.put("reason",Objects.toString(data.get("reason"),""));return input;}
 private void active(UUID user){var account=jdbc.queryForMap("SELECT status,email_verified,phone_verified FROM users WHERE id=? FOR UPDATE",user);if(!account.get("status").equals("ACTIVE")||!(Boolean.TRUE.equals(account.get("email_verified"))||Boolean.TRUE.equals(account.get("phone_verified"))))throw conflict("Applicant account is locked or unverified");}
 private void globalAccountLock(){jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('identity-admin-account-controls',0))",Object.class);}
 private Map<String,Object> row(UUID id){var rows=jdbc.queryForList("SELECT * FROM owner_applications WHERE id=?",id);if(rows.isEmpty())throw fail(HttpStatus.NOT_FOUND,"Owner application not found");return rows.getFirst();}
 private Map<String,Object> lock(UUID id){jdbc.queryForList("SELECT id FROM owner_applications WHERE id=? FOR UPDATE",UUID.class,id);return row(id);}
 private void own(Map<String,Object> data,AuthenticatedUser actor){if(!actor.userId().equals(data.get("user_id")))throw fail(HttpStatus.FORBIDDEN,"Application belongs to another applicant");}
 private void editable(Map<String,Object> data){if(!Set.of("DRAFT","SUPPLEMENT_REQUIRED").contains(state(data)))throw conflict("Application is frozen; wait for a supplement decision or create a new application after rejection");}
 private void admin(AuthenticatedUser actor){if(actor==null||!actor.roles().contains(Role.ADMIN))throw fail(HttpStatus.FORBIDDEN,"Admin role required");}
 private void validate(Object value){if(value==null||!validator.validate(value).isEmpty())throw fail(HttpStatus.BAD_REQUEST,"Invalid application input");}
 private String state(Map<String,Object> data){return (String)data.get("state");}
 private Legal legal(Map<String,Object> data){try{return json.readValue(cipher.open((String)data.get("private_payload"),(UUID)data.get("id")),Legal.class);}catch(IdentityException ex){throw ex;}catch(Exception ex){throw new IllegalStateException(ex);}}
 private Summary summary(Map<String,Object> r){return new Summary((UUID)r.get("id"),(UUID)r.get("user_id"),(UUID)r.get("facility_id"),(String)r.get("business_name"),(String)r.get("facility_name"),state(r),r.get("submitted_at")==null?null:instant(r.get("submitted_at")),(String)r.get("reason"),(java.math.BigDecimal)r.get("commission_percent"),(String)r.get("last_error"));}
 private Instant instant(Object value){return ((java.sql.Timestamp)value).toInstant();}
 private void notice(UUID user,String state,String reason){var u=jdbc.queryForMap("SELECT email,phone,email_verified FROM users WHERE id=?",user);String recipient=Boolean.TRUE.equals(u.get("email_verified"))||u.get("phone")==null?(String)u.get("email"):(String)u.get("phone");jdbc.update("INSERT INTO identity_notifications(id,user_id,recipient,subject,body) VALUES(?,?,?,?,?)",UUID.randomUUID(),user,recipient,"SportHub Owner application "+state,"Application decision: "+state+". "+reason+(state.equals("APPROVED")?". Sign in again to manage your facility, schedules and Staff from the Owner portal.":""));}
 private void audit(UUID id,UUID actor,String action,Object details){audit(id,actor,action,null,details);}
 private void audit(UUID id,UUID actor,String action,Object before,Object after){jdbc.update("INSERT INTO audit_log(id,user_id,action,entity_type,entity_id,old_value,new_value) VALUES(?,?,?,'OWNER_APPLICATION',?,?::jsonb,?::jsonb)",UUID.randomUUID(),actor,action,id,before==null?null:write(before),write(after));}
 private String safeDependencyMessage(org.springframework.web.client.HttpClientErrorException ex){try{return read(ex.getResponseBodyAsString()).path("message").asText("Facility setup is incomplete");}catch(Exception ignored){return "Facility setup is incomplete";}}
 private String write(Object value){return json.valueToTree(value).toString();}
 private JsonNode read(String value){try{return json.readTree(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
 private IdentityException conflict(String message){return fail(HttpStatus.CONFLICT,message);}
 private IdentityException fail(HttpStatus status,String message){return new IdentityException(status,"IDENTITY-OWNER-APPLICATION",message);}
}
