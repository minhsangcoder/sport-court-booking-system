package com.sporthub.schedule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.common.exception.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.time.*;
import java.util.*;

/** Reads Facility-owned data through REST; never reads its database. */
@Component
public class FacilityClient {
    private final RestClient client;
    public FacilityClient(@Value("${FACILITY_SERVICE_URL:http://localhost:8082}") String url) {
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public void requireOwner(UUID facilityId,String token) {
        read("/api/v1/owner/facilities/"+facilityId+"/write-access",token);
    }
    public void requireReader(UUID facilityId,String token){read("/api/v1/owner/facilities/"+facilityId,token);}
    public void requireScheduleManager(UUID facilityId,String token){read("/api/v1/facilities/"+facilityId+"/operations/access?permission=SCHEDULE_MANAGE",token);}
    public Configuration configuration(UUID facilityId,String token) {
        JsonNode data;
        try{data=read("/api/v1/owner/facilities/"+facilityId,token);}catch(ForbiddenException ex){data=read("/api/v1/facilities/"+facilityId+"/operations/access?permission=SCHEDULE_MANAGE",token);}
        return new Configuration(ZoneId.of(data.path("timezone").asText()),data.path("status").asText());
    }
    public record Configuration(ZoneId timezone,String status) {}
    public boolean applicationCommitted(UUID application,String secret,String identityUrl){
        String path="/api/v1/internal/owner-applications/committed";String body="{\"applicationIds\":[\""+application+"\"]}";long now=Instant.now().getEpochSecond();
        var result=client.post().uri(identityUrl+path).contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("X-Service-Time",String.valueOf(now)).header("X-Service-Signature",com.sporthub.common.security.ServiceCalls.sign(secret,"POST",path,now,body)).body(body).retrieve().body(JsonNode.class);return result!=null&&result.path("data").size()==1;
    }
    public Context context(UUID courtId,String ownerToken) {
        var data=read(ownerToken==null?"/api/v1/facilities/courts/"+courtId+"/context":
            "/api/v1/owner/courts/"+courtId+"/context",ownerToken);
        return parseContext(data);
    }
    public List<Context> contexts(List<UUID> ids) {
        var data=read("/api/v1/facilities/courts/contexts?ids="+String.join(",",ids.stream().map(UUID::toString).toList()),null);
        var result=new ArrayList<Context>(); data.forEach(value -> result.add(parseContext(value))); return result;
    }
    private Context parseContext(JsonNode data) {
        var f=data.path("facility");var c=data.path("court");
        var windows=new ArrayList<Window>();
        data.path("maintenance").forEach(m->windows.add(new Window(Instant.parse(m.path("startsAt").asText()),Instant.parse(m.path("endsAt").asText()))));
        return new Context(UUID.fromString(f.path("id").asText()),UUID.fromString(c.path("id").asText()),
            UUID.fromString(c.path("sportCategoryId").asText()),ZoneId.of(f.path("timezone").asText()),
            c.path("enabled").asBoolean(),f.path("status").asText(),List.copyOf(windows),uuid(c,"courtTypeId"));
    }
    private JsonNode read(String path,String token) {
        try {
            var request=client.get().uri(path);
            if(token!=null)request.header("Authorization",token);
            return request.retrieve().body(JsonNode.class).path("data");
        } catch(org.springframework.web.client.HttpClientErrorException ex) {
            if(ex.getStatusCode().value()==403)throw new ForbiddenException("Facility is outside your ownership");
            if(ex.getStatusCode().value()==401)throw new UnauthorizedException("Authentication required");
            if(ex.getStatusCode().value()==409)throw new ConflictException("Facility configuration is frozen during review");
            throw new ResourceNotFoundException("Facility or court is unavailable");
        }
    }
    public record Window(Instant startsAt,Instant endsAt) {}
    public record Context(UUID facilityId,UUID courtId,UUID sportCategoryId,ZoneId timezone,
        boolean enabled,String status,List<Window> maintenance,UUID courtTypeId) {
        public Context(UUID facility,UUID court,UUID category,ZoneId zone,boolean enabled,String status,List<Window> maintenance){this(facility,court,category,zone,enabled,status,maintenance,null);}
    }
    public List<Context> courts(UUID facility,String token){var result=new ArrayList<Context>();for(var c:read(token==null?"/api/v1/facilities/"+facility+"/courts":"/api/v1/owner/facilities/"+facility+"/courts",token))result.add(context(UUID.fromString(c.path("id").asText()),token));return List.copyOf(result);}
    public void requireCategory(UUID category){boolean found=false;for(var c:read("/api/v1/sport-categories",null))if(category.toString().equals(c.path("id").asText())&&c.path("active").asBoolean())found=true;if(!found)throw new IllegalArgumentException("Sport category must be active");}
    public void requireType(UUID facility,UUID type,String token){boolean found=false;for(var t:read(token==null?"/api/v1/facilities/"+facility+"/court-types":"/api/v1/owner/facilities/"+facility+"/court-types",token))if(type.toString().equals(t.path("id").asText())&&t.path("active").asBoolean())found=true;if(!found)throw new IllegalArgumentException("Court type must be active and belong to the facility");}
    private static UUID uuid(JsonNode node,String field){return node.path(field).isMissingNode()||node.path(field).isNull()?null:UUID.fromString(node.path(field).asText());}
}
