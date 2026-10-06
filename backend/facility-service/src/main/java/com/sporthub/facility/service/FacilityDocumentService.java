package com.sporthub.facility.service;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import io.minio.*;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
@Service
public class FacilityDocumentService {
 public record Document(UUID id,String name,String url,String contentType,long sizeBytes,boolean archived){}
 private final MinioClient storage,signer;private final String bucket;private final FacilityService facilities;private final JdbcTemplate jdbc;
 public FacilityDocumentService(@Value("${sporthub.media.endpoint}") String endpoint,@Value("${sporthub.media.access-key}") String access,@Value("${sporthub.media.secret-key}") String secret,@Value("${sporthub.media.bucket}") String bucket,@Value("${MINIO_PUBLIC_ENDPOINT:}") String publicEndpoint,FacilityService facilities,JdbcTemplate jdbc){this.storage=MinioClient.builder().endpoint(endpoint).credentials(access,secret).build();this.signer=MinioClient.builder().endpoint(publicEndpoint.isBlank()?endpoint:publicEndpoint).credentials(access,secret).region("us-east-1").build();this.bucket=bucket;this.facilities=facilities;this.jdbc=jdbc;}
 @Transactional public List<Document> list(UUID id,Caller actor){if(actor.hasRole("ADMIN")){facilities.find(id);audit(id,actor,"ADMIN_DOCUMENTS_VIEWED");}else facilities.ownedEntity(id,actor);return jdbc.query("SELECT * FROM facility_documents WHERE facility_id=? AND (? OR NOT archived) ORDER BY created_at",(r,n)->view(r.getObject("id",UUID.class),r.getString("name"),r.getString("object_key"),r.getString("content_type"),r.getLong("size_bytes"),r.getBoolean("archived")),id,actor.hasRole("ADMIN"));}
 @Transactional public Document upload(UUID facilityId,MultipartFile file,Caller actor){return uploadOnce(facilityId,UUID.randomUUID(),file,actor);}
 @Transactional public Document uploadOnce(UUID facilityId,UUID id,MultipartFile file,Caller actor){
  facilities.ownedEntity(facilityId,actor);
  jdbc.queryForList("SELECT id FROM facilities WHERE id=? FOR UPDATE",UUID.class,facilityId);
  var existing=jdbc.queryForList("SELECT id FROM facility_documents WHERE facility_id=? AND id=?",UUID.class,facilityId,id);
  if(!existing.isEmpty())return list(facilityId,actor).stream().filter(d->d.id().equals(id)).findFirst().orElseThrow();
  facilities.mutableOwnedEntity(facilityId,actor);if(file.isEmpty()||file.getSize()>10*1024*1024)throw new IllegalArgumentException("Document must be between 1 byte and 10 MB");
  String type=com.sporthub.common.upload.UploadValidation.document(file);String key="private/facility-reviews/"+facilityId+"/"+id;String name=Objects.toString(file.getOriginalFilename(),"document").replaceAll("[\\\\/\\r\\n]","_");if(name.length()>180)name=name.substring(name.length()-180);
  try{if(!storage.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))storage.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());try(var stream=file.getInputStream()){storage.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(type).stream(stream,file.getSize(),-1).build());}
   try{jdbc.update("INSERT INTO facility_documents(id,facility_id,name,object_key,content_type,size_bytes) VALUES(?,?,?,?,?,?)",id,facilityId,name,key,type,file.getSize());audit(facilityId,actor,"FACILITY_DOCUMENT_UPLOADED");}catch(RuntimeException ex){storage.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());throw ex;}
   return view(id,name,key,type,file.getSize(),false);
  }catch(Exception ex){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"Private document storage is unavailable",ex);}
 }
 @Transactional public void delete(UUID facilityId,UUID id,Caller actor){facilities.mutableOwnedEntity(facilityId,actor);var keys=jdbc.queryForList("SELECT object_key FROM facility_documents WHERE id=? AND facility_id=? AND NOT archived",String.class,id,facilityId);if(keys.isEmpty())throw new ResourceNotFoundException("Document not found");boolean retained=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM facility_reviews WHERE facility_id=? AND snapshot->'documents' @> jsonb_build_array(jsonb_build_object('id',?::text)))",Boolean.class,facilityId,id.toString()));if(retained){jdbc.update("UPDATE facility_documents SET archived=true WHERE id=?",id);audit(facilityId,actor,"FACILITY_DOCUMENT_ARCHIVED");}else{jdbc.update("INSERT INTO media_cleanup(object_key) VALUES(?) ON CONFLICT DO NOTHING",keys.getFirst());jdbc.update("DELETE FROM facility_documents WHERE id=?",id);audit(facilityId,actor,"FACILITY_DOCUMENT_REMOVED");}}
 private Document view(UUID id,String name,String key,String type,long size,boolean archived){try{return new Document(id,name,signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET).bucket(bucket).object(key).expiry(300).build()),type,size,archived);}catch(Exception ex){throw new IllegalStateException("Could not sign private document access",ex);}}
 private void audit(UUID id,Caller actor,String action){jdbc.update("INSERT INTO facility_audit(id,actor_id,facility_id,action) VALUES(?,?,?,?)",UUID.randomUUID(),actor.id(),id,action);}
}
