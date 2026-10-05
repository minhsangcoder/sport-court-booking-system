package com.sporthub.facility.service;
import com.fasterxml.jackson.databind.*;
import com.sporthub.common.security.ServiceCalls;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.*;
@Component
public class OwnerApplicationGate {
 private final RestClient identity;private final String secret;private final ObjectMapper json;
 public OwnerApplicationGate(@Value("${IDENTITY_SERVICE_URL:http://localhost:8081}") String url,@Value("${SERVICE_CALL_SECRET:}") String secret,ObjectMapper json){var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());factory.setReadTimeout(java.time.Duration.ofSeconds(5));identity=RestClient.builder().baseUrl(url).requestFactory(factory).build();this.secret=secret;this.json=json;}
 public Set<UUID> approved(Collection<UUID> ids){if(ids.isEmpty())return Set.of();String path="/api/v1/internal/owner-applications/committed";String body=json.valueToTree(Map.of("applicationIds",ids)).toString();long now=java.time.Instant.now().getEpochSecond();try{var response=identity.post().uri(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("X-Service-Time",String.valueOf(now)).header("X-Service-Signature",ServiceCalls.sign(secret,"POST",path,now,body)).body(body).retrieve().body(JsonNode.class);var result=new HashSet<UUID>();response.path("data").forEach(id->result.add(UUID.fromString(id.asText())));return Set.copyOf(result);}catch(org.springframework.web.client.RestClientException ex){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"First-facility approval could not be verified",ex);}}
}
