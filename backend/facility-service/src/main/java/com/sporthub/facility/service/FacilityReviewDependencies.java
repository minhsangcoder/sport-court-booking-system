package com.sporthub.facility.service;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.UUID;
@Component
public class FacilityReviewDependencies {
 private final RestClient schedule;
 private final String secret;private final com.fasterxml.jackson.databind.ObjectMapper json;
 public FacilityReviewDependencies(@Value("${SCHEDULE_SERVICE_URL:http://localhost:8083}") String url,@Value("${SERVICE_CALL_SECRET:}") String secret,com.fasterxml.jackson.databind.ObjectMapper json){this.secret=secret;this.json=json;var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());factory.setReadTimeout(java.time.Duration.ofSeconds(10));schedule=RestClient.builder().baseUrl(url).requestFactory(factory).build();}
 public JsonNode applicationSnapshot(UUID application,UUID facility,String timezone,java.util.List<java.util.Map<String,Object>> courts,boolean release){String path="/api/v1/internal/schedules/application-snapshot";String body=json.valueToTree(java.util.Map.of("applicationId",application,"facilityId",facility,"timezone",timezone,"courts",courts,"release",release)).toString();long now=java.time.Instant.now().getEpochSecond();return schedule.post().uri(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("X-Service-Time",String.valueOf(now)).header("X-Service-Signature",com.sporthub.common.security.ServiceCalls.sign(secret,"POST",path,now,body)).body(body).retrieve().body(JsonNode.class).path("data");}
 public JsonNode hours(UUID id,String token){return schedule.get().uri("/api/v1/schedules/facilities/"+id+"/hours").header("Authorization",token).retrieve().body(JsonNode.class).path("data");}
 public JsonNode prices(UUID id,String token){return schedule.get().uri("/api/v1/schedules/facilities/"+id+"/pricing").header("Authorization",token).retrieve().body(JsonNode.class).path("data");}
 public JsonNode preview(UUID facility,UUID court,java.time.LocalDate date,String token){return schedule.get().uri("/api/v1/schedules/facilities/"+facility+"/preview?courtId="+court+"&date="+date).header("Authorization",token).retrieve().body(JsonNode.class).path("data");}
}
