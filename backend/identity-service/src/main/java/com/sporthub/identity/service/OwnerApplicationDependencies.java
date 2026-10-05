package com.sporthub.identity.service;
import com.fasterxml.jackson.databind.*;
import com.sporthub.common.security.ServiceCalls;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.*;
@Component
public class OwnerApplicationDependencies {
 private final RestClient facility,payment;private final ObjectMapper json;private final String secret;
 public OwnerApplicationDependencies(@Value("${FACILITY_SERVICE_URL:http://localhost:8082}") String facilityUrl,@Value("${PAYMENT_SERVICE_URL:http://localhost:8085}") String paymentUrl,@Value("${SERVICE_CALL_SECRET:}") String secret,ObjectMapper json){this.secret=secret;this.json=json;var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());factory.setReadTimeout(java.time.Duration.ofSeconds(10));facility=RestClient.builder().baseUrl(facilityUrl).requestFactory(factory).build();payment=RestClient.builder().baseUrl(paymentUrl).requestFactory(factory).build();}
 public JsonNode facility(UUID id,String command,Map<String,Object> input,String token){return call(facility,"/api/v1/internal/owner-applications/"+id+"/"+command,input,token);}
 public JsonNode wallet(UUID id,UUID user,java.math.BigDecimal commission){return call(payment,"/api/v1/internal/owner-wallets/prepare",Map.of("applicationId",id,"userId",user,"commissionPercent",commission),null);}
 private JsonNode call(RestClient client,String path,Map<String,Object> input,String token){String body=json.valueToTree(input).toString();long now=java.time.Instant.now().getEpochSecond();var request=client.post().uri(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("X-Service-Time",String.valueOf(now)).header("X-Service-Signature",ServiceCalls.sign(secret,"POST",path,now,body));if(token!=null)request.header("Authorization",token);var response=request.body(body).retrieve().body(JsonNode.class);if(response==null||response.path("data").isMissingNode())throw new IllegalStateException("Application dependency returned no result");return response.path("data");}
}
