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
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class BookingIntegrationTest {
 static PostgreSQLContainer<?> postgres;
 @DynamicPropertySource static void db(DynamicPropertyRegistry p){String url=System.getenv("SPORTHUB_BOOKING_TEST_DB_URL");if(url!=null){p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv("SPORTHUB_TEST_DB_USER"));p.add("spring.datasource.password",()->System.getenv("SPORTHUB_TEST_DB_PASSWORD"));}else{postgres=new PostgreSQLContainer<>("postgres:16-alpine");postgres.start();p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}}
 @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
 @Autowired BookingService service;@Autowired BookingRepository repo;@Autowired PaymentOutcomeConsumer consumer;@Autowired ObjectMapper json;
 @MockBean BookingDependencies dependencies;@MockBean Clock clock;
 @MockBean DiscoveryDependencies discoveryDependencies;
 @Autowired DiscoveryService discovery;
 @MockBean com.sporthub.common.security.RemoteIdentity identity;
 @MockBean ReliableOutbox outbox;
 @Autowired GroupService groups;
 @Autowired org.springframework.test.web.servlet.MockMvc http;
 @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
 @Autowired TransferLeaseService transfers;
 @Autowired com.sporthub.booking.web.BookingMonitoringController monitoring;
 Instant now=Instant.parse("2026-10-05T00:00:00Z"),start=now.plusSeconds(3600),end=now.plusSeconds(7200);
 UUID court,facility;Caller user;JsonNode quote;
 @BeforeEach void setup(){court=UUID.randomUUID();facility=UUID.randomUUID();user=customer();when(clock.instant()).thenReturn(now);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
  quote=json.valueToTree(Map.of("facilityId",facility,"courtId",court,"startsAt",start,"endsAt",end,"amount",150000,"currency","VND","timezone","Asia/Ho_Chi_Minh","segments",List.of()));when(dependencies.quote(any())).thenReturn(quote);
 }
 Caller customer(){return new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of());}
 HoldInput input(){return new HoldInput(court,start,end,new BigDecimal("150000"));}
 String key(){return UUID.randomUUID().toString();}

 @Test void discoveryUsesRealReservationExclusionsAndPublicSummaries() {
  UUID category=UUID.randomUUID();String day=now.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().toString();
  when(discoveryDependencies.categories()).thenReturn(json.valueToTree(List.of(Map.of("id",category,"name","Pickleball","active",true))));
  when(discoveryDependencies.facilities(any(),any(),any(),any())).thenReturn(json.valueToTree(List.of(Map.of("id",facility,"ownerId",user.id(),"name","Search Court Center","province","Hà Nội","district","Cầu Giấy","addressLine","Public address","timezone","Asia/Ho_Chi_Minh","latitude",21,"longitude",105,"amenities",List.of("Parking")))));
  when(discoveryDependencies.courts(facility)).thenReturn(json.valueToTree(List.of(Map.of("id",court,"name","Enabled court","sportCategoryId",category,"enabled",true),Map.of("id",UUID.randomUUID(),"name","Disabled court","sportCategoryId",category,"enabled",false))));
  var preview=json.createObjectNode();preview.put("timezone","Asia/Ho_Chi_Minh");var slots=preview.putArray("slots");
  slots.addObject().put("startsAt",start.toString()).put("endsAt",end.toString()).put("amount",150000).put("currency","VND").put("state","ELIGIBLE");
  slots.addObject().put("startsAt",end.toString()).put("endsAt",end.plusSeconds(3600).toString()).put("amount",100000).put("currency","VND").put("state","ELIGIBLE");
  slots.addObject().put("startsAt",end.plusSeconds(3600).toString()).put("endsAt",end.plusSeconds(7200).toString()).put("amount",1).put("currency","VND").put("state","BLOCKED").put("reason","MAINTENANCE");
  when(dependencies.preview(court,day)).thenReturn(preview);
  var criteria=new DiscoveryService.Criteria("Search",null,null,category,day,null,null,null,null,21d,105d,1d,"DISTANCE");
  var first=discovery.search(criteria);assertThat(first.items()).hasSize(1);assertThat(first.items().getFirst().availableSlots()).isEqualTo(2);assertThat(first.items().getFirst().fromPrice()).isEqualByComparingTo("100000");assertThat(first.items().getFirst().distanceKm()).isZero();
  assertThat(json.valueToTree(first).toString()).doesNotContain("ownerId","phone","email","customerId","checkinToken");
  service.hold(input(),key(),user,"token",false);
  var held=discovery.search(criteria).items().getFirst();assertThat(held.availableSlots()).isEqualTo(1);assertThat(held.firstStartsAt()).isEqualTo(end);
  assertThat(discovery.search(new DiscoveryService.Criteria(null,null,null,null,day,null,null,null,new BigDecimal("99999"),null,null,null,"PRICE_ASC")).items()).isEmpty();
  assertThat(discovery.search(new DiscoveryService.Criteria(null,null,null,null,day,"08:00","09:00",null,null,null,null,null,null)).items()).isEmpty();
  assertThat(discovery.search(new DiscoveryService.Criteria(null,null,null,null,day,null,null,null,null,0d,0d,1d,"DISTANCE")).items()).isEmpty();
 }
 @Test void discoveryRejectsInvalidCriteriaAndReportsDependencyFailure() {
  assertThatThrownBy(()->discovery.search(new DiscoveryService.Criteria(null,null,null,null,"invalid",null,null,null,null,null,null,null,null))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->discovery.search(new DiscoveryService.Criteria(null,null,null,null,null,"20:00","06:00",null,null,null,null,null,null))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->discovery.search(new DiscoveryService.Criteria(null,null,null,null,null,null,null,new BigDecimal("200"),new BigDecimal("100"),null,null,null,null))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->discovery.search(new DiscoveryService.Criteria(null,null,null,null,null,null,null,null,null,21d,null,1d,"DISTANCE"))).isInstanceOf(IllegalArgumentException.class);
  when(discoveryDependencies.categories()).thenThrow(new org.springframework.web.client.ResourceAccessException("Unreachable private service"));
  assertThatThrownBy(()->discovery.search(new DiscoveryService.Criteria(null,null,null,null,null,null,null,null,null,null,null,null,null))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("503");
 }
 com.fasterxml.jackson.databind.JsonNode success(Booking b,UUID payment){return json.valueToTree(DomainEvent.create("payment.completed",1,"payment-service",payment,Map.of("bookingId",b.id(),"paymentId",payment,"payerId",user.id(),"amount",b.amount(),"currency","VND","status","SUCCESS","paidAt",now,"purpose","BOOKING")));}
 @Test void adminMonitoringReportsRealDataWithoutGrantingUsageAndRejectsCustomers(){
  var h=service.hold(input(),key(),user,"token",false);var b=service.create(new CreateInput(h.id(),null,null),key(),user,"token",false);consumer.apply(success(b,UUID.randomUUID()));var request=new org.springframework.mock.web.MockHttpServletRequest();when(identity.current(request)).thenReturn(user);assertThatThrownBy(()->monitoring.list(b.id(),null,null,request)).isInstanceOf(ForbiddenException.class);
  var admin=new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of());when(identity.current(request)).thenReturn(admin);assertThat(monitoring.list(b.id(),null,null,request).getData()).hasSize(1);assertThat(monitoring.detail(b.id(),request).getData().checkinToken()).isNull();assertThat(monitoring.statistics(null,null,request).getData().get("states")).isInstanceOf(List.class);
  var owner=new Caller(UUID.randomUUID(),"Owner",Set.of("OWNER"),Map.of());when(identity.current(request)).thenReturn(owner);assertThat((List<?>)monitoring.ownerReport(facility,now.minusSeconds(86400),end.plusSeconds(86400),request).getData().get("courts")).hasSize(1);
 }
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
 Group reminderGroup(Caller... others){court=UUID.randomUUID();((com.fasterxml.jackson.databind.node.ObjectNode)quote).put("courtId",court.toString());var g=groups.create(groupInput(),key(),user,"token");String code=groups.invite(g.id(),user).code();for(var other:others)g=groups.join(new JoinGroup(code),other);return groups.split(g.id(),new Split("EQUAL",null),user);}
 Member recipient(Group g,Caller actor){return g.members().stream().filter(m->m.userId().equals(actor.id())).findFirst().orElseThrow();}
 PaymentReminders selected(UUID... ids){return new PaymentReminders(List.of(ids),false);}
 @Test void reminderOneRecordsAcceptanceAndServerContentWithoutChangingFinances(){
  var other=customer();var g=reminderGroup(other);var m=recipient(g,other);var result=groups.remind(g.id(),selected(m.id()),user);
  assertThat(result.acceptedCount()).isEqualTo(1);assertThat(result.members().getFirst().lastReminderAt()).isEqualTo(now);
  var after=groups.detail(g.id(),user);assertThat(after.booking()).isEqualTo(g.booking());assertThat(after.totalPaid()).isEqualByComparingTo(g.totalPaid());
  assertThat(after.members()).extracting(Member::amountDue,Member::amountPaid,Member::paymentState,Member::paymentRequestedAt).containsExactlyElementsOf(g.members().stream().map(x->org.assertj.core.groups.Tuple.tuple(x.amountDue(),x.amountPaid(),x.paymentState(),x.paymentRequestedAt())).toList());
  assertThat(recipient(after,other).lastReminderAt()).isEqualTo(now);assertThat(recipient(after,other).reminderBlockedReason()).isEqualTo("COOLDOWN");
  verify(outbox).record(eq(com.sporthub.common.event.RabbitMQConfig.EXCHANGE_BOOKING),argThat(e->{var p=json.valueToTree(e.payload());return e.eventType().equals("booking.group.payment.reminder")&&p.path("recipientId").asText().equals(other.id().toString())&&p.path("amount").decimalValue().compareTo(m.amountDue())==0&&p.path("deadline").asText().equals(g.deadline().toString());}));
  assertThat(repo.history(g.id()).stream().filter(h->h.action().equals("GROUP_PAYMENT_REMINDER_ACCEPTED"))).hasSize(1);
 }
 @Test void reminderMultipleAndAllExcludeSelfPaidAndCooldown(){
  var a=customer();var b=customer();var c=customer();var g=reminderGroup(a,b,c);var ma=recipient(g,a);var mb=recipient(g,b);var mc=recipient(g,c);
  consumer.apply(contribution(g,mc,c,UUID.randomUUID()));
  var result=groups.remind(g.id(),selected(ma.id(),mb.id(),mc.id(),recipient(g,user).id(),UUID.randomUUID()),user);
  assertThat(result.acceptedCount()).isEqualTo(2);assertThat(result.skippedCount()).isEqualTo(3);
  assertThat(result.members()).extracting(ReminderMemberResult::reason).contains("PAID","SELF","NOT_MEMBER");
  var all=groups.remind(g.id(),new PaymentReminders(null,true),user);assertThat(all.acceptedCount()).isZero();assertThat(all.skippedCount()).isEqualTo(3);
  assertThat(all.members()).extracting(ReminderMemberResult::reason).containsExactly("COOLDOWN","COOLDOWN","PAID");
  verify(outbox,times(2)).record(any(),any());
 }
 @Test void reminderAllSelectsEligibleMembersAndZeroShareIsSkipped(){
  var a=customer();var b=customer();var g=reminderGroup(a,b);
  g=groups.split(g.id(),new Split("CUSTOM",List.of(new Allocation(recipient(g,user).id(),new BigDecimal("75000")),new Allocation(recipient(g,a).id(),new BigDecimal("75000")),new Allocation(recipient(g,b).id(),BigDecimal.ZERO))),user);
  var result=groups.remind(g.id(),new PaymentReminders(null,true),user);assertThat(result.acceptedCount()).isEqualTo(1);assertThat(result.members()).extracting(ReminderMemberResult::reason).contains("PAID");
 }
 @Test void reminderSkipsMemberWhoPaidAfterOwnerRead(){
  var a=customer();var b=customer();var g=reminderGroup(a,b);var stale=recipient(g,a);assertThat(stale.reminderEligible()).isTrue();
  consumer.apply(contribution(g,stale,a,UUID.randomUUID()));
  var result=groups.remind(g.id(),selected(stale.id(),recipient(g,b).id()),user);
  assertThat(result.acceptedCount()).isEqualTo(1);assertThat(result.members().getFirst().reason()).isEqualTo("PAID");
  assertThat(recipient(groups.detail(g.id(),user),a).lastReminderAt()).isNull();
  verify(outbox,never()).record(any(),argThat(e->json.valueToTree(e.payload()).path("recipientId").asText().equals(a.id().toString())));
 }
 @Test void reminderRejectsClosedStatesDeadlinesAndUnallocatedGroups(){
  var a=customer();var draft=groups.create(groupInput(),key(),user,"token");UUID draftId=draft.id();
  assertThatThrownBy(()->groups.remind(draftId,new PaymentReminders(null,true),user)).isInstanceOf(ConflictException.class);
  for(String state:List.of("CANCELLED","COMPLETED","EXPIRED","CONFIRMED","CHECKED_IN")){
   user=customer();
   var g=reminderGroup(a);repo.jdbc().update("UPDATE bookings SET status=? WHERE id=?",state,g.id());
   assertThatThrownBy(()->groups.remind(g.id(),selected(recipient(g,a).id()),user)).isInstanceOf(ConflictException.class);
  }
  for(String state:List.of("GROUP_CANCELLED","GROUP_EXPIRED","CONFIRMED")){
   user=customer();
   var g=reminderGroup(a);repo.jdbc().update("UPDATE booking_groups SET state=? WHERE id=?",state,g.id());
   assertThatThrownBy(()->groups.remind(g.id(),selected(recipient(g,a).id()),user)).isInstanceOf(ConflictException.class);
  }
  var g=reminderGroup(a);when(clock.instant()).thenReturn(g.deadline());
  assertThatThrownBy(()->groups.remind(g.id(),selected(recipient(g,a).id()),user)).isInstanceOf(ConflictException.class);
  verifyNoInteractions(outbox);
 }
 @Test void reminderInactiveMembersAndInvalidSelectionsCannotEnqueue(){
  var a=customer();var g=reminderGroup(a);var m=recipient(g,a);groups.leave(g.id(),a);groups.split(g.id(),new Split("EQUAL",null),user);
  assertThat(groups.remind(g.id(),selected(m.id()),user).members().getFirst().reason()).isEqualTo("NOT_MEMBER");
  for(var input:List.of(new PaymentReminders(null,false),selected(),new PaymentReminders(List.of(m.id()),true),new PaymentReminders(Arrays.asList(m.id(),m.id()),false),new PaymentReminders(Arrays.asList((UUID)null),false)))
   assertThatThrownBy(()->groups.remind(g.id(),input,user)).isInstanceOf(IllegalArgumentException.class);
  verifyNoInteractions(outbox);
 }
 @Test void reminderHttpAuthorizationValidationAndErrors()throws Exception{
  var a=customer();var g=reminderGroup(a);var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/groups/"+g.id()+"/payment-reminders").contentType("application/json").content("{\"remindAll\":true}");
  when(identity.current(any())).thenThrow(new UnauthorizedException("Authentication required"));http.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
  for(var caller:List.of(a,customer(),new Caller(UUID.randomUUID(),"Owner",Set.of("OWNER"),Map.of()),new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of()))){doReturn(caller).when(identity).current(any());http.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());}
  doReturn(user).when(identity).current(any());http.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/groups/"+g.id()+"/payment-reminders").contentType("application/json").content("{\"memberIds\":[null]}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
  assertThatThrownBy(()->groups.remind(UUID.randomUUID(),new PaymentReminders(null,true),user)).isInstanceOf(ResourceNotFoundException.class);
 }
 @Test void reminderEnqueueFailureRollsBackBatchAndCanRetry(){
  var a=customer();var b=customer();var g=reminderGroup(a,b);var calls=new java.util.concurrent.atomic.AtomicInteger();
  doAnswer(i->{if(calls.incrementAndGet()==2)throw new IllegalStateException("Controlled enqueue failure");DomainEvent<?> event=i.getArgument(1);repo.jdbc().update("INSERT INTO event_outbox(event_id,exchange,routing_key,body) VALUES(?,?,?,?::jsonb)",event.eventId(),i.getArgument(0),event.eventType(),repo.write(event));return null;}).when(outbox).record(any(),any());
  assertThatThrownBy(()->groups.remind(g.id(),new PaymentReminders(null,true),user)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("503");
  assertThat(groups.detail(g.id(),user).members()).allMatch(m->m.lastReminderAt()==null);
  assertThat(repo.history(g.id()).stream().filter(h->h.action().equals("GROUP_PAYMENT_REMINDER_ACCEPTED"))).isEmpty();
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM event_outbox WHERE body->>'aggregateId'=?",Integer.class,g.id().toString())).isZero();
  doNothing().when(outbox).record(any(),any());assertThat(groups.remind(g.id(),new PaymentReminders(null,true),user).acceptedCount()).isEqualTo(2);
 }
 @Test void reminderDeadlineCrossingDuringBatchRollsBackEarlierAcceptances(){
  var a=customer();var b=customer();var g=reminderGroup(a,b);
  doAnswer(i->{DomainEvent<?> event=i.getArgument(1);repo.jdbc().update("INSERT INTO event_outbox(event_id,exchange,routing_key,body) VALUES(?,?,?,?::jsonb)",event.eventId(),i.getArgument(0),event.eventType(),repo.write(event));when(clock.instant()).thenReturn(g.deadline());return null;}).when(outbox).record(any(),any());
  assertThatThrownBy(()->groups.remind(g.id(),new PaymentReminders(null,true),user)).isInstanceOf(ConflictException.class);
  assertThat(groups.detail(g.id(),user).members()).allMatch(m->m.lastReminderAt()==null);
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM event_outbox WHERE body->>'aggregateId'=?",Integer.class,g.id().toString())).isZero();
  assertThat(repo.history(g.id()).stream().filter(h->h.action().equals("GROUP_PAYMENT_REMINDER_ACCEPTED"))).isEmpty();
 }
 @Test void reminderConcurrentRequestsAcceptOnlyOnce()throws Exception{
  var a=customer();var g=reminderGroup(a);var m=recipient(g,a);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{var futures=new ArrayList<Future<ReminderResult>>();for(int i=0;i<2;i++)futures.add(pool.submit(()->{ready.countDown();go.await();return groups.remind(g.id(),selected(m.id()),user);}));ready.await(5,TimeUnit.SECONDS);go.countDown();int accepted=0;for(var f:futures)accepted+=f.get(10,TimeUnit.SECONDS).acceptedCount();assertThat(accepted).isEqualTo(1);verify(outbox,times(1)).record(any(),any());}finally{pool.shutdownNow();}
 }
 @Test void reminderWaitsForConcurrentPaymentCommitThenSkips()throws Exception{
  var a=customer();var b=customer();var g=reminderGroup(a,b);var m=recipient(g,a);var payment=contribution(g,m,a,UUID.randomUUID());var locked=new CountDownLatch(1);var commit=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{
   var pay=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(txManager).execute(s->{repo.lock(court);consumer.apply(payment);locked.countDown();try{if(!commit.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Test timed out");}catch(InterruptedException ex){throw new IllegalStateException(ex);}return true;}));
   assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();var remind=pool.submit(()->groups.remind(g.id(),selected(m.id()),user));
   commit.countDown();assertThat(pay.get(10,TimeUnit.SECONDS)).isTrue();assertThat(remind.get(10,TimeUnit.SECONDS).members().getFirst().reason()).isEqualTo("PAID");verifyNoInteractions(outbox);
  }finally{commit.countDown();pool.shutdownNow();}
 }
}
