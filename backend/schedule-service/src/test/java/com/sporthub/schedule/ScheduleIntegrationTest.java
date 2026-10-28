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
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class ScheduleIntegrationTest {
    static PostgreSQLContainer<?> postgres;
    @DynamicPropertySource static void db(DynamicPropertyRegistry p) {
        String url=System.getenv("SPORTHUB_SCHEDULE_TEST_DB_URL");
        if(url!=null) {p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv("SPORTHUB_TEST_DB_USER"));p.add("spring.datasource.password",()->System.getenv("SPORTHUB_TEST_DB_PASSWORD"));}
        else {postgres=new PostgreSQLContainer<>("postgres:16-alpine");postgres.start();p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}
    }
    @AfterAll static void close() {if(postgres!=null)postgres.stop();}
    @MockBean FacilityClient facility;
    @MockBean com.sporthub.common.schedule.ConfigurationGuardTransport guard;
    @MockBean Clock clock;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.sporthub.common.schedule.ConfigurationChanges changes;
    @Autowired ScheduleService service;
    @MockBean com.sporthub.common.security.RemoteIdentity identity;
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    UUID facilityId,courtId,categoryId;
    Caller owner=new Caller(UUID.randomUUID(),"Owner",Set.of("OWNER"),Map.of());
    LocalDate day=LocalDate.now().plusDays(7);
    FacilityClient.Context context;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(Instant.now());when(clock.withZone(any())).thenAnswer(a->Clock.fixed(clock.instant(),a.getArgument(0)));
        doAnswer(a->new com.sporthub.common.schedule.ConfigurationGuardProtocol.Prepared(a.<com.sporthub.common.schedule.ConfigurationGuardProtocol.Command>getArgument(0).operationId(),List.of())).when(guard).prepare(any());
        when(facility.configuration(any(),any())).thenReturn(new FacilityClient.Configuration(ZoneId.of("Asia/Ho_Chi_Minh"),"ACTIVE"));
        facilityId=UUID.randomUUID();courtId=UUID.randomUUID();categoryId=UUID.randomUUID();
        context=new FacilityClient.Context(facilityId,courtId,categoryId,ZoneId.of("Asia/Ho_Chi_Minh"),true,"ACTIVE",List.of());
        when(facility.context(eq(courtId),any())).thenReturn(context);
    }
    private void hours() {service.replaceHours(facilityId,new HoursInput(null,List.of(new Interval(day.getDayOfWeek().getValue(),LocalTime.of(6,0),LocalTime.of(11,0),60),new Interval(day.getDayOfWeek().getValue(),LocalTime.of(13,0),LocalTime.of(22,0),60))),owner,"token");}
    private PriceInput price(UUID court,UUID category,LocalDate date,int priority,String amount) {return new PriceInput(court,category,date==null?day.getDayOfWeek().getValue():null,date,LocalTime.of(6,0),LocalTime.of(11,0),new BigDecimal(amount),priority,"Rule",day.minusDays(1),day.plusDays(30),"VND");}
    private Instant at(int hour) {return day.atTime(hour,0).atZone(context.timezone()).toInstant();}
    @Test void ownerSignupConfigurationRetriesAreAtomicBoundAndPlayable(){
        UUID application=UUID.randomUUID();var setup=new com.sporthub.common.dto.OwnerSignupSetup("C1","Court",categoryId,Set.of(1,2,3,4,5,6,7),LocalTime.of(6,0),LocalTime.of(22,0),60,new BigDecimal("100000"));
        service.prepareSignup(facilityId,application,owner.id(),"Asia/Ho_Chi_Minh",setup);service.prepareSignup(facilityId,application,owner.id(),"Asia/Ho_Chi_Minh",setup);
        assertThat(service.hours(facilityId,owner,"token")).hasSize(7);assertThat(service.rules(facilityId,owner,"token")).hasSize(7);assertThat(service.freezeApplication(facilityId,application,List.of(context))).containsKeys("hours","prices");
        assertThatThrownBy(()->service.prepareSignup(facilityId,UUID.randomUUID(),owner.id(),"Asia/Ho_Chi_Minh",setup)).isInstanceOf(ForbiddenException.class);
        var invalid=new com.sporthub.common.dto.OwnerSignupSetup("C1","Court",categoryId,Set.of(1),LocalTime.of(6,0),LocalTime.of(7,10),60,new BigDecimal("100000"));UUID empty=UUID.randomUUID();assertThatThrownBy(()->service.prepareSignup(empty,application,owner.id(),"Asia/Ho_Chi_Minh",invalid)).isInstanceOf(IllegalArgumentException.class);assertThat(service.hours(empty,owner,"token")).isEmpty();
    }
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
        assertThat(quote.amount()).isEqualByComparingTo("200000");assertThat(quote.segments()).hasSize(1);
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
        assertThat(preview.slots()).allMatch(slot -> slot.state().equals("BLOCKED") && slot.reason().equals("CLOSED_EXCEPTION"));
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

 @Test void scheduleReadIsFacilityScopedAndStaffCannotWriteEvenWithEveryExistingPermission() {
  hours();var permissions=Set.of("BOOKING_READ","BOOKING_CREATE_COUNTER","BOOKING_CHECK_IN","BOOKING_COMPLETE","SCHEDULE_READ");
  var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(facilityId,permissions));
  assertThat(service.hours(facilityId,staff,"token")).hasSize(2);
  assertThatThrownBy(()->service.hours(UUID.randomUUID(),staff,"token")).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.hours(facilityId,new Caller(staff.id(),"Revoked",Set.of("STAFF"),Map.of()),"token")).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.replaceHours(facilityId,new HoursInput(null,List.of()),staff,"token")).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.addPrice(facilityId,price(null,null,null,0,"100000"),staff,"token")).isInstanceOf(ForbiddenException.class);
  doThrow(new ForbiddenException("Foreign facility")).when(facility).requireReader(eq(facilityId),eq("foreign-owner"));
  assertThatThrownBy(()->service.hours(facilityId,owner,"foreign-owner")).isInstanceOf(ForbiddenException.class);
 }


    private com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation reservation(int from,int to,String state) {
        return new com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation(UUID.randomUUID(),UUID.randomUUID(),courtId,at(from),at(to),state);
    }
    private void affected(com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation reservation) {
        doAnswer(a->new com.sporthub.common.schedule.ConfigurationGuardProtocol.Prepared(a.<com.sporthub.common.schedule.ConfigurationGuardProtocol.Command>getArgument(0).operationId(),List.of(reservation))).when(guard).prepare(any());
    }
    @Test void courtWeeklyOverrideAndClearingItRestoreFacilityInheritance() {
        hours();service.addPrice(facilityId,price(null,null,null,0,"100000"),owner,"token");
        service.replaceHours(facilityId,new HoursInput(courtId,List.of(new Interval(day.getDayOfWeek().getValue(),LocalTime.of(8,0),LocalTime.of(10,0),60))),owner,"token");
        assertThat(service.publicPreview(courtId,day).slots()).hasSize(4);
        assertThatThrownBy(()->service.quote(new QuoteInput(courtId,at(6),at(7)))).isInstanceOf(ConflictException.class);
        service.replaceHours(facilityId,new HoursInput(courtId,List.of()),owner,"token");
        assertThat(service.quote(new QuoteInput(courtId,at(6),at(7))).amount()).isEqualByComparingTo("100000");
    }
    @Test void courtSpecialOpeningOverridesGeneralHolidayButNotMaintenance() {
        hours();service.addPrice(facilityId,price(null,null,null,0,"100000"),owner,"token");
        service.addException(facilityId,new ExceptionInput(null,day,"CLOSED",null,null,"Holiday"),owner,"token");
        service.addException(facilityId,new ExceptionInput(courtId,day,"SPECIAL_HOURS",LocalTime.of(6,0),LocalTime.of(8,0),"Special event"),owner,"token");
        assertThat(service.quote(new QuoteInput(courtId,at(6),at(7))).amount()).isEqualByComparingTo("100000");
        when(facility.context(eq(courtId),any())).thenReturn(new FacilityClient.Context(facilityId,courtId,categoryId,context.timezone(),true,"ACTIVE",List.of(new FacilityClient.Window(at(6),at(7)))));
        assertThat(service.publicPreview(courtId,day).slots().getFirst().reason()).isEqualTo("MAINTENANCE");
    }
    @Test void exceptionDatesUseFacilityTimezoneAndSuspensionPreventsSpecialOpening() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-13T20:00:00Z"));
        assertThatThrownBy(()->service.addException(facilityId,new ExceptionInput(null,LocalDate.of(2026,10,13),"CLOSED",null,null,"Past local date"),owner,"token")).isInstanceOf(IllegalArgumentException.class);
        when(facility.configuration(any(),any())).thenReturn(new FacilityClient.Configuration(ZoneId.of("America/Los_Angeles"),"ACTIVE"));
        var today=service.addException(facilityId,new ExceptionInput(null,LocalDate.of(2026,10,13),"CLOSED",null,null,"Current local date"),owner,"token");
        assertThatThrownBy(()->service.removeException(facilityId,today.id(),owner,"token")).isInstanceOf(ConflictException.class);
        when(facility.configuration(any(),any())).thenReturn(new FacilityClient.Configuration(ZoneId.of("Asia/Ho_Chi_Minh"),"SUSPENDED"));
        assertThatThrownBy(()->service.addException(facilityId,new ExceptionInput(null,LocalDate.of(2026,10,15),"SPECIAL_HOURS",LocalTime.of(6,0),LocalTime.of(8,0),"Open"),owner,"token")).isInstanceOf(ConflictException.class);
    }
    @Test void samePriorityExceptionsAndNonMinuteTimesAreRejectedWithoutWrites() {
        hours();var first=service.addException(facilityId,new ExceptionInput(null,day,"SPECIAL_HOURS",LocalTime.of(6,0),LocalTime.of(8,0),"Open"),owner,"token");
        assertThatThrownBy(()->service.addException(facilityId,new ExceptionInput(null,day,"SPECIAL_HOURS",LocalTime.of(7,0),LocalTime.of(9,0),"Overlap"),owner,"token")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service.replaceHours(facilityId,new HoursInput(null,List.of(new Interval(1,LocalTime.of(6,0,30),LocalTime.of(7,0,30),60))),owner,"token")).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.exceptions(facilityId,owner,"token")).extracting(ExceptionView::id).containsExactly(first.id());
    }
    @Test void affectedConfirmedBookingRejectsHoursAtomicallyAndReleasesBarrier() {
        hours();var before=service.hours(facilityId,owner,"token");affected(reservation(6,7,"BOOKED"));clearInvocations(guard);
        assertThatThrownBy(()->service.replaceHours(facilityId,new HoursInput(null,List.of()),owner,"token")).isInstanceOf(ConfigurationImpactException.class);
        assertThat(service.hours(facilityId,owner,"token")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_audit WHERE facility_id=? AND action='HOURS_REPLACED'",Integer.class,facilityId)).isEqualTo(1);
        verify(guard).release(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM configuration_guard_releases WHERE facility_id=?",Integer.class,facilityId)).isZero();
    }
    @Test void existingHoldAndServingBookingRejectClosureAndRemovingSpecialHours() {
        hours();affected(reservation(6,7,"HOLD"));
        assertThatThrownBy(()->service.addException(facilityId,new ExceptionInput(courtId,day,"CLOSED",null,null,"Close"),owner,"token")).isInstanceOf(ConfigurationImpactException.class);
        assertThat(service.exceptions(facilityId,owner,"token")).isEmpty();
        doAnswer(a->new com.sporthub.common.schedule.ConfigurationGuardProtocol.Prepared(a.<com.sporthub.common.schedule.ConfigurationGuardProtocol.Command>getArgument(0).operationId(),List.of())).when(guard).prepare(any());
        var special=service.addException(facilityId,new ExceptionInput(courtId,day,"SPECIAL_HOURS",LocalTime.of(11,0),LocalTime.of(12,0),"Extra opening"),owner,"token");
        affected(reservation(11,12,"BOOKED"));
        assertThatThrownBy(()->service.removeException(facilityId,special.id(),owner,"token")).isInstanceOf(ConfigurationImpactException.class);
        assertThat(service.exceptions(facilityId,owner,"token")).extracting(ExceptionView::id).containsExactly(special.id());
    }
    @Test void harmlessHoursChangeDoesNotRepriceAnExistingReservation() {
        hours();affected(reservation(6,7,"BOOKED"));
        // No price rules at all: temporal impact detection must not reprice existing bookings.
        service.replaceHours(facilityId,new HoursInput(null,List.of(new Interval(day.getDayOfWeek().getValue(),LocalTime.of(6,0),LocalTime.of(12,0),60))),owner,"token");
        assertThat(service.hours(facilityId,owner,"token")).hasSize(1);
    }
    @Test void dependencyFailureRollsBackAndCommittedReleaseFailureIsRetried() {
        hours();var before=service.hours(facilityId,owner,"token");
        doThrow(new IllegalStateException("Unavailable")).when(guard).prepare(any());
        assertThatThrownBy(()->service.replaceHours(facilityId,new HoursInput(null,List.of()),owner,"token")).isInstanceOf(IllegalStateException.class);
        assertThat(service.hours(facilityId,owner,"token")).isEqualTo(before);
        doAnswer(a->new com.sporthub.common.schedule.ConfigurationGuardProtocol.Prepared(a.<com.sporthub.common.schedule.ConfigurationGuardProtocol.Command>getArgument(0).operationId(),List.of())).when(guard).prepare(any());
        doThrow(new IllegalStateException("Unavailable")).when(guard).release(any());
        service.replaceHours(facilityId,new HoursInput(null,List.of()),owner,"token");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM configuration_guard_releases WHERE facility_id=?",Integer.class,facilityId)).isEqualTo(1);
        doNothing().when(guard).release(any());changes.retryCommittedReleases();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM configuration_guard_releases WHERE facility_id=?",Integer.class,facilityId)).isZero();
    }
    @Test void previewIsReadOnlyAndNeverClaimsBookingAvailability() {
        hours();service.addPrice(facilityId,price(null,null,null,0,"100000"),owner,"token");clearInvocations(guard);
        var audits=jdbc.queryForObject("SELECT count(*) FROM schedule_audit",Integer.class);
        var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(facilityId,Set.of("SCHEDULE_READ")));
        var preview=service.ownerPreview(facilityId,courtId,day,staff,"token");
        assertThat(preview.slots()).extracting(Slot::state).containsOnly("ELIGIBLE","BLOCKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_audit",Integer.class)).isEqualTo(audits);
        verifyNoInteractions(guard);
        assertThatThrownBy(()->service.ownerPreview(UUID.randomUUID(),courtId,day,staff,"token")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(()->service.addException(facilityId,new ExceptionInput(null,day,"CLOSED",null,null,"Close"),staff,"token")).isInstanceOf(ForbiddenException.class);
    }
    @Test void daylightSavingTransitionsNeverGenerateEligibleAmbiguousOrZeroLengthSlots() {
        var zone=ZoneId.of("America/New_York");day=LocalDate.of(2027,3,14);
        context=new FacilityClient.Context(facilityId,courtId,categoryId,zone,true,"ACTIVE",List.of());when(facility.context(eq(courtId),any())).thenReturn(context);
        service.replaceHours(facilityId,new HoursInput(null,List.of(new Interval(7,LocalTime.of(1,0),LocalTime.of(5,0),60))),owner,"token");
        service.addPrice(facilityId,new PriceInput(null,null,7,null,LocalTime.of(1,0),LocalTime.of(5,0),new BigDecimal("100000"),0,"DST",LocalDate.of(2027,1,1),null,"VND"),owner,"token");
        var spring=service.publicPreview(courtId,day);assertThat(spring.slots()).allMatch(slot->slot.startsAt().isBefore(slot.endsAt()));
        assertThat(spring.slots()).anyMatch(slot->"TIMEZONE_TRANSITION".equals(slot.reason()));
        day=LocalDate.of(2027,11,7);assertThat(service.publicPreview(courtId,day).slots()).anyMatch(slot->"TIMEZONE_TRANSITION".equals(slot.reason()));
    }

 @Test void abortedConfigurationReleaseIsDurableAndCanRecoverAfterDependencyOutage(){
  hours();var before=service.hours(facilityId,owner,"token");
  doThrow(new IllegalStateException("Prepare timeout")).when(guard).prepare(any());doThrow(new IllegalStateException("Release outage")).when(guard).release(any());
  assertThatThrownBy(()->service.replaceHours(facilityId,new HoursInput(null,List.of()),owner,"token")).isInstanceOf(IllegalStateException.class);
  assertThat(service.hours(facilityId,owner,"token")).isEqualTo(before);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM configuration_guard_releases WHERE facility_id=?",Integer.class,facilityId)).isEqualTo(1);
  doNothing().when(guard).release(any());changes.retryCommittedReleases();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM configuration_guard_releases WHERE facility_id=?",Integer.class,facilityId)).isZero();
 }
 @Test void concurrentSameScopeExceptionsHaveExactlyOneWinner()throws Exception{
  var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try{
   var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int n=0;n<2;n++)futures.add(pool.submit(()->{ready.countDown();go.await();try{service.addException(facilityId,new ExceptionInput(null,day,"CLOSED",null,null,"Holiday"),owner,"token");return true;}catch(ConflictException conflict){return false;}}));
   assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();go.countDown();int winners=0;for(var future:futures)if(future.get(10,java.util.concurrent.TimeUnit.SECONDS))winners++;
   assertThat(winners).isEqualTo(1);assertThat(service.exceptions(facilityId,owner,"token")).hasSize(1);
  }finally{pool.shutdownNow();}
 }

 @Test void futureExceptionEditPreservesIdentityAndAuditAndRejectsBookingImpact(){
  hours();var exception=service.addException(facilityId,new ExceptionInput(courtId,day,"SPECIAL_HOURS",LocalTime.of(6,0),LocalTime.of(8,0),"Original"),owner,"token");
  var updated=service.updateException(facilityId,exception.id(),new ExceptionInput(courtId,day,"SPECIAL_HOURS",LocalTime.of(6,0),LocalTime.of(9,0),"Extended"),owner,"token");
  assertThat(updated.id()).isEqualTo(exception.id());assertThat(updated.closesAt()).isEqualTo(LocalTime.of(9,0));
  assertThat(jdbc.queryForObject("SELECT snapshot->'before'->>'reason' FROM schedule_audit WHERE facility_id=? AND action='EXCEPTION_UPDATED'",String.class,facilityId)).isEqualTo("Original");
  affected(reservation(6,7,"BOOKED"));
  assertThatThrownBy(()->service.updateException(facilityId,exception.id(),new ExceptionInput(courtId,day,"SPECIAL_HOURS",LocalTime.of(7,0),LocalTime.of(9,0),"Cuts booking"),owner,"token")).isInstanceOf(ConfigurationImpactException.class);
  assertThat(service.exceptions(facilityId,owner,"token")).containsExactly(updated);
  var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(facilityId,Set.of("SCHEDULE_READ")));
  assertThatThrownBy(()->service.updateException(facilityId,exception.id(),new ExceptionInput(null,day,"CLOSED",null,null,"Close"),staff,"token")).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.updateException(UUID.randomUUID(),exception.id(),new ExceptionInput(null,day,"CLOSED",null,null,"Foreign"),owner,"token")).isInstanceOf(ResourceNotFoundException.class);
 }

 @Test void originalSlotSizeDoesNotChangeTheThirtyMinuteSelectionGrid(){
  service.replaceHours(facilityId,new HoursInput(null,List.of(new Interval(day.getDayOfWeek().getValue(),LocalTime.of(6,0),LocalTime.of(12,0),30))),owner,"token");
  var booked=new com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation(UUID.randomUUID(),UUID.randomUUID(),courtId,at(6).plusSeconds(1800),at(7).plusSeconds(1800),"BOOKED");
  affected(booked);var changed=new HoursInput(null,List.of(new Interval(day.getDayOfWeek().getValue(),LocalTime.of(6,0),LocalTime.of(12,0),60)));
  service.replaceHours(facilityId,changed,owner,"token");
  affected(new com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation(booked.reservationId(),booked.bookingId(),courtId,booked.startsAt(),booked.endsAt(),"HOLD"));
  service.replaceHours(facilityId,changed,owner,"token");
 }

 private PriceInput rate(UUID court,UUID type,Integer weekday,LocalDate specific,LocalTime start,LocalTime end,String amount,int priority,LocalDate from,LocalDate to){return new PriceInput(court,null,weekday,specific,start,end,new BigDecimal(amount),priority,"Pricing test",from,to,"VND",type);}
 private void fullWeek(){service.replaceHours(facilityId,new HoursInput(null,java.util.stream.IntStream.rangeClosed(1,7).mapToObj(d->new Interval(d,LocalTime.of(6,0),LocalTime.of(12,0),60)).toList()),owner,"token");}
 @Test void weekdaysWeekendAndPeakOffPeakUseExactPerSlotPrices(){
  fullWeek();for(int d=1;d<=7;d++){service.addPrice(facilityId,rate(null,null,d,null,LocalTime.of(6,0),LocalTime.of(9,0),d>=6?"200000.20":"100000.10",0,day.minusDays(10),null),owner,"token");service.addPrice(facilityId,rate(null,null,d,null,LocalTime.of(9,0),LocalTime.of(12,0),d>=6?"300000.30":"150000.15",0,day.minusDays(10),null),owner,"token");}
  day=day.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));assertThat(service.quote(new QuoteInput(courtId,at(8),at(10))).amount()).isEqualByComparingTo("250000");
  day=day.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));assertThat(service.quote(new QuoteInput(courtId,at(8),at(10))).amount()).isEqualByComparingTo("500001");
  assertThat(service.quote(new QuoteInput(courtId,at(9),at(10))).segments()).allMatch(slot->slot.startsAt().equals(at(9))&&slot.amount().compareTo(new BigDecimal("300000"))==0);
 }
 @Test void specialDateOverridesSamePriorityWeekdayAndExpiryFallsBackAtInclusiveBoundaries(){
  fullWeek();int weekday=day.getDayOfWeek().getValue();var normal=service.addPrice(facilityId,rate(null,null,weekday,null,LocalTime.of(6,0),LocalTime.of(12,0),"100000",0,day.minusDays(30),null),owner,"token");
  var special=service.addPrice(facilityId,rate(null,null,null,day,LocalTime.of(6,0),LocalTime.of(12,0),"250000",0,day,day),owner,"token");
  assertThat(service.quote(new QuoteInput(courtId,at(6),at(7))).segments().getFirst().ruleId()).isEqualTo(special.id());
  day=day.plusWeeks(1);assertThat(service.quote(new QuoteInput(courtId,at(6),at(7))).segments().getFirst().ruleId()).isEqualTo(normal.id());
  service.addPrice(facilityId,rate(null,null,weekday,null,LocalTime.of(6,0),LocalTime.of(12,0),"180000",10,day,day.plusWeeks(1)),owner,"token");
  assertThat(service.quote(new QuoteInput(courtId,at(6),at(7))).amount()).isEqualByComparingTo("180000");day=day.plusWeeks(1);assertThat(service.quote(new QuoteInput(courtId,at(6),at(7))).amount()).isEqualByComparingTo("180000");day=day.plusWeeks(1);assertThat(service.quote(new QuoteInput(courtId,at(6),at(7))).amount()).isEqualByComparingTo("100000");
 }
 @Test void courtTypeIsIndependentFromSportCategoryAndSpecificCourtOutranksGenericType(){
  hours();UUID type=UUID.randomUUID();context=new FacilityClient.Context(facilityId,courtId,categoryId,context.timezone(),true,"ACTIVE",List.of(),type);when(facility.context(eq(courtId),any())).thenReturn(context);
  service.addPrice(facilityId,price(null,null,null,9999,"100000"),owner,"token");var typed=service.addPrice(facilityId,rate(null,type,day.getDayOfWeek().getValue(),null,LocalTime.of(6,0),LocalTime.of(11,0),"120000",9999,day.minusDays(1),null),owner,"token");
  assertThat(service.quote(new QuoteInput(courtId,at(6),at(7))).segments().getFirst().ruleId()).isEqualTo(typed.id());
  var specific=service.addPrice(facilityId,price(courtId,null,null,0,"180000"),owner,"token");assertThat(service.quote(new QuoteInput(courtId,at(6),at(7))).segments().getFirst().ruleId()).isEqualTo(specific.id());
  assertThatThrownBy(()->service.addPrice(facilityId,rate(courtId,UUID.randomUUID(),day.getDayOfWeek().getValue(),null,LocalTime.of(6,0),LocalTime.of(11,0),"150000",0,day.minusDays(1),null),owner,"token")).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void crossScopeEqualRankAmbiguityIsRejectedButDifferentPriorityIsDeterministic(){
  hours();service.addPrice(facilityId,price(courtId,null,null,0,"100000"),owner,"token");
  assertThatThrownBy(()->service.addPrice(facilityId,price(null,null,day,0,"200000"),owner,"token")).isInstanceOf(ConflictException.class);
  var special=service.addPrice(facilityId,price(null,null,day,1,"200000"),owner,"token");assertThat(service.quote(new QuoteInput(courtId,at(6),at(7))).segments().getFirst().ruleId()).isEqualTo(special.id());
 }
 @Test void effectivePeriodOverlapRequiresAnActualMatchingWeekday(){
  fullWeek();var monday=day.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
  service.addPrice(facilityId,rate(null,null,1,null,LocalTime.of(6,0),LocalTime.of(12,0),"100000",0,monday,monday.plusDays(2)),owner,"token");
  service.addPrice(facilityId,rate(null,null,1,null,LocalTime.of(6,0),LocalTime.of(12,0),"120000",0,monday.plusDays(1),monday.plusWeeks(1)),owner,"token");
  assertThatThrownBy(()->service.addPrice(facilityId,rate(null,null,1,null,LocalTime.of(6,0),LocalTime.of(12,0),"120000",1,monday.plusDays(1),monday.plusDays(2)),owner,"token")).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void priceRangeCannotUseFacilityHoursWhenCourtWeeklyOverrideClosesThatRange(){
  hours();service.replaceHours(facilityId,new HoursInput(courtId,List.of(new Interval(day.getDayOfWeek().getValue(),LocalTime.of(8,0),LocalTime.of(10,0),60))),owner,"token");
  assertThatThrownBy(()->service.addPrice(facilityId,price(courtId,null,null,0,"100000"),owner,"token")).isInstanceOf(IllegalArgumentException.class);
  service.addPrice(facilityId,rate(courtId,null,day.getDayOfWeek().getValue(),null,LocalTime.of(8,0),LocalTime.of(10,0),"100000",0,day.minusDays(1),null),owner,"token");
  assertThatThrownBy(()->service.addPrice(facilityId,rate(courtId,null,day.getDayOfWeek().getValue(),null,LocalTime.of(8,30),LocalTime.of(9,30),"120000",1,day.minusDays(1),null),owner,"token")).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void specificDatePriceCanFollowSpecialOpeningOutsideRegularHours(){
  hours();service.addException(facilityId,new ExceptionInput(courtId,day,"SPECIAL_HOURS",LocalTime.of(11,0),LocalTime.of(12,0),"Special opening"),owner,"token");
  service.addPrice(facilityId,rate(courtId,null,null,day,LocalTime.of(11,0),LocalTime.of(12,0),"250000",0,day,day),owner,"token");assertThat(service.quote(new QuoteInput(courtId,at(11),at(12))).amount()).isEqualByComparingTo("250000");
 }
 @Test void priceValidationRejectsInvalidAmountsDatesScopeAndInactiveTypes(){
  hours();for(var amount:List.of("0","-1","100000.001","10000000000"))assertThatThrownBy(()->service.addPrice(facilityId,price(null,null,null,0,amount),owner,"token")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->service.addPrice(facilityId,rate(null,null,null,day,LocalTime.of(6,0),LocalTime.of(11,0),"100000",0,day.plusDays(1),day),owner,"token")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->service.addPrice(facilityId,rate(null,null,null,day,LocalTime.of(8,0),LocalTime.of(7,0),"100000",0,day,day),owner,"token")).isInstanceOf(IllegalArgumentException.class);
  doThrow(new IllegalArgumentException("Inactive type")).when(facility).requireType(any(),any(),any());
  assertThatThrownBy(()->service.addPrice(facilityId,rate(null,UUID.randomUUID(),day.getDayOfWeek().getValue(),null,LocalTime.of(6,0),LocalTime.of(11,0),"100000",0,day,null),owner,"token")).isInstanceOf(IllegalArgumentException.class);
  doThrow(new IllegalArgumentException("Inactive sport")).when(facility).requireCategory(any());assertThatThrownBy(()->service.addPrice(facilityId,price(null,categoryId,null,0,"100000"),owner,"token")).isInstanceOf(IllegalArgumentException.class);
  assertThat(service.rules(facilityId,owner,"token")).isEmpty();
 }
 @Test void priceRevisionsPreservePriorValuesAndOptimisticVersionAndDisableAreIdempotent(){
  hours();var initial=service.addPrice(facilityId,price(null,null,null,0,"100000"),owner,"token");var updated=service.updatePrice(facilityId,initial.id(),price(null,null,null,0,"150000"),0,owner,"token");assertThat(updated.version()).isEqualTo(1);
  assertThatThrownBy(()->service.updatePrice(facilityId,initial.id(),price(null,null,null,0,"190000"),0,owner,"token")).isInstanceOf(ConflictException.class);
  var history=service.priceHistory(facilityId,initial.id(),owner,"token");assertThat(history).extracting(PriceRevision::version).containsExactly(0L,1L);assertThat(history.getFirst().snapshot().path("pricePerSlot").decimalValue()).isEqualByComparingTo("100000");
  assertThatThrownBy(()->jdbc.update("UPDATE pricing_rule_versions SET snapshot='{}' WHERE rule_id=?",initial.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
  service.disablePrice(facilityId,initial.id(),owner,"token");service.disablePrice(facilityId,initial.id(),owner,"token");assertThat(service.priceHistory(facilityId,initial.id(),owner,"token")).extracting(PriceRevision::version).containsExactly(0L,1L,2L);
 }
 @Test void draftUsesSameEngineAndReportsAmbiguityWithoutWritingAnyConfiguration(){
  hours();var current=service.addPrice(facilityId,price(null,null,null,0,"100000"),owner,"token");clearInvocations(guard);
  int versions=jdbc.queryForObject("SELECT count(*) FROM pricing_rule_versions",Integer.class),audits=jdbc.queryForObject("SELECT count(*) FROM schedule_audit",Integer.class);
  var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(facilityId,Set.of("SCHEDULE_READ")));
  var draft=service.pricingDraft(facilityId,new PricingDraft(courtId,day,price(null,null,null,0,"200000"),null),staff,"token");assertThat(draft.slots().getFirst().reason()).isEqualTo("AMBIGUOUS_PRICE");assertThat(draft.slots().getFirst().amount()).isNull();assertThat(draft.slots().getFirst().conflictingRuleIds()).contains(current.id());
  var replacement=service.pricingDraft(facilityId,new PricingDraft(courtId,day,price(null,null,null,0,"200000"),current.id()),staff,"token");assertThat(replacement.slots().getFirst().amount()).isEqualByComparingTo("200000");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing_rule_versions",Integer.class)).isEqualTo(versions);assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_audit",Integer.class)).isEqualTo(audits);verifyNoInteractions(guard);
  assertThat(service.quote(new QuoteInput(courtId,at(6),at(7))).amount()).isEqualByComparingTo("100000");
 }
 @Test void pricingWritesShareOneBarrierWithinTheSameLocalTransaction(){
  clearInvocations(guard);new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx->{hours();for(var interval:List.of(new int[]{6,11},new int[]{13,22}))service.addPrice(facilityId,rate(null,null,day.getDayOfWeek().getValue(),null,LocalTime.of(interval[0],0),LocalTime.of(interval[1],0),"100000",0,day.minusDays(1),null),owner,"token");});
  verify(guard,times(1)).prepare(any());verify(guard,times(1)).release(any());assertThat(service.rules(facilityId,owner,"token")).hasSize(2);
 }
 @Test void priceUpdateApiRequiresVersionAndKeepsStaffReadOnly()throws Exception{
  hours();var initial=service.addPrice(facilityId,price(null,null,null,0,"100000"),owner,"token");when(identity.current(any())).thenReturn(owner);
  String path="/api/v1/schedules/facilities/"+facilityId+"/pricing/"+initial.id();String body=json.writeValueAsString(price(null,null,null,0,"200000"));
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).header("If-Match","\"0\"").contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).header("If-Match","0").contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
  var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(facilityId,Set.of("SCHEDULE_READ")));when(identity.current(any())).thenReturn(staff);
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).header("If-Match","1").contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
  assertThat(service.priceHistory(facilityId,initial.id(),staff,"token")).hasSize(2);assertThatThrownBy(()->service.priceHistory(UUID.randomUUID(),initial.id(),staff,"token")).isInstanceOf(ForbiddenException.class);
 }
 @Test void quoteUsesFacilityLocalSpecialDateAndRejectsTotalStorageOverflow(){
  var zone=ZoneId.of("Pacific/Kiritimati");context=new FacilityClient.Context(facilityId,courtId,categoryId,zone,true,"ACTIVE",List.of());when(facility.context(eq(courtId),any())).thenReturn(context);fullWeek();
  service.addPrice(facilityId,rate(null,null,day.getDayOfWeek().getValue(),null,LocalTime.of(6,0),LocalTime.of(12,0),"10.10",0,day.minusDays(1),null),owner,"token");service.addPrice(facilityId,rate(null,null,null,day,LocalTime.of(6,0),LocalTime.of(12,0),"20.20",0,day,day),owner,"token");
  assertThat(at(6).atZone(ZoneOffset.UTC).toLocalDate()).isEqualTo(day.minusDays(1));assertThat(service.quote(new QuoteInput(courtId,at(6),at(8))).amount()).isEqualByComparingTo("40");
  service.addPrice(facilityId,rate(courtId,null,day.getDayOfWeek().getValue(),null,LocalTime.of(6,0),LocalTime.of(12,0),"9999999999.99",1,day.minusDays(1),null),owner,"token");assertThatThrownBy(()->service.quote(new QuoteInput(courtId,at(6),at(8)))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void legacyTypeTextIsNeverSilentlyAppliedAsFacilityWidePrice(){
  hours();var rule=service.addPrice(facilityId,price(null,null,null,0,"100000"),owner,"token");jdbc.update("UPDATE pricing_rules SET court_type='Legacy indoor' WHERE id=?",rule.id());
  assertThat(service.publicPreview(courtId,day).slots().getFirst().reason()).isEqualTo("LEGACY_COURT_TYPE_UNRESOLVED");assertThatThrownBy(()->service.quote(new QuoteInput(courtId,at(6),at(7)))).isInstanceOf(ConflictException.class);
  assertThatThrownBy(()->service.updatePrice(facilityId,rule.id(),price(null,null,null,0,"120000"),0,owner,"token")).isInstanceOf(IllegalArgumentException.class);
 }

 @Test void legacyUnsupportedCurrencyCannotBeQuotedAsVnd(){
  hours();var rule=service.addPrice(facilityId,price(null,null,null,0,"100000"),owner,"token");jdbc.update("UPDATE pricing_rules SET currency='USD' WHERE id=?",rule.id());
  assertThat(service.publicPreview(courtId,day).slots().getFirst().reason()).isEqualTo("INVALID_PRICE_RULE");assertThatThrownBy(()->service.quote(new QuoteInput(courtId,at(6),at(7)))).isInstanceOf(ConflictException.class);
 }

 @Test void typeScopedPriceCannotBypassEveryMatchingCourtOverride(){
  hours();UUID type=UUID.randomUUID();context=new FacilityClient.Context(facilityId,courtId,categoryId,context.timezone(),true,"ACTIVE",List.of(),type);when(facility.context(eq(courtId),any())).thenReturn(context);when(facility.courts(eq(facilityId),any())).thenReturn(List.of(context));
  service.replaceHours(facilityId,new HoursInput(courtId,List.of(new Interval(day.getDayOfWeek().getValue(),LocalTime.of(8,0),LocalTime.of(10,0),60))),owner,"token");
  assertThatThrownBy(()->service.addPrice(facilityId,rate(null,type,day.getDayOfWeek().getValue(),null,LocalTime.of(6,0),LocalTime.of(11,0),"100000",0,day.minusDays(1),null),owner,"token")).isInstanceOf(IllegalArgumentException.class);
  service.addPrice(facilityId,rate(null,type,day.getDayOfWeek().getValue(),null,LocalTime.of(8,0),LocalTime.of(10,0),"100000",0,day.minusDays(1),null),owner,"token");assertThat(service.quote(new QuoteInput(courtId,at(8),at(9))).amount()).isEqualByComparingTo("100000");
 }
    @Test void staffScheduleManagerCanWriteHoursAndExceptionsButNeverPrices(){
        hours();var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(facilityId,Set.of("SCHEDULE_MANAGE")));var input=new HoursInput(courtId,List.of(new Interval(day.getDayOfWeek().getValue(),LocalTime.of(6,0),LocalTime.of(11,0),60)));
        clearInvocations(facility);assertThat(service.replaceHours(facilityId,input,staff,"Bearer staff")).isNotEmpty();var exception=service.addException(facilityId,new ExceptionInput(courtId,day,"CLOSED",null,null,"Staff exception"),staff,"Bearer staff");service.removeException(facilityId,exception.id(),staff,"Bearer staff");assertThat(service.exceptions(facilityId,staff,"Bearer staff")).isEmpty();verify(facility,atLeastOnce()).requireScheduleManager(facilityId,"Bearer staff");verify(facility,never()).requireOwner(facilityId,"Bearer staff");
        assertThatThrownBy(()->service.addPrice(facilityId,price(null,null,null,0,"100000"),staff,"Bearer staff")).isInstanceOf(ForbiddenException.class);
    }
    @Test void staffScheduleWriteRejectsReadOnlyForeignAndRevokedPermissions(){
        var input=new HoursInput(null,List.of(new Interval(1,LocalTime.of(6,0),LocalTime.of(8,0),60)));for(var bindings:List.of(Map.of(facilityId,Set.of("SCHEDULE_READ")),Map.of(UUID.randomUUID(),Set.of("SCHEDULE_MANAGE")),Map.<UUID,Set<String>>of())){var actor=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),bindings);assertThatThrownBy(()->service.replaceHours(facilityId,input,actor,"Bearer staff")).isInstanceOf(ForbiddenException.class);}
        var actor=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(facilityId,Set.of("SCHEDULE_MANAGE")));doThrow(new ForbiddenException("Staff permission was revoked")).when(facility).requireScheduleManager(facilityId,"Bearer staff");assertThatThrownBy(()->service.replaceHours(facilityId,input,actor,"Bearer staff")).isInstanceOf(ForbiddenException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM operating_hours WHERE facility_id=?",Integer.class,facilityId)).isZero();
    }

}
