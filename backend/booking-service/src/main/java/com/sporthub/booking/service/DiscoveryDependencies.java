package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import java.util.UUID;

@Component
public class DiscoveryDependencies {
    private final RestClient facility;
    public DiscoveryDependencies(@Value("${FACILITY_SERVICE_URL:http://localhost:8082}") String url) {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        facility = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public JsonNode facilities(String q, String province, String district, UUID category) {
        return facility.get().uri(uri -> {
            uri.path("/api/v1/facilities");
            if(q!=null)uri.queryParam("q",q);if(province!=null)uri.queryParam("province",province);if(district!=null)uri.queryParam("district",district);if(category!=null)uri.queryParam("sportCategoryId",category);
            return uri.build();
        }).retrieve().body(JsonNode.class).path("data");
    }
    public JsonNode courts(UUID id) { return facility.get().uri("/api/v1/facilities/"+id+"/courts").retrieve().body(JsonNode.class).path("data"); }
    public JsonNode categories() { return facility.get().uri("/api/v1/sport-categories").retrieve().body(JsonNode.class).path("data"); }
}
