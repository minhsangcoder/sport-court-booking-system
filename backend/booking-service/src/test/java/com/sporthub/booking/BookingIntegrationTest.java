package com.sporthub.booking;

import static com.sporthub.booking.web.BookingDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sporthub.booking.service.*;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.event.DomainEvent;
import com.sporthub.common.event.ReliableOutbox;
import com.sporthub.booking.web.GroupDtos.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(properties={"spring.rabbitmq.username=test","spring.rabbitmq.password=test","spring.rabbitmq.listener.simple.auto-startup=false",
 "spring.data.redis.password=test","SERVICE_CALL_SECRET=test-service-call-key-longer-than-thirty-two-characters","JWT_SECRET=test-signing-key-longer-than-thirty-two-characters","sporthub.events.outbox.enabled=false","booking.expiry-delay-ms=3600000"})
class BookingIntegrationTest {
 static PostgreSQLContainer<?> postgres;
 @DynamicPropertySource static void db(DynamicPropertyRegistry p){String url=System.getenv("SPORTHUB_BOOKING_TEST_DB_URL");if(url!=null){p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv("SPORTHUB_TEST_DB_USER"));p.add("spring.datasource.password",()->System.getenv("SPORTHUB_TEST_DB_PASSWORD"));}else{postgres=new PostgreSQLContainer<>("postgres:16-alpine");postgres.start();p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}}
 @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
 @Autowired BookingService service;@Autowired BookingRepository repo;@Autowired PaymentOutcomeConsumer consumer;@Autowired ObjectMapper json;
 @MockBean BookingDependencies dependencies;@MockBean Clock clock;
 @MockBean ReliableOutbox outbox;
 @Autowired GroupService groups;
 @Autowired TransferLeaseService transfers;
 Instant now=Instant.parse("2026-10-05T00:00:00Z"),start=now.plusSeconds(3600),end=now.plusSeconds(7200);
 UUID court,facility;Caller user;JsonNode quote;
 @BeforeEach void setup(){court=UUID.randomUUID();facility=UUID.randomUUID();user=customer();when(clock.instant()).thenReturn(now);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
  quote=json.valueToTree(Map.of("facilityId",facility,"courtId",court,"startsAt",start,"endsAt",end,"amount",150000,"currency","VND","timezone","Asia/Ho_Chi_Minh","segments",List.of()));when(dependencies.quote(any())).thenReturn(quote);
 }
 Caller customer(){return new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of());}
 HoldInput input(){return new HoldInput(court,start,end,new BigDecimal("150000"));}
 String key(){return UUID.randomUUID().toString();}
 com.fasterxml.jackson.databind.JsonNode success(Booking b,UUID payment){return json.valueToTree(DomainEvent.create("payment.completed",1,"payment-service",payment,Map.of("bookingId",b.id(),"paymentId",payment,"payerId",user.id(),"amount",b.amount(),"currency","VND","status","SUCCESS","paidAt",now,"purpose","BOOKING")));}
 @Test void transferLeasePreservesSnapshotAndRevokesPreviousQrExactlyOnce(){
  var h=service.hold(input(),key(),user,"token",false);var b=service.create(new CreateInput(h.id(),null,null),key(),user,"token",false);consumer.apply(success(b,UUID.randomUUID()));
  when(clock.instant()).thenReturn(start.minusSeconds(600));String oldQr=service.detail(b.id(),user,"token").checkinToken();UUID listing=UUID.randomUUID(),acquisition=UUID.randomUUID();var buyer=customer();
  var registration=new TransferLeaseService.Command(listing,b.id(),user.id(),new BigDecimal("100000"),start,null,null,null,null,null,null);transfers.command("register",registration);transfers.command("register",registration);
  var lock=new TransferLeaseService.Command(listing,b.id(),user.id(),null,null,acquisition,buyer.id(),start.minusSeconds(300),null,null,null);transfers.command("lock",lock);
  assertThat(repo.find(b.id()).currentHolderId()).isEqualTo(user.id());
  var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of());UUID id=b.id();assertThatThrownBy(()->service.checkin(id,new CheckinInput(oldQr),staff,"token")).isInstanceOf(ConflictException.class);
  UUID payment=UUID.randomUUID();var paid=new TransferLeaseService.Command(listing,b.id(),user.id(),new BigDecimal("100000"),start,acquisition,buyer.id(),start.minusSeconds(300),payment,clock.instant(),"VND");transfers.command("complete",paid);transfers.command("complete",paid);
  var result=repo.find(id);assertThat(result.currentHolderId()).isEqualTo(buyer.id());assertThat(result.customerId()).isEqualTo(buyer.id());assertThat(result.createdBy()).isEqualTo(user.id());assertThat(result.amount()).isEqualByComparingTo("150000");assertThat(result.priceSnapshot()).isEqualTo(b.priceSnapshot());
  assertThat(service.detail(id,user,"token").checkinToken()).isNull();assertThatThrownBy(()->service.checkin(id,new CheckinInput(oldQr),staff,"token")).isInstanceOf(ForbiddenException.class);
  assertThat(repo.history(id).stream().filter(x->x.action().equals("TRANSFER_COMPLETED"))).hasSize(1);assertThat(service.checkin(id,new CheckinInput(service.detail(id,buyer,"token").checkinToken()),staff,"token").status()).isEqualTo("CHECKED_IN");
 }
 @Test void databasePreventsConcurrentHoldsAndIdempotencyReusesResource()throws Exception{
  var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{var futures=new ArrayList<Future<Boolean>>();for(int n=0;n<2;n++){var actor=customer();futures.add(pool.submit(()->{ready.countDown();go.await();try{service.hold(input(),key(),actor,"token",false);return true;}catch(ConflictException ex){return false;}}));}ready.await(5,TimeUnit.SECONDS);go.countDown();int winners=0;for(var f:futures)if(f.get(10,TimeUnit.SECONDS))winners++;assertThat(winners).isEqualTo(1);}finally{pool.shutdownNow();}
  court=UUID.randomUUID();quote=((com.fasterxml.jackson.databind.node.ObjectNode)quote).put("courtId",court.toString());when(dependencies.quote(any())).thenReturn(quote);
  String key=key();var hold=service.hold(input(),key,user,"token",false);assertThat(service.hold(input(),key,user,"token",false).id()).isEqualTo(hold.id());
  assertThatThrownBy(()->service.hold(new HoldInput(court,start,end,new BigDecimal("1")),key,user,"token",false)).isInstanceOf(ConflictException.class);
 }
 @Test void immutableSnapshotPaymentInboxAndCheckinLifecycle(){
  var h=service.hold(input(),key(),user,"token",false);var booking=service.create(new CreateInput(h.id(),null,null),key(),user,"token",false);
  assertThat(booking.status()).isEqualTo("PENDING");var payment=UUID.randomUUID();var event=success(booking,payment);consumer.apply(event);consumer.apply(event);
  booking=repo.find(booking.id());assertThat(booking.status()).isEqualTo("CONFIRMED");assertThat(repo.history(booking.id()).stream().filter(v->v.action().equals("CONFIRMED"))).hasSize(1);
  UUID id=booking.id();assertThatThrownBy(()->repo.jdbc().update("UPDATE bookings SET amount=1 WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
  when(clock.instant()).thenReturn(start.minusSeconds(600));String token=service.detail(id,user,"token").checkinToken();var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(facility,Set.of("BOOKING_CHECK_IN","BOOKING_COMPLETE")));
  assertThat(service.checkin(id,new CheckinInput(token),staff,"token").status()).isEqualTo("CHECKED_IN");assertThatThrownBy(()->service.checkin(id,new CheckinInput(token),staff,"token")).isInstanceOf(ConflictException.class);
  when(clock.instant()).thenReturn(end.plusSeconds(1));assertThat(service.complete(id,staff,"token").status()).isEqualTo("COMPLETED");
 }
 @Test void expiredHoldsReleaseAndLateMoneyRequiresReview(){
  var h=service.hold(input(),key(),user,"token",false);var b=service.create(new CreateInput(h.id(),null,null),key(),user,"token",false);
  when(clock.instant()).thenReturn(now.plusSeconds(601));service.expireCourt(court);assertThat(repo.find(b.id()).status()).isEqualTo("EXPIRED");assertThat(repo.hold(h.id()).state()).isEqualTo("RELEASED");
  consumer.apply(success(b,UUID.randomUUID()));assertThat(repo.find(b.id()).status()).isEqualTo("EXPIRED");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_reconciliation WHERE booking_id=?",Integer.class,b.id())).isEqualTo(1);
 }
 @Test void ownershipAndPriceChangeAreRejected(){
  var h=service.hold(input(),key(),user,"token",false);assertThatThrownBy(()->service.ownHold(h.id(),customer())).isInstanceOf(ForbiddenException.class);
  var changed=quote.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("amount",200000);when(dependencies.quote(any())).thenReturn(changed);
  assertThatThrownBy(()->service.create(new CreateInput(h.id(),null,null),key(),user,"token",false)).isInstanceOf(ConflictException.class);
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM bookings WHERE reservation_id=?",Integer.class,h.id())).isZero();
 }
 CreateGroup groupInput(){start=now.plusSeconds(86400);end=start.plusSeconds(3600);quote=((com.fasterxml.jackson.databind.node.ObjectNode)quote).put("startsAt",start.toString()).put("endsAt",end.toString());when(dependencies.quote(any())).thenReturn(quote);var h=service.hold(input(),key(),user,"token",false);return new CreateGroup(h.id(),"QA team",10);}
 JsonNode contribution(com.sporthub.booking.web.GroupDtos.Group g,com.sporthub.booking.web.GroupDtos.Member m,Caller payer,UUID payment){return json.valueToTree(DomainEvent.create("payment.completed",1,"payment-service",payment,Map.of("bookingId",g.id(),"memberId",m.id(),"paymentId",payment,"payerId",payer.id(),"amount",m.amountDue(),"currency","VND","status","SUCCESS","paidAt",clock.instant(),"purpose","GROUP_CONTRIBUTION")));}
 @Test void groupJoinEqualSplitContributionsAndInboxConfirmExactlyOnce(){
  var input=groupInput();String key=key();var g=groups.create(input,key,user,"token");assertThat(groups.create(input,key,user,"token").id()).isEqualTo(g.id());
  var second=customer();String code=groups.invite(g.id(),user).code();g=groups.join(new JoinGroup(code),second);assertThat(groups.join(new JoinGroup(code),second).members()).hasSize(2);
  UUID id=g.id();assertThatThrownBy(()->groups.invite(id,second)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.payable(id,user)).isInstanceOf(ConflictException.class);
  g=groups.split(id,new Split("EQUAL",null),user);assertThat(g.members()).allMatch(m->m.amountDue().compareTo(new BigDecimal("75000"))==0);
  for(var m:g.members()){var payer=m.userId().equals(user.id())?user:second;groups.payable(id,m.id(),payer);var event=contribution(g,m,payer,UUID.randomUUID());consumer.apply(event);consumer.apply(event);}
  assertThat(groups.detail(id,user).state()).isEqualTo("CONFIRMED");assertThat(repo.find(id).status()).isEqualTo("CONFIRMED");assertThat(service.detail(id,user,"token").checkinQrSvg()).contains("<svg");
  assertThat(repo.history(id).stream().filter(h->h.action().equals("GROUP_CONFIRMED"))).hasSize(1);
 }
 @Test void customSplitConservesVndRemainderAndInFlightPaymentsFreezeMutations(){
  var input=groupInput();var g=groups.create(input,key(),user,"token");String code=groups.invite(g.id(),user).code();g=groups.join(new JoinGroup(code),customer());g=groups.join(new JoinGroup(code),customer());
  UUID id=g.id();var invalid=new Split("CUSTOM",List.of(new Allocation(g.members().get(0).id(),BigDecimal.ONE)));
  assertThatThrownBy(()->groups.split(id,invalid,user)).isInstanceOf(ConflictException.class);
  g=groups.split(id,new Split("EQUAL",null),user);var first=g.members().get(0);groups.payable(id,first.id(),user);
  assertThatThrownBy(()->groups.split(id,new Split("EQUAL",null),user)).isInstanceOf(ConflictException.class).hasMessageContaining("BLOCKED_RULE");
  var outsider=customer();assertThatThrownBy(()->groups.join(new JoinGroup(code),outsider)).isInstanceOf(ConflictException.class);
  consumer.apply(contribution(g,first,user,UUID.randomUUID()));assertThat(groups.detail(id,user).totalPaid()).isEqualByComparingTo(first.amountDue());
 }
 @Test void groupTimeoutReleasesSlotAndRequestsOriginalPayerRefundReview(){
  var input=groupInput();var g=groups.create(input,key(),user,"token");var other=customer();g=groups.join(new JoinGroup(groups.invite(g.id(),user).code()),other);g=groups.split(g.id(),new Split("EQUAL",null),user);
  var member=g.members().stream().filter(m->m.userId().equals(other.id())).findFirst().orElseThrow();UUID payment=UUID.randomUUID();consumer.apply(contribution(g,member,other,payment));
  when(clock.instant()).thenReturn(g.deadline().plusSeconds(1));service.expireCourt(court);assertThat(groups.detail(g.id(),user).state()).isEqualTo("GROUP_EXPIRED");assertThat(repo.find(g.id()).status()).isEqualTo("EXPIRED");
  verify(outbox).record(any(),argThat(event->event.eventType().equals("booking.group.refund.requested")));
  UUID id=g.id();assertThatThrownBy(()->groups.payable(id,member.id(),other)).isInstanceOf(ConflictException.class);
 }
 @Test void equalSplitAssignsEveryRemainderDongToBookingOwner(){
  start=now.plusSeconds(86400);end=start.plusSeconds(3600);
  quote=((com.fasterxml.jackson.databind.node.ObjectNode)quote).put("amount",150001).put("startsAt",start.toString()).put("endsAt",end.toString());when(dependencies.quote(any())).thenReturn(quote);
  var h=service.hold(new HoldInput(court,start,end,new BigDecimal("150001")),key(),user,"token",false);
  var g=groups.create(new CreateGroup(h.id(),"Remainder QA",5),key(),user,"token");String code=groups.invite(g.id(),user).code();groups.join(new JoinGroup(code),customer());groups.join(new JoinGroup(code),customer());
  g=groups.split(g.id(),new Split("EQUAL",null),user);
  assertThat(g.members().stream().filter(m->m.userId().equals(user.id())).findFirst().orElseThrow().amountDue()).isEqualByComparingTo("50001");
  assertThat(g.members().stream().map(m->m.amountDue()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("150001");
 }
}
