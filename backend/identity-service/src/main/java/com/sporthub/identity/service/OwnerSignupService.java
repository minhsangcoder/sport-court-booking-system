package com.sporthub.identity.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.dto.OwnerSignupSetup;
import com.sporthub.common.upload.UploadValidation;
import com.sporthub.identity.domain.AccountStatus;
import com.sporthub.identity.exception.IdentityException;
import com.sporthub.identity.web.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.time.Instant;
import java.util.*;

/** Registration orchestration enters the existing application lifecycle; it never grants a role. */
@Service
public class OwnerSignupService {
 private final AuthService auth;private final OwnerApplicationService applications;private final OwnerApplicationDependencies dependencies;private final JdbcTemplate jdbc;private final ObjectMapper json;private final jakarta.validation.Validator validator;private final PasswordEncoder passwords;private final jakarta.persistence.EntityManager entities;private final TransactionTemplate tx;
 public OwnerSignupService(AuthService auth,OwnerApplicationService applications,OwnerApplicationDependencies dependencies,JdbcTemplate jdbc,ObjectMapper json,jakarta.validation.Validator validator,PasswordEncoder passwords,jakarta.persistence.EntityManager entities,PlatformTransactionManager manager){this.auth=auth;this.applications=applications;this.dependencies=dependencies;this.jdbc=jdbc;this.json=json;this.validator=validator;this.passwords=passwords;this.entities=entities;tx=new TransactionTemplate(manager);}
 public RegisterResult register(RegisterRequest request,String key,MultipartFile identity,MultipartFile location,MultipartFile image,RequestMetadata metadata){
  if(request==null||!validator.validate(request).isEmpty())throw invalid("Invalid account information");
  if(!request.ownerOption())return auth.register(request,metadata); // Ignore all incidental Owner fields/files.
  String keyHash;try{keyHash=hash(UUID.fromString(key).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ex){throw invalid("Owner signup requires a UUID Idempotency-Key");}
  OwnerApplicationDtos.Create owner;OwnerSignupSetup setup;
  try {
   owner=json.treeToValue(request.ownerApplication(),OwnerApplicationDtos.Create.class);
   if(owner==null)throw invalid("Supply valid Owner information and first facility");
   var violations=validator.validate(owner);
   if(!violations.isEmpty())throw new com.sporthub.identity.exception.OwnerSignupInputException("Supply valid Owner information and first facility",violations.stream().map(v->com.sporthub.common.dto.FieldError.builder().field("ownerApplication."+v.getPropertyPath()).code("Invalid").message(v.getMessage()).build()).toList());
   setup=json.treeToValue(request.ownerApplication().path("setup"),OwnerSignupSetup.class);setup.validate();
  }catch(IdentityException ex){throw ex;}catch(Exception ex){throw invalid("Invalid Owner/facility/operating information");}
  checkFile("identityDocument",identity,false);checkFile("locationDocument",location,false);checkFile("facilityImage",image,true);
  var payload=new LinkedHashMap<String,Object>();payload.put("setup",setup);
  payload.put("identityDocument",file(identity));payload.put("locationDocument",file(location));payload.put("facilityImage",file(image));
  // Digest a canonical server whitelist; never persist credentials, document bytes or raw legal values here.
  var fingerprint=new LinkedHashMap<String,Object>();fingerprint.put("fullName",request.fullName().trim());fingerprint.put("email",request.email()==null?null:request.email().trim().toLowerCase(Locale.ROOT));fingerprint.put("phone",request.phone());fingerprint.put("owner",owner);fingerprint.put("setup",setup);
  for(var entry:Map.of("identityDocument",identity,"locationDocument",location,"facilityImage",image).entrySet())fingerprint.put(entry.getKey(),Map.of("name",Objects.toString(entry.getValue().getOriginalFilename(),"file"),"digest",hash(bytes(entry.getValue()))));
  String requestHash=canonicalHash(fingerprint);
  // Preflight has no writes. Validation/category/service failures before this point create nothing.
  var preflight=new LinkedHashMap<String,Object>();preflight.put("facilityId",new UUID(0,0));preflight.put("userId",new UUID(0,0));preflight.put("facility",owner.facility());preflight.put("setup",setup);
  try{if(jdbc.queryForObject("SELECT count(*) FROM owner_signup_receipts WHERE key_hash=?",Integer.class,keyHash)==0)dependencies.facility(new UUID(0,0),"signup-validate",preflight,null);}catch(org.springframework.web.client.HttpClientErrorException ex){throw invalid("Invalid first facility or inactive sport category");}catch(RuntimeException ex){throw new IdentityException(HttpStatus.SERVICE_UNAVAILABLE,"IDENTITY-OWNER-SIGNUP","Facility validation is unavailable; retry safely");}
  var receipt=tx.execute(status->{
   jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"owner-signup:"+keyHash);
   var existing=jdbc.queryForList("SELECT * FROM owner_signup_receipts WHERE key_hash=?",keyHash);
   if(!existing.isEmpty()){
    var row=existing.getFirst();var account=jdbc.queryForMap("SELECT password_hash,status FROM users WHERE id=? FOR UPDATE",row.get("user_id"));String password=(String)account.get("password_hash");
    if(!matchesReceipt((String)row.get("request_hash"),requestHash,fingerprint)||!passwords.matches(request.password(),password))throw new IdentityException(HttpStatus.CONFLICT,"IDENTITY-OWNER-SIGNUP","Retry must use the original registration data and password");
    if(!requestHash.equals(row.get("request_hash")))jdbc.update("UPDATE owner_signup_receipts SET request_hash=? WHERE key_hash=?",requestHash,keyHash);
    if(!Set.of("PENDING_VERIFICATION","ACTIVE").contains(account.get("status")))throw new IdentityException(HttpStatus.FORBIDDEN,"IDENTITY-OWNER-SIGNUP","Registration account is inactive");return row;
   }
   var result=auth.register(new RegisterRequest(request.fullName(),request.email(),request.phone(),request.password()),metadata);entities.flush();
   UUID application=applications.createForRegistration(owner,result.userId());
   jdbc.update("INSERT INTO owner_signup_receipts(key_hash,request_hash,user_id,application_id,challenge_id,verification_expires_at) VALUES(?,?,?,?,?,?)",keyHash,requestHash,result.userId(),application,result.verificationChallengeId(),java.sql.Timestamp.from(result.verificationExpiresAt()));
   return jdbc.queryForMap("SELECT * FROM owner_signup_receipts WHERE key_hash=?",keyHash);
  });
  UUID application=(UUID)receipt.get("application_id");String message=null;
  try {
   var current=applications.registrationSummary(application);
   if(current.state().equals("DRAFT")){applications.prepareRegistration(application,payload);applications.submitRegistration(application);}
   else if(current.state().equals("SUBMITTING"))applications.process(application);
  }catch(RuntimeException ex){
   // Account/application/receipt already committed. Keep Customer verification and private draft recovery.
   org.slf4j.LoggerFactory.getLogger(getClass()).warn("Owner signup {} setup deferred ({})",application,ex.getClass().getSimpleName());
   applications.registrationSetupFailed(application);
  }
  var summary=applications.registrationSummary(application);
  if(summary.state().equals("DRAFT"))message="Account created; verify and sign in to complete the private Owner application, or retry the same signup request";
  else if(summary.state().equals("SUBMITTING"))message="Account created; Owner submission is retrying safely";
  return new RegisterResult((UUID)receipt.get("user_id"),AccountStatus.valueOf(jdbc.queryForObject("SELECT status FROM users WHERE id=?",String.class,receipt.get("user_id"))),(UUID)receipt.get("challenge_id"),((java.sql.Timestamp)receipt.get("verification_expires_at")).toInstant(),summary,message);
 }
 private void checkFile(String field,MultipartFile file,boolean image){try{if(image)UploadValidation.image(file);else UploadValidation.document(file);}catch(IllegalArgumentException ex){throw new com.sporthub.identity.exception.OwnerSignupInputException(field+": "+ex.getMessage(),List.of(com.sporthub.common.dto.FieldError.builder().field(field).code("InvalidFile").message(ex.getMessage()).build()));}}
 private Map<String,Object> file(MultipartFile file){return Map.of("name",Objects.toString(file.getOriginalFilename(),"file"),"bytes",bytes(file));}
 private byte[] bytes(MultipartFile file){try{return file.getBytes();}catch(java.io.IOException ex){throw invalid("Could not read verification file");}}
 private String canonicalHash(Map<String,Object> fingerprint){try{return hash(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(fingerprint));}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw invalid("Invalid registration data");}}
 private boolean matchesReceipt(String stored,String canonical,Map<String,Object> fingerprint){
  if(stored.equals(canonical))return true;
  // Early, uncommitted signup receipts used Map.of iteration order. Accept only the exact
  // original whitelist/file digests under those twelve layouts, then normalize in the same transaction.
  String[] files={"identityDocument","locationDocument","facilityImage"};
  for(int first=0;first<3;first++)for(int second=0;second<3;second++)if(first!=second)for(boolean nameFirst:new boolean[]{true,false}){
   var legacy=new LinkedHashMap<String,Object>();for(String field:List.of("fullName","email","phone","owner","setup"))legacy.put(field,fingerprint.get(field));
   for(int index:new int[]{first,second,3-first-second}){
    var file=(Map<?,?>)fingerprint.get(files[index]);var ordered=new LinkedHashMap<String,Object>();
    for(String field:nameFirst?List.of("name","digest"):List.of("digest","name"))ordered.put(field,file.get(field));legacy.put(files[index],ordered);
   }
   if(stored.equals(hash(json.valueToTree(legacy).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))))return true;
  }
  return false;
 }
 private String hash(byte[] value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
 private IdentityException invalid(String message){return new IdentityException(HttpStatus.BAD_REQUEST,"IDENTITY-OWNER-SIGNUP",message);}
}
