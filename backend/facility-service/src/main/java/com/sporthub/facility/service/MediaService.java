package com.sporthub.facility.service;

import com.sporthub.common.exception.ResourceNotFoundException;
import com.sporthub.common.security.RemoteIdentity.Caller;
import io.minio.*;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@Service
public class MediaService {
    private final MinioClient storage;
    private final MinioClient signer;
    private final String bucket;
    private final FacilityService facilities;
    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public MediaService(@Value("${sporthub.media.endpoint}") String endpoint,
            @Value("${sporthub.media.access-key}") String accessKey,@Value("${sporthub.media.secret-key}") String secretKey,
            @Value("${sporthub.media.bucket}") String bucket,@Value("${MINIO_PUBLIC_ENDPOINT:}") String publicEndpoint,FacilityService facilities,JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager){
        storage=MinioClient.builder().endpoint(endpoint).credentials(accessKey,secretKey).build();
        signer=MinioClient.builder().endpoint(publicEndpoint.isBlank()?endpoint:publicEndpoint).credentials(accessKey,secretKey).region("us-east-1").build();
        this.bucket=bucket;this.facilities=facilities;this.jdbc=jdbc;
        this.transactions=new org.springframework.transaction.support.TransactionTemplate(manager);
    }
    public record ImageView(UUID id,UUID courtId,String objectKey,String url,String contentType,long sizeBytes){}
    public List<ImageView> list(UUID facilityId,Caller caller){
        if(caller==null)facilities.publicDetail(facilityId);else facilities.ownedDetail(facilityId,caller);
        return jdbc.query("SELECT * FROM facility_images WHERE facility_id=? ORDER BY created_at",(rs,n)->image(
                rs.getObject("id",UUID.class),rs.getObject("court_id",UUID.class),rs.getString("object_key"),rs.getString("content_type"),rs.getLong("size_bytes")),facilityId);
    }
    public ImageView upload(UUID facilityId,UUID courtId,MultipartFile file,Caller caller){
        facilities.ownedDetail(facilityId,caller);
        if(courtId!=null && !facilities.ownedCourt(courtId,caller).facilityId().equals(facilityId))
            throw new IllegalArgumentException("Court does not belong to facility");
        if(file.isEmpty() || file.getSize()>10*1024*1024) throw new IllegalArgumentException("Image must be between 1 byte and 10 MB (demo technical limit)");
        String type=validateImage(file);
        UUID id=UUID.randomUUID();String key="facilities/"+facilityId+"/"+id+(type.equals("image/png")?".png":".jpg");
        try{
            ensureBucket();
            try(var stream=file.getInputStream()){
                storage.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(type).stream(stream,file.getSize(),-1).build());
            }
            try{jdbc.update("INSERT INTO facility_images(id,facility_id,court_id,object_key,content_type,size_bytes) VALUES(?,?,?,?,?,?)",id,facilityId,courtId,key,type,file.getSize());}
            catch(RuntimeException ex){storage.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());throw ex;}
            return image(id,courtId,key,type,file.getSize());
        }catch(IllegalArgumentException ex){throw ex;}
        catch(Exception ex){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"Object storage is unavailable",ex);}
    }
    public void delete(UUID facilityId,UUID imageId,Caller caller){
        facilities.ownedDetail(facilityId,caller);
        var keys=jdbc.queryForList("SELECT object_key FROM facility_images WHERE id=? AND facility_id=?",String.class,imageId,facilityId);
        if(keys.isEmpty())throw new ResourceNotFoundException("Image not found");
        try{storage.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(keys.getFirst()).build());}
        catch(Exception ex){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"Image could not be removed",ex);}
        jdbc.update("DELETE FROM facility_images WHERE id=? AND facility_id=?",imageId,facilityId);
    }
    public ImageView replace(UUID facilityId,UUID imageId,MultipartFile file,Caller caller){
        facilities.ownedDetail(facilityId,caller);
        if(jdbc.queryForObject("SELECT count(*) FROM facility_images WHERE id=? AND facility_id=?",Integer.class,imageId,facilityId)==0)
            throw new ResourceNotFoundException("Image not found");
        if(file.isEmpty()||file.getSize()>10*1024*1024)throw new IllegalArgumentException("Image must be between 1 byte and 10 MB");
        String type=validateImage(file);String key="facilities/"+facilityId+"/"+UUID.randomUUID()+(type.equals("image/png")?".png":".jpg");
        try {
            ensureBucket();try(var stream=file.getInputStream()){storage.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(type).stream(stream,file.getSize(),-1).build());}
            UUID court;
            try {court=transactions.execute(status->{
                var rows=jdbc.queryForList("SELECT object_key,court_id FROM facility_images WHERE id=? AND facility_id=? FOR UPDATE",imageId,facilityId);
                if(rows.isEmpty())throw new ResourceNotFoundException("Image not found");var old=rows.getFirst();
                jdbc.update("UPDATE facility_images SET object_key=?,content_type=?,size_bytes=? WHERE id=?",key,type,file.getSize(),imageId);
                jdbc.update("INSERT INTO media_cleanup(object_key) VALUES(?) ON CONFLICT DO NOTHING",old.get("object_key"));
                return (UUID)old.get("court_id");
            });}catch(RuntimeException ex){storage.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());throw ex;}
            return image(imageId,court,key,type,file.getSize());
        }catch(IllegalArgumentException|ResourceNotFoundException ex){throw ex;}
        catch(Exception ex){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"Image replacement failed; the previous database reference was preserved",ex);}
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${sporthub.media.cleanup-delay-ms:2000}")
    public void cleanupReplacedObjects(){
        for(var row:jdbc.queryForList("SELECT object_key FROM media_cleanup WHERE next_attempt_at<=NOW() ORDER BY created_at LIMIT 20")){
            String key=(String)row.get("object_key");
            try{storage.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());jdbc.update("DELETE FROM media_cleanup WHERE object_key=?",key);}
            catch(Exception ex){jdbc.update("UPDATE media_cleanup SET attempts=attempts+1,next_attempt_at=NOW()+INTERVAL '30 seconds' WHERE object_key=?",key);}
        }
    }
    private synchronized void ensureBucket()throws Exception{
        if(!storage.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))storage.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
    }
    private ImageView image(UUID id,UUID courtId,String key,String type,long size){
        try{return new ImageView(id,courtId,key,signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET).bucket(bucket).object(key).expiry(900).build()),type,size);}
        catch(Exception ex){throw new IllegalStateException("Image access URL could not be generated",ex);}
    }
    private String validateImage(MultipartFile file){
        try(var input=javax.imageio.ImageIO.createImageInputStream(file.getInputStream())){
            var readers=javax.imageio.ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw new IllegalArgumentException("Only JPEG and PNG images are supported");
            var reader=readers.next();
            try{
                reader.setInput(input);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("png","jpeg","jpg").contains(format))throw new IllegalArgumentException("Only JPEG and PNG images are supported");
                if((long)reader.getWidth(0)*reader.getHeight(0)>20_000_000)throw new IllegalArgumentException("Image dimensions exceed demo technical limit");
                return format.equals("png")?"image/png":"image/jpeg";
            }finally{reader.dispose();}
        }catch(java.io.IOException ex){throw new IllegalArgumentException("Invalid image content",ex);}
    }
}
