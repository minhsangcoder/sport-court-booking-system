package com.sporthub.transfer.service;
import com.fasterxml.jackson.databind.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.ServiceCalls;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.util.*;
@Component
public class TransferDependencies {
 private final RestClient booking,facility;private final String secret;private final ObjectMapper json;
 public TransferDependencies(@Value("${BOOKING_SERVICE_URL:http://localhost:8084}") String bookUrl,@Value("${FACILITY_SERVICE_URL:http://localhost:8082}") String facUrl,@Value("${SERVICE_CALL_SECRET}") String secret,ObjectMapper json){this.booking=client(bookUrl);this.facility=client(facUrl);this.secret=secret;this.json=json;}
 private RestClient client(String url){var f=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());f.setReadTimeout(java.time.Duration.ofSeconds(5));return RestClient.builder().baseUrl(url).requestFactory(f).build();}
 public JsonNode booking(UUID id){String path="/api/v1/internal/bookings/"+id;long time=Instant.now().getEpochSecond();return booking.get().uri(path).header("X-Service-Time",""+time).header("X-Service-Signature",ServiceCalls.sign(secret,"GET",path,time,"")).retrieve().body(JsonNode.class).path("data");}
 public JsonNode command(String action,Object input){try{String body=json.writeValueAsString(input),path="/api/v1/internal/bookings/transfer/"+action;long time=Instant.now().getEpochSecond();return booking.post().uri(path).header("X-Service-Time",""+time).header("X-Service-Signature",ServiceCalls.sign(secret,"POST",path,time,body)).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class).path("data");}catch(org.springframework.web.client.HttpClientErrorException ex){throw new ConflictException("Booking transfer rejected: "+ex.getStatusCode().value());}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException(ex);}}
 public JsonNode context(UUID court){return facility.get().uri("/api/v1/facilities/courts/"+court+"/context").retrieve().body(JsonNode.class).path("data");}
 public JsonNode policy(UUID facilityId){return facility.get().uri("/api/v1/facilities/"+facilityId+"/transfer-policy").retrieve().body(JsonNode.class).path("data");}
}
