package com.sporthub.schedule;

import static com.sporthub.schedule.web.ScheduleDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sporthub.schedule.service.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@SpringBootTest(properties={"spring.rabbitmq.username=test","spring.rabbitmq.password=test","spring.data.redis.password=test"})
class ScheduleIntegrationTest {
    static PostgreSQLContainer<?> postgres;
    @DynamicPropertySource static void db(DynamicPropertyRegistry p) {
        String url=System.getenv("SPORTHUB_SCHEDULE_TEST_DB_URL");
        if(url!=null) {p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv("SPORTHUB_TEST_DB_USER"));p.add("spring.datasource.password",()->System.getenv("SPORTHUB_TEST_DB_PASSWORD"));}
        else {postgres=new PostgreSQLContainer<>("postgres:16-alpine");postgres.start();p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}
    }
    @AfterAll static void close() {if(postgres!=null)postgres.stop();}
    @MockBean FacilityClient facility;
    @Autowired ScheduleService service;
    UUID facilityId,courtId,categoryId;
    Caller owner=new Caller(UUID.randomUUID(),"Owner",Set.of("OWNER"),Map.of());
    LocalDate day=LocalDate.now().plusDays(7);
    FacilityClient.Context context;
    @BeforeEach void setup() {
        facilityId=UUID.randomUUID();courtId=UUID.randomUUID();categoryId=UUID.randomUUID();
        context=new FacilityClient.Context(facilityId,courtId,categoryId,ZoneId.of("Asia/Ho_Chi_Minh"),true,"ACTIVE",List.of());
        when(facility.context(eq(courtId),any())).thenReturn(context);
    }
    private void hours() {service.replaceHours(facilityId,new HoursInput(null,List.of(new Interval(day.getDayOfWeek().getValue(),LocalTime.of(6,0),LocalTime.of(11,0),60),new Interval(day.getDayOfWeek().getValue(),LocalTime.of(13,0),LocalTime.of(22,0),60))),owner,"token");}
    private PriceInput price(UUID court,UUID category,LocalDate date,int priority,String amount) {return new PriceInput(court,category,date==null?day.getDayOfWeek().getValue():null,date,LocalTime.of(6,0),LocalTime.of(11,0),new BigDecimal(amount),priority,"Rule",day.minusDays(1),day.plusDays(30),"VND");}
    private Instant at(int hour) {return day.atTime(hour,0).atZone(context.timezone()).toInstant();}
    @Test void applicationSnapshotSerializesConfigurationAndOnlyCommittedOwnerCanWrite(){
        hours();
        for(var interval:List.of(new int[]{6,11},new int[]{13,22}))service.addPrice(facilityId,new PriceInput(null,null,day.getDayOfWeek().getValue(),null,LocalTime.of(interval[0],0),LocalTime.of(interval[1],0),new BigDecimal("100000"),0,"First application price",day.minusDays(10),day.plusDays(30),"VND"),owner,"token");
        UUID application=UUID.randomUUID();var snapshot=service.freezeApplication(facilityId,application,List.of(context));assertThat(snapshot).containsKeys("hours","prices");
        var applicant=new Caller(owner.id(),"Applicant",Set.of("CUSTOMER"),Map.of(facilityId,Set.of("APPLICATION_READ","APPLICATION_EDIT")));
        assertThat(service.hours(facilityId,applicant,"token")).hasSize(2);
        assertThatThrownBy(()->service.replaceHours(facilityId,new HoursInput(null,List.of()),applicant,"token")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service.replaceHours(facilityId,new HoursInput(null,List.of()),owner,"token")).isInstanceOf(ConflictException.class);
        when(facility.applicationCommitted(eq(application),anyString(),anyString())).thenReturn(true);
        service.replaceHours(facilityId,new HoursInput(null,List.of()),owner,"token");assertThat(service.hours(facilityId,owner,"token")).isEmpty();
    }
    @Test void multipleIntervalsAndSnapshotTraceArePersistent() {
        hours();var rule=service.addPrice(facilityId,price(null,null,null,0,"100000"),owner,"token");
        var quote=service.quote(new QuoteInput(courtId,at(6),at(8)));
        assertThat(quote.amount()).isEqualByComparingTo("200000");assertThat(quote.segments()).hasSize(2);
        assertThat(quote.segments().get(0).ruleId()).isEqualTo(rule.id());
        assertThatThrownBy(()->service.quote(new QuoteInput(courtId,at(10),at(14)))).isInstanceOf(ConflictException.class);
        assertThat(service.hours(facilityId,owner,"token")).hasSize(2);
        hours();assertThat(service.hours(facilityId,owner,"token")).hasSize(2);
    }
    @Test void priorityMaintenanceAndClosedExceptionsAreEnforced() {
        hours();service.addPrice(facilityId,price(null,categoryId,null,999,"100000"),owner,"token");
        var special=service.addPrice(facilityId,price(null,null,day,0,"200000"),owner,"token");
        assertThat(service.quote(new QuoteInput(courtId,at(6),at(7))).segments().get(0).ruleId()).isEqualTo(special.id());
        service.addException(facilityId,new ExceptionInput(null,day,"CLOSED",null,null,"Holiday"),owner,"token");
        when(facility.context(eq(courtId),any())).thenReturn(new FacilityClient.Context(facilityId,courtId,categoryId,context.timezone(),true,"ACTIVE",List.of(new FacilityClient.Window(at(6),at(7)))));
        var preview=service.publicPreview(courtId,day);
        assertThat(preview.slots().get(0).reason()).isEqualTo("MAINTENANCE");assertThat(preview.slots().get(1).reason()).isEqualTo("CLOSED_EXCEPTION");
        assertThatThrownBy(()->service.quote(new QuoteInput(courtId,at(6),at(7)))).isInstanceOf(ConflictException.class);
    }
    @Test void ambiguousOverlapsAndForeignScopeAreRejected() {
        hours();service.addPrice(facilityId,price(null,null,null,0,"100000"),owner,"token");
        assertThatThrownBy(()->service.addPrice(facilityId,price(null,null,null,0,"200000"),owner,"token")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service.replaceHours(facilityId,new HoursInput(null,List.of(new Interval(1,LocalTime.of(6,0),LocalTime.of(10,0),60),new Interval(1,LocalTime.of(9,0),LocalTime.of(11,0),60))),owner,"token")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service.replaceHours(UUID.randomUUID(),new HoursInput(courtId,List.of()),owner,"token")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(()->service.hours(facilityId,new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of()),"token")).isInstanceOf(ForbiddenException.class);
    }
    @Test void assignedStaffCanReadButCannotWriteOrCrossFacility() {
        hours();var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(facilityId,Set.of("SCHEDULE_READ")));
        assertThat(service.hours(facilityId,staff,"token")).hasSize(2);
        assertThatThrownBy(()->service.hours(UUID.randomUUID(),staff,"token")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(()->service.replaceHours(facilityId,new HoursInput(null,List.of()),staff,"token")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(()->service.addPrice(facilityId,price(null,null,null,0,"100000"),staff,"token")).isInstanceOf(ForbiddenException.class);
    }
}
