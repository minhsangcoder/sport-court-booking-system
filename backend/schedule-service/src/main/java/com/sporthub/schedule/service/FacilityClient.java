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
    public boolean applicationCommitted(UUID application,String secret,String identityUrl){
        String path="/api/v1/internal/owner-applications/committed";String body="{\"applicationIds\":[\""+application+"\"]}";long now=Instant.now().getEpochSecond();
        var result=client.post().uri(identityUrl+path).contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("X-Service-Time",String.valueOf(now)).header("X-Service-Signature",com.sporthub.common.security.ServiceCalls.sign(secret,"POST",path,now,body)).body(body).retrieve().body(JsonNode.class);return result!=null&&result.path("data").size()==1;
    }
    public Context context(UUID courtId,String ownerToken) {
        var data=read(ownerToken==null?"/api/v1/facilities/courts/"+courtId+"/context":
            "/api/v1/owner/courts/"+courtId+"/context",ownerToken);
        var f=data.path("facility");var c=data.path("court");
        var windows=new ArrayList<Window>();
        data.path("maintenance").forEach(m->windows.add(new Window(Instant.parse(m.path("startsAt").asText()),Instant.parse(m.path("endsAt").asText()))));
        return new Context(UUID.fromString(f.path("id").asText()),courtId,
            UUID.fromString(c.path("sportCategoryId").asText()),ZoneId.of(f.path("timezone").asText()),
            c.path("enabled").asBoolean(),f.path("status").asText(),List.copyOf(windows));
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
        boolean enabled,String status,List<Window> maintenance) {}
}
