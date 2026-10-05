package com.sporthub.transfer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.sporthub.transfer.web.TransferDtos.*;
import com.sporthub.transfer.service.*;
import com.sporthub.transfer.repository.TransferRepository;
import com.sporthub.common.event.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
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

@SpringBootTest(properties={"spring.rabbitmq.username=test","spring.rabbitmq.password=test","spring.rabbitmq.listener.simple.auto-startup=false","SERVICE_CALL_SECRET=test-private-service-key-longer-than-thirty-two-characters","sporthub.events.outbox.enabled=false","transfer.workflow-delay-ms=3600000"})
class TransferIntegrationTest {
 static PostgreSQLContainer<?> postgres;
 @DynamicPropertySource static void db(DynamicPropertyRegistry p){String url=System.getenv("SPORTHUB_TRANSFER_TEST_DB_URL");if(url!=null){p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv("SPORTHUB_TEST_DB_USER"));p.add("spring.datasource.password",()->System.getenv("SPORTHUB_TEST_DB_PASSWORD"));}else{postgres=new PostgreSQLContainer<>("postgres:16-alpine");postgres.start();p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}}
 @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
 @Autowired TransferService service;@Autowired TransferWorkflow workflow;@Autowired TransferPaymentConsumer payments;@Autowired TransferRepository repo;@Autowired ObjectMapper json;
 @MockBean TransferDependencies dependencies;@MockBean Clock clock;@MockBean ReliableOutbox outbox;
 Instant now=Instant.parse("2026-10-05T00:00:00Z"),start=now.plusSeconds(86400);UUID book,facility,court;Caller seller,buyer;JsonNode booking;
 Caller customer(){return new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of());}String key(){return UUID.randomUUID().toString();}
 @BeforeEach void setup(){repo.jdbc().execute("TRUNCATE transfer_listings,request_idempotency,event_inbox,event_outbox CASCADE");seller=customer();buyer=customer();book=UUID.randomUUID();facility=UUID.randomUUID();court=UUID.randomUUID();when(clock.instant()).thenReturn(now);
  booking=json.valueToTree(Map.of("id",book,"facilityId",facility,"courtId",court,"currentHolderId",seller.id(),"startsAt",start,"endsAt",start.plusSeconds(3600),"paidAt",now,"status","CONFIRMED","amount",150000,"currency","VND"));when(dependencies.booking(book)).thenReturn(booking);when(dependencies.command(any(),any())).thenReturn(booking);
  when(dependencies.policy(facility)).thenReturn(json.valueToTree(Map.of("configured",true,"enabled",true,"minLeadSeconds",3600)));when(dependencies.context(court)).thenReturn(json.valueToTree(Map.of("facility",Map.of("name","Court Center","addressLine","Test address","province","TP HCM","district","1","timezone","Asia/Ho_Chi_Minh","amenities",List.of()),"court",Map.of("name","Court 1","sportCategoryId",UUID.randomUUID()))));
 }
 UUID listing(){UUID id=service.create(new Create(book,new BigDecimal("100000"),start.minusSeconds(3600)),key(),seller);workflow.sync(id);return id;}
 JsonNode outcome(UUID acquisition,UUID payment,String status){return json.valueToTree(DomainEvent.create("payment.completed",1,"payment-service",payment,Map.of("acquisitionId",acquisition,"bookingId",book,"paymentId",payment,"payerId",buyer.id(),"purpose","TRANSFER","amount",100000,"currency","VND","status",status,"paidAt",clock.instant())));}
 @Test void listingPolicyPrivacyCapAndIdempotency(){
  String key=key();var input=new Create(book,new BigDecimal("100000"),start.minusSeconds(3600));UUID id=service.create(input,key,seller);assertThat(service.create(input,key,seller)).isEqualTo(id);workflow.sync(id);
  var view=service.market(null,null,null,null,null,"start",buyer).stream().filter(l->l.id().equals(id)).findFirst().orElseThrow();String publicJson=repo.write(view);assertThat(publicJson).doesNotContain(seller.id().toString(),book.toString(),"sellerId","phone","checkin");
  assertThatThrownBy(()->service.acquire(id,key(),seller)).isInstanceOf(ForbiddenException.class);assertThatThrownBy(()->service.edit(id,new Edit(new BigDecimal("150001"),start),seller)).isInstanceOf(ConflictException.class);
  when(dependencies.policy(facility)).thenReturn(json.valueToTree(Map.of("configured",false)));assertThatThrownBy(()->service.acquire(id,key(),buyer)).isInstanceOf(ConflictException.class).hasMessageContaining("BLOCKED_RULE");
 }
 @Test void concurrentBuyersOnlyOneAcquisitionAndCancelReleases()throws Exception {
  UUID id=listing();var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);var buyers=List.of(buyer,customer());var futures=new ArrayList<Future<UUID>>();
  try{for(var actor:buyers)futures.add(pool.submit(()->{ready.countDown();go.await();try{return service.acquire(id,key(),actor);}catch(ConflictException ex){return null;}}));ready.await(5,TimeUnit.SECONDS);go.countDown();var winners=new ArrayList<UUID>();for(var f:futures){var result=f.get(10,TimeUnit.SECONDS);if(result!=null)winners.add(result);}assertThat(winners).hasSize(1);UUID acquisition=winners.get(0);var a=repo.acquisition(acquisition);var owner=buyers.stream().filter(c->c.id().equals(a.buyerId())).findFirst().orElseThrow();workflow.sync(id);service.cancel(acquisition,owner);workflow.sync(id);assertThat(repo.find(id).state()).isEqualTo("ACTIVE");assertThat(repo.acquisition(acquisition).state()).isEqualTo("CANCELLED");verify(dependencies).command(eq("unlock"),any());}finally{pool.shutdownNow();}
 }
 @Test void paymentInboxAndDurableHandoffRetryCompleteOnce(){
  UUID id=listing();String key=key();UUID a=service.acquire(id,key,buyer);assertThat(service.acquire(id,key,buyer)).isEqualTo(a);workflow.sync(id);assertThat(service.payable(a,buyer).amount()).isEqualByComparingTo("100000");UUID payment=UUID.randomUUID();var event=outcome(a,payment,"SUCCESS");payments.apply(event);payments.apply(event);
  var replay=(com.fasterxml.jackson.databind.node.ObjectNode)event.deepCopy();replay.put("eventId",UUID.randomUUID().toString());payments.apply(replay);verify(outbox,never()).record(any(),any());
  when(dependencies.command(eq("complete"),any())).thenThrow(new org.springframework.web.client.ResourceAccessException("Temporarily unavailable")).thenReturn(booking);workflow.sync(id);assertThat(repo.acquisition(a).state()).isEqualTo("PENDING_HANDOFF");assertThat(repo.find(id).workflow()).isEqualTo("HANDOFF");workflow.sync(id);workflow.sync(id);assertThat(repo.acquisition(a).state()).isEqualTo("SUCCESS");assertThat(repo.find(id).state()).isEqualTo("COMPLETED");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_audit WHERE listing_id=? AND action='TRANSFER_COMPLETED'",Integer.class,id)).isEqualTo(1);
 }
 @Test void registrationSurvivesServiceOutageWithoutPublishingAnUnregisteredListing(){
  when(dependencies.command(eq("register"),any())).thenThrow(new org.springframework.web.client.ResourceAccessException("Booking offline")).thenReturn(booking);UUID id=service.create(new Create(book,new BigDecimal("100000"),start.minusSeconds(3600)),key(),seller);workflow.sync(id);assertThat(repo.find(id).workflow()).isEqualTo("REGISTER");assertThat(service.market(null,null,null,null,null,"start",buyer).stream().noneMatch(l->l.id().equals(id))).isTrue();workflow.sync(id);assertThat(service.detail(id,buyer).available()).isTrue();
 }
 @Test void rejectedHandoffRequiresAuditAndPreservesPaymentReference(){
  UUID id=listing();UUID a=service.acquire(id,key(),buyer);workflow.sync(id);UUID payment=UUID.randomUUID();payments.apply(outcome(a,payment,"SUCCESS"));when(dependencies.command(eq("complete"),any())).thenThrow(new ConflictException("Booking checked in"));workflow.sync(id);assertThat(repo.find(id).state()).isEqualTo("PENDING_AUDIT");assertThat(repo.acquisition(a).paymentId()).isEqualTo(payment);assertThat(repo.acquisition(a).state()).isEqualTo("PENDING_AUDIT");verify(outbox).record(any(),argThat(e->e.eventType().equals("transfer.refund.review.requested")));
 }
 @Test void latePaymentDoesNotHandoffAndRequestsReviewForOriginalBuyer(){
  UUID id=listing();UUID a=service.acquire(id,key(),buyer);workflow.sync(id);when(clock.instant()).thenReturn(now.plusSeconds(601));workflow.sync(id);assertThat(repo.acquisition(a).state()).isEqualTo("EXPIRED");assertThat(repo.find(id).state()).isEqualTo("ACTIVE");UUID payment=UUID.randomUUID();var event=outcome(a,payment,"SUCCESS");payments.apply(event);payments.apply(event);verify(dependencies,never()).command(eq("complete"),any());assertThat(repo.jdbc().queryForObject("SELECT payer_id FROM transfer_reconciliation WHERE payment_id=?",UUID.class,payment)).isEqualTo(buyer.id());verify(outbox).record(any(),argThat(e->e.eventType().equals("transfer.refund.review.requested")));
 }
}
