package com.sporthub.identity.service;

import static com.sporthub.identity.web.dto.OwnerApplicationDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporthub.identity.exception.IdentityException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Admin projection: only state/reason/field names, never legal values or encrypted snapshots. */
@Component
public class OwnerApplicationHistory {
 private static final Set<String> STATES=Set.of("DRAFT","SUBMITTING","PENDING_APPROVAL","SUPPLEMENT_REQUIRED","DECIDING","APPROVING","APPROVED","REJECTED");
 private static final Set<String> LEGAL_FIELDS=Set.of("representativeName","identityNumber","businessName","businessLicense","taxCode","bankName","bankAccountHolder","bankAccountNumber");
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 public OwnerApplicationHistory(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public HistoryPage read(UUID id,int page,int size){
  if(page<0||size<1||size>100)throw new IdentityException(HttpStatus.BAD_REQUEST,"IDENTITY-OWNER-APPLICATION","History page must be nonnegative and size between 1 and 100");
  String scope="a.entity_type='OWNER_APPLICATION' AND a.entity_id=? AND a.action LIKE 'OWNER\\_APPLICATION\\_%' ESCAPE '\\'";
  long total=jdbc.queryForObject("SELECT count(*) FROM audit_log a WHERE "+scope,Long.class,id);
  var items=jdbc.query("SELECT a.id,a.user_id,p.full_name,a.action,a.created_at,a.old_value,a.new_value FROM audit_log a LEFT JOIN user_profiles p ON p.user_id=a.user_id WHERE "+scope+" ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",(r,n)->{
   var before=readJson(r.getString("old_value"));var after=readJson(r.getString("new_value"));
   var fields=new ArrayList<String>();for(var f:after.path("changedFields"))if(f.isTextual()&&LEGAL_FIELDS.contains(f.asText()))fields.add(f.asText());
   String submission=after.path("submissionId").asText(null);UUID submissionId=null;
   if(submission!=null)try{submissionId=UUID.fromString(submission);}catch(IllegalArgumentException ignored){}
   return new HistoryEntry(r.getObject("id",UUID.class),r.getObject("user_id",UUID.class),r.getString("full_name"),r.getString("action"),r.getTimestamp("created_at").toInstant(),state(before,"state"),state(after,"state"),after.path("reason").asText(null),List.copyOf(fields),submissionId,state(after,"submissionOrigin"),state(before,"state")!=null||state(after,"state")!=null);
  },id,size,(long)page*size);
  return new HistoryPage(items,page,size,total,(total+size-1)/size);
 }
 private String state(JsonNode node,String field){String value=node.path(field).asText(null);return STATES.contains(Objects.toString(value,""))?value:null;}
 private JsonNode readJson(String value){if(value==null)return json.createObjectNode();try{return json.readTree(value);}catch(Exception ex){throw new IllegalStateException("Invalid application audit metadata",ex);}}
}
