package com.sporthub.booking;

import static com.sporthub.booking.web.BookingDtos.*;
import static com.sporthub.booking.web.GroupDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.booking.service.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs the same business fixtures against the preserved before source and the refactored source. */
@EnabledIfSystemProperty(named="sporthub.report.compatibility",matches="true")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ReportCompatibilityTest extends BookingIntegrationTest {
  private final Map<String,Object> cases=new LinkedHashMap<>();
  private final Map<String,Object> queryScopes=new LinkedHashMap<>();
  @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext context;
  private final LocalDate first=LocalDate.of(2026,10,5);

  @Test void capture() throws Exception {
    assertThat(System.getenv("SPORTHUB_BOOKING_TEST_DB_URL")).isNull();
    when(paymentEvidence.read(any())).thenAnswer(call->{
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return new PaymentEvidenceClient.Result(Map.of(),Map.of(),List.of(),List.of(),0);
    });
    var owner=demoOwner();
    var online=demoConfirmed();
    var deal=demoDeal(online,user,customer(),owner,500,true);
    demoReportAccess(owner);
    var cancelled=cancelForCapture(repo.find(online.id()),deal.buyer(),"Compatibility transfer cancellation");
    UUID refund=(UUID)((Map<?,?>)cancelled.get("refund")).get("id");
    when(clock.instant()).thenReturn(now.plusSeconds(1));
    demoFinance.paySeller(deal.command().acquisitionId(),new BookingDemoFinance.Outcome("FAILED"),key(),owner,"token");
    when(clock.instant()).thenReturn(now.plusSeconds(2));
    demoFinance.refund(refund,new BookingDemoFinance.Outcome("FAILED"),key(),owner,"token");
    record("transfer-failures",first,first,owner);
    when(clock.instant()).thenReturn(now.plusSeconds(3*86400));
    record("carryover-before-later-execution",first.plusDays(1),first.plusDays(2),owner);
    demoFinance.paySeller(deal.command().acquisitionId(),new BookingDemoFinance.Outcome("SUCCESS"),key(),owner,"token");
    demoFinance.refund(refund,new BookingDemoFinance.Outcome("SUCCESS"),key(),owner,"token");
    record("carryover-after-later-execution",first.plusDays(1),first.plusDays(2),owner);
    record("execution-day",first.plusDays(3),first.plusDays(3),owner);

    when(clock.instant()).thenReturn(now);
    nextCourt();demoAcl(owner);scopedPermissions();
    var staff=counterStaff("BOOKING_CREATE_COUNTER","BOOKING_REFUND");
    var guest=counterBooking(staff);
    var paid=(ObjectNode)counterSuccess(guest,staff.id(),UUID.randomUUID());
    ((ObjectNode)paid.path("payload")).put("provider","DEMO").put("canonical",true);
    consumer.apply(paid);
    var guestCancellation=cancelForCapture(repo.find(guest.id()),staff,"Compatibility guest cancellation");
    UUID guestRefund=(UUID)((Map<?,?>)guestCancellation.get("refund")).get("id");
    demoFinance.refund(guestRefund,new BookingDemoFinance.Outcome("SUCCESS"),key(),staff,"token");
    assertThat(repo.find(guest.id()).currentHolderId()).isNull();

    nextCourt();start=now.plusSeconds(86400);end=start.plusSeconds(3600);
    ((ObjectNode)quote).put("startsAt",start.toString()).put("endsAt",end.toString()).put("amount",300000);
    var hold=service.hold(new HoldInput(court,start,end,new BigDecimal("300000")),key(),user,"token",false);
    var group=groups.create(new CreateGroup(hold.id(),"Compatibility paid group",10),key(),user,"token");
    var invite=groups.invite(group.id(),user).code();groups.join(new JoinGroup(invite),customer());
    group=groups.split(group.id(),new Split("EQUAL",null),user);
    for(var member:group.members()){
      groups.payable(group.id(),member.id(),user);
      var event=(ObjectNode)contribution(group,member,user,UUID.randomUUID());
      ((ObjectNode)event.path("payload")).put("provider","DEMO").put("canonical",true);
      consumer.apply(event);
    }
    demoReportAccess(owner);when(clock.instant()).thenReturn(now.plusSeconds(60));
    record("guest-and-group",first,first.plusDays(1),owner);
    record("payment-before-play-period",first.plusDays(1),first.plusDays(1),owner);

    nextCourt();when(dependencies.refundPolicy(any())).thenAnswer(a->policy(a.getArgument(0),0,1));
    var zero=demoConfirmed();cancelForCapture(zero,user,"Compatibility zero refund");
    nextCourt();legacyConfirmedProof(false,false,false);
    nextCourt();legacyConfirmedProof(true,true,false);
    demoReportAccess(owner);record("zero-refund-and-incomplete-proof",first,first.plusDays(1),owner);
    verify(paymentEvidence,atLeastOnce()).read(any());
    var destination=Path.of(System.getProperty("sporthub.report.output","target/report-compatibility.json"));
    Files.createDirectories(destination.toAbsolutePath().getParent());
    Files.writeString(destination,json.writerWithDefaultPrettyPrinter().writeValueAsString(cases));
    if(!queryScopes.isEmpty())Files.writeString(destination.resolveSibling("report-query-scope.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(queryScopes));
  }

  private void nextCourt(){court=UUID.randomUUID();((ObjectNode)quote).put("courtId",court.toString());}
  private Map<String,Object> cancelForCapture(Booking booking,Caller actor,String reason){
    return Map.of("refund",Map.of("id",seedHistoricalDemoRefund(booking,actor)));
  }
  private void record(String name,LocalDate from,LocalDate to,Caller owner){
    var report=demoReports.dashboard(facility,from,to,0,2,owner,"token");
    var all=new ArrayList<com.sporthub.booking.web.DemoFacilityReportDtos.Transaction>();
    var ids=new HashSet<String>();
    for(int page=0;page<report.transactions().totalPages();page++){
      var slice=demoReports.dashboard(facility,from,to,page,2,owner,"token");
      assertThat(slice.summary()).isEqualTo(report.summary());
      for(var item:slice.transactions().items()){assertThat(ids.add(item.id())).isTrue();all.add(item);}
    }
    assertThat(all).hasSize((int)report.transactions().total());
    var payload=json.valueToTree(report);
    ((ObjectNode)payload.path("transactions")).set("items",json.valueToTree(all));
    cases.put(name,payload);
    verifyQueryScope(name,from,to);
  }
  private void verifyQueryScope(String name,LocalDate from,LocalDate to){
    Class<?> type;try{type=Class.forName("com.sporthub.booking.service.ReportSnapshotReader");}catch(ClassNotFoundException before){return;}
    try{
      var window=com.sporthub.common.time.FacilityReportWindow.of(from,to,"Asia/Ho_Chi_Minh",clock);
      var method=type.getDeclaredMethod("read",UUID.class,com.sporthub.common.time.FacilityReportWindow.class,Instant.class);method.setAccessible(true);
      var snapshot=method.invoke(context.getBean(type),facility,window,clock.instant().isBefore(window.to())?clock.instant():window.to());
      var getter=snapshot.getClass().getDeclaredMethod("events");getter.setAccessible(true);
      var events=(List<?>)getter.invoke(snapshot);long total=repo.jdbc().queryForObject("SELECT count(*) FROM demo_money_events WHERE facility_id=?",Long.class,facility);
      for(var item:events){var row=(Map<?,?>)item;var value=row.get("performed_at");var at=value instanceof java.sql.Timestamp t?t.toInstant():((java.time.OffsetDateTime)value).toInstant();assertThat(at).isAfterOrEqualTo(window.from()).isBefore(window.to());}
      if(name.equals("carryover-after-later-execution"))assertThat(events.size()).isLessThan((int)total);
      queryScopes.put(name,Map.of("eventsLoaded",events.size(),"eventsInFacility",total));
    }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
  }
}
