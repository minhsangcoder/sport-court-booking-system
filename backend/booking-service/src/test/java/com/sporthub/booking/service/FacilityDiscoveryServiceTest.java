package com.sporthub.booking.service;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;

class FacilityDiscoveryServiceTest {
    final ObjectMapper json=new ObjectMapper();
    DiscoveryDependencies dependencies; BookingService bookings; FacilityDiscoveryService service;
    final UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c1=UUID.randomUUID(),c2=UUID.randomUUID(),c3=UUID.randomUUID(),c4=UUID.randomUUID(),sport=UUID.randomUUID(),other=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-12T06:00:00Z");
    Map<UUID,JsonNode> previews;
    @BeforeEach void setup() {
        dependencies=mock(DiscoveryDependencies.class);bookings=mock(BookingService.class);
        service=new FacilityDiscoveryService(dependencies,bookings,Clock.fixed(now,ZoneOffset.UTC),90);
        when(dependencies.categories()).thenReturn(json.valueToTree(List.of(Map.of("id",sport,"name","Pickleball","active",true),Map.of("id",other,"name","Tennis","active",true))));
        when(dependencies.catalog(any(),any(),any(),any(),any())).thenReturn(json.valueToTree(List.of(entry(a,"Alpha","00166",List.of(court(c1,sport),court(c2,sport),court(c3,other))),entry(b,"Beta","00175",List.of(court(c4,sport))))));
        previews=new LinkedHashMap<>();previews.put(c1,preview(100,120));previews.put(c2,preview(50,70));previews.put(c3,preview(200,220));previews.put(c4,preview(40,60));
        when(bookings.availabilityBatch(anyList(),eq("2026-10-13"))).thenAnswer(call -> {
            var result=new LinkedHashMap<UUID,JsonNode>();for(UUID id:call.<List<UUID>>getArgument(0))result.put(id,previews.get(id));return result;
        });
    }
    Map<String,Object> entry(UUID id,String name,String ward,List<Map<String,Object>> courts) {
        return Map.of("facility",Map.of("id",id,"name",name,"addressLine","Demo address","provinceCode","01","province","Hà Nội","wardCode",ward,"ward","Ward","timezone","Asia/Ho_Chi_Minh","amenities",List.of()),"courts",courts,"imageUrl","/demo.jpg");
    }
    Map<String,Object> court(UUID id,UUID category){return Map.of("id",id,"name",id.toString(),"sportCategoryId",category,"enabled",true);}
    ObjectNode preview(int first,int second) {var p=json.createObjectNode();var slots=p.putArray("slots");slots.add(slot("01:00","02:00",first));slots.add(slot("02:00","03:00",second));return p;}
    ObjectNode slot(String start,String end,int price) {var s=json.createObjectNode();s.put("startsAt","2026-10-13T"+start+":00Z");s.put("endsAt","2026-10-13T"+end+":00Z");s.put("state","AVAILABLE");s.put("amount",price);s.put("currency","VND");return s;}
    FacilityDiscoveryService.Criteria criteria(UUID category,String ward,String after,String before,BigDecimal min,BigDecimal max,String sort,int page,int size) {
        return new FacilityDiscoveryService.Criteria(null,"01",ward,category,"2026-10-13",after,before,min,max,null,null,null,sort,null,page,size);
    }
    @Test void groupsAllMatchesBeforePaginationWithoutLosingAvailability() {
        var result=service.search(criteria(null,null,null,null,null,null,"NAME",1,1));
        assertThat(result.totalFacilities()).isEqualTo(2);assertThat(result.totalPages()).isEqualTo(2);assertThat(result.items()).hasSize(1);
        var first=result.items().getFirst();assertThat(first.facility().id()).isEqualTo(a);assertThat(first.matchingCourts()).isEqualTo(3);
        assertThat(first.availableSlots()).isEqualTo(6);assertThat(first.sports()).hasSize(2);assertThat(first.fromPrice()).isEqualByComparingTo("50");assertThat(first.courts()).hasSize(3);assertThat(first.imageUrl()).isEqualTo("/demo.jpg");
        verify(bookings,times(1)).availabilityBatch(anyList(),eq("2026-10-13"));verify(bookings,never()).availability(any(),any());verify(dependencies,never()).courts(any());
    }
    @Test void facilityPaginationAndSortDoNotSplitCourts() {
        var page=service.search(criteria(null,null,null,null,null,null,"PRICE_ASC",1,1));assertThat(page.items().getFirst().facility().id()).isEqualTo(b);
        var second=service.search(criteria(null,null,null,null,null,null,"PRICE_ASC",2,1));assertThat(second.items().getFirst().matchingCourts()).isEqualTo(3);
        assertThat(service.search(criteria(null,null,null,null,null,null,"NAME",3,1)).items()).isEmpty();
        assertThat(service.search(criteria(null,null,null,null,null,null,"NAME",Integer.MAX_VALUE,48)).items()).isEmpty();
    }
    @Test void sportWardAndPriceFiltersPrecedeFacilityCounts() {
        var result=service.search(criteria(sport,"00166",null,null,new BigDecimal("80"),new BigDecimal("150"),"NAME",1,12));
        assertThat(result.totalFacilities()).isEqualTo(1);assertThat(result.items().getFirst().matchingCourts()).isEqualTo(1);assertThat(result.items().getFirst().fromPrice()).isEqualByComparingTo("100");
    }
    @Test void fullWindowRequiresExactContinuousPricedAvailability() {
        ((ObjectNode)previews.get(c2).path("slots").get(1)).put("state","BLOCKED").put("reason","HELD");
        ((ObjectNode)previews.get(c3).path("slots").get(1)).putNull("amount");
        var result=service.search(criteria(null,null,"08:00","10:00",null,null,"NAME",1,12));
        assertThat(result.items().getFirst().matchingCourts()).isEqualTo(1);assertThat(result.items().getFirst().availableSlots()).isEqualTo(2);
        assertThat(service.search(criteria(null,null,"08:30","10:00",null,null,"NAME",1,12)).items()).isEmpty();
        assertThat(service.search(criteria(null,null,"08:00","10:30",null,null,"NAME",1,12)).items()).isEmpty();
        assertThat(service.search(criteria(null,null,"08:00","10:00",null,new BigDecimal("50"),"NAME",1,12)).items()).isEmpty();
    }
    @Test void gapBookedMaintenanceAndPastSlotsNeverMatchWholeWindow() {
        var p=preview(100,100);((ObjectNode)p.path("slots").get(1)).put("startsAt","2026-10-13T02:30:00Z");
        var c=criteria(null,null,"08:00","10:00",null,null,"NAME",1,12);
        assertThat(FacilityDiscoveryService.matchingSlots(p,c,Instant.parse("2026-10-13T01:00:00Z"),Instant.parse("2026-10-13T03:00:00Z"),now,90)).isEmpty();
        for(String reason:List.of("BOOKED","MAINTENANCE","HELD")) {p=preview(100,100);((ObjectNode)p.path("slots").get(0)).put("state","BLOCKED").put("reason",reason);assertThat(FacilityDiscoveryService.matchingSlots(p,c,Instant.parse("2026-10-13T01:00:00Z"),Instant.parse("2026-10-13T03:00:00Z"),now,90)).isEmpty();}
        assertThat(FacilityDiscoveryService.matchingSlots(preview(100,100),c,null,null,Instant.parse("2026-10-13T04:00:00Z"),90)).isEmpty();
    }
    @Test void partialTimeFilterRetainsSlotsInsideBound() {assertThat(service.search(criteria(null,null,"09:00",null,null,null,"NAME",1,12)).items().getFirst().availableSlots()).isEqualTo(3);}
    @Test void invalidPaginationWindowAndPriceAreRejected() {
        assertThatThrownBy(() -> service.search(criteria(null,null,null,null,null,null,"NAME",0,12))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(criteria(null,null,null,null,null,null,"NAME",1,49))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(criteria(null,null,"10:00","08:00",null,null,"NAME",1,12))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(criteria(null,null,null,null,new BigDecimal("200"),new BigDecimal("100"),"NAME",1,12))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void controllerBindsFiltersAndFacilityPagination() throws Exception {
        var http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.sporthub.booking.web.FacilityDiscoveryController(service)).build();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/bookings/search/facilities?date=2026-10-13&provinceCode=01&wardCode=00166&page=1&size=1"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.totalFacilities").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].matchingCourts").value(3));
    }
}
