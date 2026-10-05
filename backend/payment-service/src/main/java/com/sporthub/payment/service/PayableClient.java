package com.sporthub.payment.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.common.exception.ConflictException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import java.util.UUID;
@Component
public class PayableClient {
 private final RestClient booking,transfer;
 public PayableClient(@Value("${BOOKING_SERVICE_URL:http://localhost:8084}") String url,@Value("${TRANSFER_SERVICE_URL:http://localhost:8086}") String transferUrl){var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());factory.setReadTimeout(java.time.Duration.ofSeconds(5));booking=RestClient.builder().baseUrl(url).requestFactory(factory).build();transfer=RestClient.builder().baseUrl(transferUrl).requestFactory(factory).build();}
 public JsonNode payable(UUID id,UUID member,String token){try{return booking.get().uri(member==null?"/api/v1/bookings/"+id+"/payable":"/api/v1/groups/"+id+"/members/"+member+"/payable").header("Authorization",token).retrieve().body(JsonNode.class).path("data");}catch(org.springframework.web.client.HttpClientErrorException ex){throw new ConflictException("Booking or contribution is no longer payable");}}
 public JsonNode transfer(UUID acquisition,String token){try{return transfer.get().uri("/api/v1/transfers/acquisitions/"+acquisition+"/payable").header("Authorization",token).retrieve().body(JsonNode.class).path("data");}catch(org.springframework.web.client.HttpClientErrorException ex){throw new ConflictException("Transfer acquisition is no longer payable");}}
}
