package com.sporthub.facility.service;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.UUID;
@Component
public class FacilityReviewDependencies {
 private final RestClient schedule;
 public FacilityReviewDependencies(@Value("${SCHEDULE_SERVICE_URL:http://localhost:8083}") String url){var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());factory.setReadTimeout(java.time.Duration.ofSeconds(5));schedule=RestClient.builder().baseUrl(url).requestFactory(factory).build();}
 public JsonNode hours(UUID id,String token){return schedule.get().uri("/api/v1/schedules/facilities/"+id+"/hours").header("Authorization",token).retrieve().body(JsonNode.class).path("data");}
 public JsonNode prices(UUID id,String token){return schedule.get().uri("/api/v1/schedules/facilities/"+id+"/pricing").header("Authorization",token).retrieve().body(JsonNode.class).path("data");}
 public JsonNode preview(UUID facility,UUID court,java.time.LocalDate date,String token){return schedule.get().uri("/api/v1/schedules/facilities/"+facility+"/preview?courtId="+court+"&date="+date).header("Authorization",token).retrieve().body(JsonNode.class).path("data");}
}
