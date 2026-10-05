package com.sporthub.booking.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import java.util.*;
import static com.sporthub.booking.web.BookingDtos.*;

@Component
public class BookingDependencies {
    private final RestClient schedule,facility;
    public BookingDependencies(@Value("${SCHEDULE_SERVICE_URL:http://localhost:8083}") String scheduleUrl,@Value("${FACILITY_SERVICE_URL:http://localhost:8082}") String facilityUrl) {
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());factory.setReadTimeout(Duration.ofSeconds(5));
        schedule=RestClient.builder().baseUrl(scheduleUrl).requestFactory(factory).build();facility=RestClient.builder().baseUrl(facilityUrl).requestFactory(factory).build();
    }
    public JsonNode quote(HoldInput input){try{return schedule.post().uri("/api/v1/schedules/public/quote").body(Map.of("courtId",input.courtId(),"startsAt",input.startsAt(),"endsAt",input.endsAt())).retrieve().body(JsonNode.class).path("data");}catch(org.springframework.web.client.HttpClientErrorException ex){throw new ConflictException("Schedule or pricing is no longer eligible");}}
    public JsonNode preview(UUID court,String date){try{return schedule.get().uri("/api/v1/schedules/public/courts/"+court+"?date="+date).retrieve().body(JsonNode.class).path("data");}catch(org.springframework.web.client.HttpClientErrorException ex){throw new ResourceNotFoundException("Court is unavailable");}}
    public void facilityPermission(UUID facilityId,Caller caller,String token,String permission) {
        if(caller.hasRole("OWNER")) {
            try{facility.get().uri("/api/v1/owner/facilities/"+facilityId).header("Authorization",token).retrieve().toBodilessEntity();return;}
            catch(org.springframework.web.client.HttpClientErrorException ex){if(!caller.hasRole("STAFF"))throw new ForbiddenException("Facility is outside your ownership");}
        }
        if(!caller.hasRole("STAFF") || !caller.facilityBindings().getOrDefault(facilityId,Set.of()).contains(permission))throw new ForbiddenException("Missing facility permission: "+permission);
    }
}
