package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class DiscoveryService {
    public record Criteria(String q,String province,String district,UUID sportCategoryId,String date,String opensAfter,String closesBefore,
                           BigDecimal minPrice,BigDecimal maxPrice,Double latitude,Double longitude,Double radiusKm,String sort) {}
    public record FacilitySummary(UUID id,String name,String addressLine,String province,String district,String ward,String timezone,
                                  Double latitude,Double longitude,List<String> amenities) {}
    public record Result(FacilitySummary facility,UUID courtId,String courtName,UUID sportCategoryId,String sportName,String date,
                         BigDecimal fromPrice,String currency,int availableSlots,Instant firstStartsAt,Instant firstEndsAt,Double distanceKm) {}
    public record SearchResult(List<Result> items,boolean truncated) {}
    private final DiscoveryDependencies dependencies;private final BookingService bookings;private final Clock clock;private final int advanceDays;
    public DiscoveryService(DiscoveryDependencies dependencies,BookingService bookings,Clock clock,@org.springframework.beans.factory.annotation.Value("${booking.advance-days:90}") int advanceDays){this.dependencies=dependencies;this.bookings=bookings;this.clock=clock;this.advanceDays=advanceDays;}
    public SearchResult search(Criteria input){
        text(input.q());text(input.province());text(input.district());
        var today=clock.instant().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
        LocalDate day;LocalTime after,before;
        try {day=input.date()==null?today:LocalDate.parse(input.date());after=input.opensAfter()==null?null:LocalTime.parse(input.opensAfter());before=input.closesBefore()==null?null:LocalTime.parse(input.closesBefore());}
        catch(DateTimeException ex){throw new IllegalArgumentException("Use a valid ISO play date and HH:mm time window");}
        if(day.isBefore(today)||day.isAfter(today.plusDays(advanceDays)))throw new IllegalArgumentException("Choose a play date inside the booking window");
        if(after!=null&&before!=null&&!after.isBefore(before))throw new IllegalArgumentException("The time window must end after it starts");
        if((input.minPrice()!=null&&input.minPrice().signum()<0)||(input.maxPrice()!=null&&input.maxPrice().signum()<0)||(input.minPrice()!=null&&input.maxPrice()!=null&&input.minPrice().compareTo(input.maxPrice())>0))throw new IllegalArgumentException("Invalid per-slot price range");
        boolean origin=input.latitude()!=null&&input.longitude()!=null;
        if((input.latitude()!=null||input.longitude()!=null)&&!origin || origin&&(!Double.isFinite(input.latitude())||!Double.isFinite(input.longitude())||Math.abs(input.latitude())>90||Math.abs(input.longitude())>180) || input.radiusKm()!=null&&(!origin||!Double.isFinite(input.radiusKm())||input.radiusKm()<=0||input.radiusKm()>500))throw new IllegalArgumentException("Supply valid latitude/longitude and a radius of up to 500 km");
        String sort=input.sort()==null?"NAME":input.sort();if(!Set.of("NAME","PRICE_ASC","PRICE_DESC","DISTANCE").contains(sort)||sort.equals("DISTANCE")&&!origin)throw new IllegalArgumentException("Choose a valid sort; distance requires latitude/longitude");
        var results=new ArrayList<Result>();boolean truncated=false;int scanned=0;
        try{
            var names=new HashMap<UUID,String>();for(var category:dependencies.categories())if(category.path("active").asBoolean())names.put(UUID.fromString(category.path("id").asText()),category.path("name").asText());
            var facilities=dependencies.facilities(input.q(),input.province(),input.district(),input.sportCategoryId());truncated=facilities.size()>=200;
            outer:for(var f:facilities){
                Double lat=f.path("latitude").isNumber()?f.path("latitude").doubleValue():null,lon=f.path("longitude").isNumber()?f.path("longitude").doubleValue():null;
                Double distance=origin&&lat!=null&&lon!=null?distance(input.latitude(),input.longitude(),lat,lon):null;
                if(input.radiusKm()!=null&&(distance==null||distance>input.radiusKm()))continue;
                UUID facilityId=UUID.fromString(f.path("id").asText());var amenities=new ArrayList<String>();f.path("amenities").forEach(a->amenities.add(a.asText()));
                var summary=new FacilitySummary(facilityId,f.path("name").asText(),f.path("addressLine").asText(),f.path("province").asText(),f.path("district").asText(),f.path("ward").asText(),f.path("timezone").asText(),lat,lon,amenities);
                for(var court:dependencies.courts(facilityId)){
                    UUID category=UUID.fromString(court.path("sportCategoryId").asText());if(!court.path("enabled").asBoolean()||!names.containsKey(category)||input.sportCategoryId()!=null&&!input.sportCategoryId().equals(category))continue;
                    if(scanned++>=200){truncated=true;break outer;}
                    UUID courtId=UUID.fromString(court.path("id").asText());var preview=bookings.availability(courtId,day.toString());var slots=new ArrayList<JsonNode>();var zone=ZoneId.of(summary.timezone());
                    for(var slot:preview.path("slots")){
                        if(!slot.path("state").asText().equals("AVAILABLE")||!slot.path("amount").isNumber())continue;
                        Instant start=Instant.parse(slot.path("startsAt").asText()),end=Instant.parse(slot.path("endsAt").asText());
                        if(start.isAfter(clock.instant().plusSeconds(advanceDays*86400L)))continue;
                        var amount=slot.path("amount").decimalValue();
                        if(after!=null&&start.atZone(zone).toLocalTime().isBefore(after)||before!=null&&end.atZone(zone).toLocalTime().isAfter(before)||input.minPrice()!=null&&amount.compareTo(input.minPrice())<0||input.maxPrice()!=null&&amount.compareTo(input.maxPrice())>0)continue;
                        slots.add(slot);
                    }
                    if(slots.isEmpty())continue;
                    var first=slots.stream().min(Comparator.comparing(s->s.path("startsAt").asText())).orElseThrow();var cheapest=slots.stream().min(Comparator.comparing(s->s.path("amount").decimalValue())).orElseThrow();
                    results.add(new Result(summary,courtId,court.path("name").asText(),category,names.get(category),day.toString(),cheapest.path("amount").decimalValue(),cheapest.path("currency").asText("VND"),slots.size(),Instant.parse(first.path("startsAt").asText()),Instant.parse(first.path("endsAt").asText()),distance));
                }
            }
        }catch(org.springframework.web.client.RestClientException ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Search data is temporarily unavailable; please retry",ex);}catch(com.sporthub.common.exception.ResourceNotFoundException ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Court availability changed during search; please retry",ex);}
        Comparator<Result> comparator=switch(sort){case "PRICE_ASC"->Comparator.comparing(Result::fromPrice);case "PRICE_DESC"->Comparator.comparing(Result::fromPrice).reversed();case "DISTANCE"->Comparator.comparing(Result::distanceKm,Comparator.nullsLast(Double::compareTo));default->Comparator.comparing(r->r.facility().name());};
        results.sort(comparator.thenComparing(Result::courtName).thenComparing(Result::courtId));if(results.size()>100)truncated=true;
        return new SearchResult(results.stream().limit(100).toList(),truncated);
    }
    private void text(String value){if(value!=null&&value.length()>180)throw new IllegalArgumentException("Search text must not exceed 180 characters");}
    private double distance(double lat1,double lon1,double lat2,double lon2){double a=Math.pow(Math.sin(Math.toRadians(lat2-lat1)/2),2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.pow(Math.sin(Math.toRadians(lon2-lon1)/2),2);return 6371.0088*2*Math.asin(Math.sqrt(Math.min(1,a)));}
}
