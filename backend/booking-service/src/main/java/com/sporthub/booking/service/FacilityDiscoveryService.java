package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.sporthub.booking.service.DiscoveryService.FacilitySummary;

/** Additive facility search. Legacy court discovery and booking writes remain unchanged. */
@Service
public class FacilityDiscoveryService {
    public record Criteria(String q,String provinceCode,String wardCode,UUID sportCategoryId,String date,String opensAfter,String closesBefore,
        BigDecimal minPrice,BigDecimal maxPrice,Double latitude,Double longitude,Double radiusKm,String sort,UUID facilityId,Integer page,Integer size) {}
    public record Sport(UUID id,String name) {}
    public record CourtMatch(UUID courtId,String courtName,UUID sportCategoryId,String sportName,BigDecimal fromPrice,String currency,
        int availableSlots,Instant firstStartsAt,Instant firstEndsAt,List<JsonNode> slots) {}
    public record Result(FacilitySummary facility,String imageUrl,String date,int matchingCourts,List<Sport> sports,BigDecimal fromPrice,String currency,
        int availableSlots,Double distanceKm,List<CourtMatch> courts) {}
    public record Page(List<Result> items,int page,int size,int totalFacilities,int totalPages) {}
    private final DiscoveryDependencies dependencies;
    private final BookingService bookings;
    private final Clock clock;
    private final int advanceDays;
    public FacilityDiscoveryService(DiscoveryDependencies dependencies,BookingService bookings,Clock clock,@Value("${booking.advance-days:90}") int advanceDays) {
        this.dependencies=dependencies;this.bookings=bookings;this.clock=clock;this.advanceDays=advanceDays;
    }
    public Page search(Criteria c) {
        LocalDate day; LocalTime after,before;
        try {day=c.date()==null?LocalDate.now(clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh"))):LocalDate.parse(c.date());after=c.opensAfter()==null?null:LocalTime.parse(c.opensAfter());before=c.closesBefore()==null?null:LocalTime.parse(c.closesBefore());}
        catch(DateTimeException ex){throw new IllegalArgumentException("Use an ISO date and HH:mm time window");}
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh")));
        if(day.isBefore(today)||day.isAfter(today.plusDays(advanceDays)))throw new IllegalArgumentException("Choose a date inside the booking window");
        if(after!=null&&before!=null&&!after.isBefore(before))throw new IllegalArgumentException("The time window must end after it starts");
        if(c.minPrice()!=null&&c.minPrice().signum()<0||c.maxPrice()!=null&&c.maxPrice().signum()<0||c.minPrice()!=null&&c.maxPrice()!=null&&c.minPrice().compareTo(c.maxPrice())>0)throw new IllegalArgumentException("Invalid per-slot price range");
        int page=c.page()==null?1:c.page(),size=c.size()==null?12:c.size();
        if(page<1||size<1||size>48)throw new IllegalArgumentException("Page must be positive and size must be 1–48");
        boolean origin=c.latitude()!=null&&c.longitude()!=null;
        if((c.latitude()!=null||c.longitude()!=null)&&!origin||origin&&(!Double.isFinite(c.latitude())||!Double.isFinite(c.longitude())||Math.abs(c.latitude())>90||Math.abs(c.longitude())>180)||c.radiusKm()!=null&&(!origin||!Double.isFinite(c.radiusKm())||c.radiusKm()<=0||c.radiusKm()>500))throw new IllegalArgumentException("Invalid coordinates or radius");
        String sort=c.sort()==null?"NAME":c.sort();
        if(!Set.of("NAME","PRICE_ASC","PRICE_DESC","DISTANCE").contains(sort)||sort.equals("DISTANCE")&&!origin)throw new IllegalArgumentException("Invalid search sort");
        var results=new ArrayList<Result>();
        try {
            var catalog=dependencies.catalog(c.q(),c.provinceCode(),c.wardCode(),c.sportCategoryId(),c.facilityId());
            var names=new HashMap<UUID,String>();dependencies.categories().forEach(s -> {if(s.path("active").asBoolean())names.put(UUID.fromString(s.path("id").asText()),s.path("name").asText());});
            var candidates=new ArrayList<JsonNode>();var ids=new LinkedHashSet<UUID>();
            for(var entry:catalog) {
                var f=entry.path("facility");
                if(c.provinceCode()!=null&&!c.provinceCode().equals(f.path("provinceCode").asText())||c.wardCode()!=null&&!c.wardCode().equals(f.path("wardCode").asText()))continue;
                Double distance=distance(c,f);
                if(c.radiusKm()!=null&&(distance==null||distance>c.radiusKm()))continue;
                candidates.add(entry);
                for(var court:entry.path("courts"))if(court.path("enabled").asBoolean()&&(c.sportCategoryId()==null||c.sportCategoryId().toString().equals(court.path("sportCategoryId").asText())))ids.add(UUID.fromString(court.path("id").asText()));
            }
            var previews=bookings.availabilityBatch(List.copyOf(ids),day.toString());
            for(var entry:candidates) {
                var f=entry.path("facility");var matches=new ArrayList<CourtMatch>();
                var zone=ZoneId.of(f.path("timezone").asText());
                Instant start=after==null?null:day.atTime(after).atZone(zone).toInstant(),end=before==null?null:day.atTime(before).atZone(zone).toInstant();
                for(var court:entry.path("courts")) {
                    UUID id=UUID.fromString(court.path("id").asText()),category=UUID.fromString(court.path("sportCategoryId").asText());
                    var preview=previews.get(id);if(preview==null||!names.containsKey(category))continue;
                    var slots=matchingSlots(preview,c,start,end,clock.instant(),advanceDays);
                    if(slots.isEmpty())continue;
                    var cheapest=slots.stream().min(Comparator.comparing(s -> s.path("amount").decimalValue())).orElseThrow();
                    var first=slots.getFirst();matches.add(new CourtMatch(id,court.path("name").asText(),category,names.get(category),cheapest.path("amount").decimalValue(),cheapest.path("currency").asText("VND"),slots.size(),Instant.parse(first.path("startsAt").asText()),Instant.parse(first.path("endsAt").asText()),slots));
                }
                if(matches.isEmpty())continue;
                matches.sort(Comparator.comparing(CourtMatch::courtName).thenComparing(CourtMatch::courtId));
                var cheapest=matches.stream().min(Comparator.comparing(CourtMatch::fromPrice)).orElseThrow();
                var amenities=new ArrayList<String>();f.path("amenities").forEach(a -> amenities.add(a.asText()));
                var summary=new FacilitySummary(UUID.fromString(f.path("id").asText()),f.path("name").asText(),f.path("addressLine").asText(),f.path("provinceCode").asText(null),f.path("province").asText(),f.path("wardCode").asText(null),f.path("ward").asText(),f.path("timezone").asText(),number(f,"latitude"),number(f,"longitude"),amenities);
                var sports=matches.stream().map(m -> new Sport(m.sportCategoryId(),m.sportName())).distinct().sorted(Comparator.comparing(Sport::name)).toList();
                results.add(new Result(summary,entry.path("imageUrl").asText(null),day.toString(),matches.size(),sports,cheapest.fromPrice(),cheapest.currency(),matches.stream().mapToInt(CourtMatch::availableSlots).sum(),distance(c,f),List.copyOf(matches)));
            }
        } catch(org.springframework.web.client.RestClientException|com.sporthub.common.exception.ResourceNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Search availability changed or is unavailable; please retry",ex);
        }
        return paginate(results,sort,page,size);
    }
    static Page paginate(List<Result> items,String sort,int page,int size) {
        Comparator<Result> order=switch(sort) {case "PRICE_ASC" -> Comparator.comparing(Result::fromPrice);case "PRICE_DESC" -> Comparator.comparing(Result::fromPrice).reversed();case "DISTANCE" -> Comparator.comparing(Result::distanceKm,Comparator.nullsLast(Double::compareTo));default -> Comparator.comparing(r -> r.facility().name());};
        var sorted=items.stream().sorted(order.thenComparing(r -> r.facility().name()).thenComparing(r -> r.facility().id())).toList();
        long offset=(long)(page-1)*size;int total=sorted.size();
        return new Page(offset>=total?List.of():sorted.subList((int)offset,(int)Math.min(offset+size,total)),page,size,total,(int)(((long)total+size-1)/size));
    }
    static List<JsonNode> matchingSlots(JsonNode preview,Criteria c,Instant start,Instant end,Instant now,int advanceDays) {
        var slots=new ArrayList<JsonNode>();
        for(var s:preview.path("slots")) {
            if(!s.path("state").asText().equals("AVAILABLE")||!s.path("amount").isNumber())continue;
            Instant a=Instant.parse(s.path("startsAt").asText()),b=Instant.parse(s.path("endsAt").asText());
            if(!a.isAfter(now)||a.isAfter(now.plusSeconds(advanceDays*86400L))||start!=null&&a.isBefore(start)||end!=null&&b.isAfter(end))continue;
            var price=s.path("amount").decimalValue();if(c.minPrice()!=null&&price.compareTo(c.minPrice())<0||c.maxPrice()!=null&&price.compareTo(c.maxPrice())>0)continue;
            slots.add(s);
        }
        slots.sort(Comparator.comparing(s -> Instant.parse(s.path("startsAt").asText())));
        if(start!=null&&end!=null) {
            Instant cursor=start;
            for(var s:slots) {if(!Instant.parse(s.path("startsAt").asText()).equals(cursor))return List.of();cursor=Instant.parse(s.path("endsAt").asText());}
            if(!cursor.equals(end))return List.of();
        }
        return List.copyOf(slots);
    }
    private static Double number(JsonNode f,String key){return f.path(key).isNumber()?f.path(key).doubleValue():null;}
    private Double distance(Criteria c,JsonNode f) {
        Double lat=number(f,"latitude"),lon=number(f,"longitude");if(c.latitude()==null||lat==null||lon==null)return null;
        double a=Math.pow(Math.sin(Math.toRadians(lat-c.latitude())/2),2)+Math.cos(Math.toRadians(c.latitude()))*Math.cos(Math.toRadians(lat))*Math.pow(Math.sin(Math.toRadians(lon-c.longitude())/2),2);
        return 6371.0088*2*Math.asin(Math.sqrt(Math.min(1,a)));
    }
}
