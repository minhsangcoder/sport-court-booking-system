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

@SpringBootTest(properties={"transfer.finance.enabled=false","spring.rabbitmq.username=test","spring.rabbitmq.password=test","spring.rabbitmq.listener.simple.auto-startup=false","SERVICE_CALL_SECRET=test-private-service-key-longer-than-thirty-two-characters","sporthub.events.outbox.enabled=false","transfer.workflow-delay-ms=3600000"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class TransferIntegrationTest {
 static PostgreSQLContainer<?> postgres;
 @DynamicPropertySource static void db(DynamicPropertyRegistry p){String url=System.getenv("SPORTHUB_TRANSFER_TEST_DB_URL");if(url!=null){p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv("SPORTHUB_TEST_DB_USER"));p.add("spring.datasource.password",()->System.getenv("SPORTHUB_TEST_DB_PASSWORD"));}else{postgres=new PostgreSQLContainer<>("postgres:16-alpine");postgres.start();p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}}
 @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
 @Autowired TransferService service;@Autowired TransferWorkflow workflow;@Autowired TransferPaymentConsumer payments;@Autowired TransferRepository repo;@Autowired ObjectMapper json;
 @Autowired org.springframework.test.web.servlet.MockMvc http;@Autowired org.springframework.transaction.PlatformTransactionManager txManager;
 @MockBean com.sporthub.common.security.RemoteIdentity identity;
 @MockBean TransferDependencies dependencies;@MockBean Clock clock;@MockBean ReliableOutbox outbox;
 Instant now=Instant.parse("2026-10-05T00:00:00Z"),start=now.plusSeconds(86400);UUID book,facility,court;Caller seller,buyer;JsonNode booking;Map<UUID,com.fasterxml.jackson.databind.node.ObjectNode> remoteLeases;
 Caller customer(){return new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of(),"ACTIVE",true,false);}String key(){return UUID.randomUUID().toString();}
 @BeforeEach void setup(){seller=customer();buyer=customer();book=UUID.randomUUID();facility=UUID.randomUUID();court=UUID.randomUUID();remoteLeases=new HashMap<>();when(clock.instant()).thenReturn(now);
  booking=json.valueToTree(Map.of("id",book,"facilityId",facility,"courtId",court,"currentHolderId",seller.id(),"startsAt",start,"endsAt",start.plusSeconds(3600),"paidAt",now,"status","CONFIRMED","amount",150000,"currency","VND"));when(dependencies.booking(any())).thenReturn(json.createObjectNode().put("status","CANCELLED"));when(dependencies.booking(book)).thenReturn(booking);
  when(dependencies.command(any(),any())).thenAnswer(i->remoteCommand(i.getArgument(0),i.getArgument(1)));when(dependencies.receipt(any())).thenAnswer(i->{var p=remoteLeases.get(i.getArgument(0));return p==null?null:p.deepCopy();});
  when(dependencies.policy(facility)).thenReturn(json.valueToTree(Map.of("configured",true,"enabled",true,"minLeadSeconds",3600)));when(dependencies.context(court)).thenReturn(json.valueToTree(Map.of("facility",Map.of("name","Court Center","addressLine","Test address","provinceCode","01","province","Hà Nội","wardCode","00004","ward","Ba Đình","timezone","Asia/Ho_Chi_Minh","amenities",List.of()),"court",Map.of("name","Court 1","sportCategoryId",UUID.randomUUID()))));
 }
 JsonNode remoteCommand(String action,Map<String,Object> c){
  UUID id=(UUID)c.get("listingId");var p=remoteLeases.computeIfAbsent(id,k->json.createObjectNode());
  for(String key:List.of("listingId","bookingId","sellerId","acquisitionId","buyerId","expiresAt","deadline","price"))if(c.get(key)!=null)p.set(key,json.valueToTree(c.get(key)));p.put("currency","VND");
  p.put("state",switch(action){case "register","edit","unlock"->"ACTIVE";case "lock"->"LOCKED";case "withdraw"->"RELEASED";case "complete"->"COMPLETED";default->throw new IllegalArgumentException();});
  if(action.equals("complete"))p.put("commandFingerprint",com.sporthub.common.money.TransferHandoff.fingerprint(id,(UUID)c.get("bookingId"),(UUID)c.get("sellerId"),(UUID)c.get("acquisitionId"),(UUID)c.get("buyerId"),(UUID)c.get("paymentId"),(BigDecimal)c.get("price"),(String)c.get("currency"),Instant.parse(c.get("paidAt").toString()),(Instant)c.get("expiresAt"),(Instant)c.get("deadline")));
  return booking;
 }
 UUID historicalListing(JsonNode source, Instant deadline) {return HistoricalTransfers.listing(repo,source,dependencies.context(court),null,deadline);}
 UUID listing(){UUID id=historicalListing(booking,start.minusSeconds(3600));workflow.sync(id);return id;}
 UUID historicalAcquisition(UUID listing, Caller actor){return HistoricalTransfers.acquisition(repo,listing,actor.id(),clock.instant());}
 JsonNode outcome(UUID acquisition,UUID payment,String status){return json.valueToTree(DomainEvent.create("payment.completed",1,"payment-service",payment,Map.of("acquisitionId",acquisition,"bookingId",book,"paymentId",payment,"payerId",buyer.id(),"purpose","TRANSFER","amount",100000,"currency","VND","status",status,"paidAt",clock.instant())));}
 @Test void historicalListingPublicViewDoesNotLeakCustomerData(){
  UUID id=listing();var view=service.market(null,null,null,null,null,"start",buyer).stream().filter(l->l.id().equals(id)).findFirst().orElseThrow();
  assertThat(repo.write(view)).doesNotContain(seller.id().toString(),book.toString(),"sellerId","phone","checkin");
 }
 @Test void cancellingExistingUnpaidAcquisitionReleasesLegacyLease(){
  UUID id=listing(),a=historicalAcquisition(id,buyer);workflow.sync(id);service.cancel(a,buyer);workflow.sync(id);
  assertThat(repo.find(id).state()).isEqualTo("ACTIVE");assertThat(repo.acquisition(a).state()).isEqualTo("CANCELLED");verify(dependencies).command(eq("unlock"),any());
 }
 @Test void paymentInboxAndDurableHandoffRetryCompleteOnce(){
  UUID id=listing();String key=key();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);assertThat(service.payable(a,buyer).amount()).isEqualByComparingTo("100000");UUID payment=UUID.randomUUID();var event=outcome(a,payment,"SUCCESS");payments.apply(event);payments.apply(event);
  var replay=(com.fasterxml.jackson.databind.node.ObjectNode)event.deepCopy();replay.put("eventId",UUID.randomUUID().toString());payments.apply(replay);verify(outbox,never()).record(any(),any());
  var calls=new java.util.concurrent.atomic.AtomicInteger();doAnswer(i->{if(calls.incrementAndGet()==1)throw new org.springframework.web.client.ResourceAccessException("Temporarily unavailable");return remoteCommand(i.getArgument(0),i.getArgument(1));}).when(dependencies).command(eq("complete"),any());workflow.sync(id);assertThat(repo.acquisition(a).state()).isEqualTo("PENDING_HANDOFF");assertThat(repo.find(id).workflow()).isEqualTo("HANDOFF");workflow.sync(id);workflow.sync(id);assertThat(repo.acquisition(a).state()).isEqualTo("SUCCESS");assertThat(repo.find(id).state()).isEqualTo("COMPLETED");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_audit WHERE listing_id=? AND action='TRANSFER_COMPLETED'",Integer.class,id)).isEqualTo(1);
 }
 @Test void registrationSurvivesServiceOutageWithoutPublishingAnUnregisteredListing(){
  var calls=new java.util.concurrent.atomic.AtomicInteger();doAnswer(i->{if(calls.incrementAndGet()==1)throw new org.springframework.web.client.ResourceAccessException("Booking offline");return remoteCommand(i.getArgument(0),i.getArgument(1));}).when(dependencies).command(eq("register"),any());UUID id=historicalListing(booking,start.minusSeconds(3600));workflow.sync(id);assertThat(repo.find(id).workflow()).isEqualTo("REGISTER");assertThat(service.market(null,null,null,null,null,"start",buyer).stream().noneMatch(l->l.id().equals(id))).isTrue();workflow.sync(id);assertThat(service.detail(id,buyer).available()).isTrue();
 }
 @Test void rejectedHandoffRequiresAuditAndPreservesPaymentReference(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);UUID payment=UUID.randomUUID();payments.apply(outcome(a,payment,"SUCCESS"));doThrow(new ConflictException("Booking checked in")).when(dependencies).command(eq("complete"),any());workflow.sync(id);assertThat(repo.find(id).state()).isEqualTo("PENDING_AUDIT");assertThat(repo.acquisition(a).paymentId()).isEqualTo(payment);assertThat(repo.acquisition(a).state()).isEqualTo("PENDING_AUDIT");assertThat(repo.jdbc().queryForObject("SELECT reason FROM transfer_reconciliation WHERE payment_id=?",String.class,payment)).isEqualTo("HANDOFF_REJECTED");verify(outbox,never()).record(any(),any());
 }
 @Test void latePaymentDoesNotHandoffAndRequestsReviewForOriginalBuyer(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);when(clock.instant()).thenReturn(now.plusSeconds(601));workflow.sync(id);assertThat(repo.acquisition(a).state()).isEqualTo("EXPIRED");assertThat(repo.find(id).state()).isEqualTo("ACTIVE");UUID payment=UUID.randomUUID();var event=outcome(a,payment,"SUCCESS");payments.apply(event);payments.apply(event);verify(dependencies,never()).command(eq("complete"),any());assertThat(repo.jdbc().queryForObject("SELECT payer_id FROM transfer_reconciliation WHERE payment_id=?",UUID.class,payment)).isEqualTo(buyer.id());verify(outbox).record(any(),argThat(e->e.eventType().equals("transfer.refund.review.requested")));
 }

 @Test void sellerBuyerAndRoleChecksCannotBeBypassedWithAnotherListingOrAcquisitionId() {
  UUID listing=listing();var other=customer();

  assertThatThrownBy(()->service.withdraw(listing,other)).isInstanceOf(ForbiddenException.class);
  UUID acquisition=historicalAcquisition(listing,buyer);workflow.sync(listing);
  assertThatThrownBy(()->service.acquisition(acquisition,seller)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.payable(acquisition,other)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.cancel(acquisition,other)).isInstanceOf(ForbiddenException.class);
  for(String role:List.of("OWNER","STAFF","ADMIN")) {
   assertThatThrownBy(()->service.detail(listing,new Caller(UUID.randomUUID(),role,Set.of(role),Map.of()))).isInstanceOf(ForbiddenException.class);
  }
  assertThat(repo.acquisition(acquisition).state()).isEqualTo("PENDING");
 }


 @Test void authoritativeUsageOwnerRejectionHidesHistoricalListing(){
  UUID id=listing();((com.fasterxml.jackson.databind.node.ObjectNode)booking).put("transferBlockedReason","BLOCKED_RULE: counter operator is not the actual customer");
  assertThat(service.market(null,null,null,null,null,"start",buyer)).isEmpty();assertThat(service.detail(id,buyer).available()).isFalse();

  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_acquisitions WHERE listing_id=?",Integer.class,id)).isZero();
 }

 @Test void expiredHistoricalRegistrationCannotBePublished(){
  UUID id=historicalListing(booking,now.plusSeconds(30));when(clock.instant()).thenReturn(now.plusSeconds(30));workflow.sync(id);
  assertThat(repo.find(id).state()).isEqualTo("EXPIRED");assertThat(service.detail(id,buyer).available()).isFalse();verify(dependencies,never()).command(eq("register"),any());
 }
 @Test void lockedHistoricalListingCannotBeWithdrawn(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);
  assertThatThrownBy(()->service.withdraw(id,seller)).isInstanceOf(ConflictException.class);
  assertThat(repo.acquisition(a).amount()).isEqualByComparingTo("100000");assertThat(repo.find(id).state()).isEqualTo("LOCKED");
 }
 @Test void withdrawPreservesHistoryAndCanBeRetriedWithoutDuplicateAudit(){
  UUID id=listing();service.withdraw(id,seller);workflow.sync(id);service.withdraw(id,seller);workflow.sync(id);
  assertThat(repo.find(id).state()).isEqualTo("WITHDRAWN");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_audit WHERE listing_id=? AND action='LISTING_WITHDRAWN'",Integer.class,id)).isEqualTo(1);assertThat(service.market(null,null,null,null,null,"start",buyer)).noneMatch(l->l.id().equals(id));
 }
 @Test void pendingLocalLockIsNotPayableUntilMatchingBookingLeaseAcknowledged(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);assertThatThrownBy(()->service.payable(a,buyer)).isInstanceOf(ConflictException.class);
  workflow.sync(id);var proof=remoteLeases.get(id);proof.put("acquisitionId",UUID.randomUUID().toString());assertThatThrownBy(()->service.payable(a,buyer)).isInstanceOf(ConflictException.class);proof.put("acquisitionId",a.toString());assertThat(service.payable(a,buyer).amount()).isEqualByComparingTo("100000");
 }
 @Test void failedPaymentRetainsExclusiveAcquisitionAndCanRetryWithAnotherPayment(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);var failed=outcome(a,UUID.randomUUID(),"FAILED");payments.apply(failed);assertThat(repo.acquisition(a).state()).isEqualTo("PENDING");assertThat(repo.acquisition(a).paymentId()).isNull();assertThat(service.payable(a,buyer).amount()).isEqualByComparingTo("100000");
  UUID success=UUID.randomUUID();payments.apply(outcome(a,success,"SUCCESS"));var replay=(com.fasterxml.jackson.databind.node.ObjectNode)failed.deepCopy();replay.put("eventId",UUID.randomUUID().toString());payments.apply(replay);workflow.sync(id);
  assertThat(repo.acquisition(a).state()).isEqualTo("SUCCESS");assertThat(repo.acquisition(a).paymentId()).isEqualTo(success);assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_payment_receipts WHERE acquisition_id=?",Integer.class,a)).isEqualTo(2);
 }
 @Test void invalidStatusAndAggregateCannotChangeAcquisitionOrCreateReceipt(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);var event=(com.fasterxml.jackson.databind.node.ObjectNode)outcome(a,UUID.randomUUID(),"PROCESSING");assertThatThrownBy(()->payments.apply(event)).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
  ((com.fasterxml.jackson.databind.node.ObjectNode)event.path("payload")).put("status","SUCCESS");event.put("aggregateId",UUID.randomUUID().toString());assertThatThrownBy(()->payments.apply(event)).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);assertThat(repo.acquisition(a).state()).isEqualTo("PENDING");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_payment_receipts WHERE acquisition_id=?",Integer.class,a)).isZero();
 }
 @Test void samePaymentWithConflictingPayloadPreservesOriginalReceiptAndPayer(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);UUID payment=UUID.randomUUID();var event=outcome(a,payment,"SUCCESS");payments.apply(event);var altered=(com.fasterxml.jackson.databind.node.ObjectNode)event.deepCopy();altered.put("eventId",UUID.randomUUID().toString());((com.fasterxml.jackson.databind.node.ObjectNode)altered.path("payload")).put("payerId",UUID.randomUUID().toString());payments.apply(altered);
  assertThat(repo.jdbc().queryForObject("SELECT payload->>'payerId' FROM transfer_payment_receipts WHERE payment_id=?",String.class,payment)).isEqualTo(buyer.id().toString());assertThat(repo.jdbc().queryForObject("SELECT payer_id FROM transfer_reconciliation WHERE payment_id=?",UUID.class,payment)).isEqualTo(buyer.id());assertThat(repo.acquisition(a).paymentId()).isEqualTo(payment);
 }
 @Test void latePaymentForCancelledAcquisitionCannotTakeTheNewBuyersLock(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);service.cancel(a,buyer);workflow.sync(id);var other=customer();UUID next=historicalAcquisition(id,other);workflow.sync(id);payments.apply(outcome(a,UUID.randomUUID(),"SUCCESS"));
  assertThat(repo.find(id).activeAcquisitionId()).isEqualTo(next);assertThat(repo.acquisition(next).state()).isEqualTo("PENDING");assertThat(repo.acquisition(a).state()).isEqualTo("CANCELLED");verify(dependencies,never()).command(eq("complete"),any());
 }
 @Test void remoteCommitThenTimeoutIsRecoveredByReceiptWithoutRepeatingHandoff(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);payments.apply(outcome(a,UUID.randomUUID(),"SUCCESS"));
  doAnswer(i->{remoteCommand(i.getArgument(0),i.getArgument(1));throw new org.springframework.web.client.ResourceAccessException("Lost response after commit");}).when(dependencies).command(eq("complete"),any());workflow.sync(id);assertThat(repo.acquisition(a).state()).isEqualTo("PENDING_HANDOFF");workflow.sync(id);
  assertThat(repo.acquisition(a).state()).isEqualTo("SUCCESS");verify(dependencies,times(1)).command(eq("complete"),any());
 }
 @Test void httpSuccessWithoutMatchingReceiptCannotClaimHandoffSuccess(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);payments.apply(outcome(a,UUID.randomUUID(),"SUCCESS"));doReturn(booking).when(dependencies).command(eq("complete"),any());workflow.sync(id);
  assertThat(repo.find(id).workflow()).isEqualTo("HANDOFF");assertThat(repo.acquisition(a).state()).isEqualTo("PENDING_HANDOFF");assertThat(service.synchronization(id,buyer).get("lastErrorCode")).isEqualTo("BOOKING_PROOF_UNAVAILABLE");
 }
 @Test void technicalRetryExhaustionIsScopedAndCannotForceBusinessResolution(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);UUID payment=UUID.randomUUID();payments.apply(outcome(a,payment,"SUCCESS"));doThrow(new TransferDependencies.Unavailable("BOOKING_UNAVAILABLE")).when(dependencies).receipt(id);for(int i=0;i<10;i++)workflow.sync(id);
  assertThat(repo.acquisition(a).state()).isEqualTo("PENDING_AUDIT");assertThat(service.synchronization(id,buyer).get("retryable")).isEqualTo(true);assertThatThrownBy(()->service.retrySynchronization(id,customer())).isInstanceOf(ForbiddenException.class);
  doAnswer(i->remoteLeases.get(id).deepCopy()).when(dependencies).receipt(id);service.retrySynchronization(id,buyer);workflow.sync(id);assertThat(repo.acquisition(a).state()).isEqualTo("SUCCESS");assertThat(repo.acquisition(a).paymentId()).isEqualTo(payment);verify(outbox,never()).record(any(),any());
 }
 @Test void localFinalizeFailureRetainsVerifiedPaymentAndUsesRemoteReceiptOnRetry(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);payments.apply(outcome(a,UUID.randomUUID(),"SUCCESS"));String fault="transfer_fault_"+UUID.randomUUID().toString().replace("-","");repo.jdbc().execute("CREATE FUNCTION "+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Controlled transfer finalize failure'; END $$");repo.jdbc().execute("CREATE TRIGGER "+fault+" BEFORE INSERT ON transfer_audit FOR EACH ROW WHEN(NEW.listing_id='"+id+"'::uuid AND NEW.action='TRANSFER_COMPLETED') EXECUTE FUNCTION "+fault+"()");
  try{assertThatThrownBy(()->workflow.sync(id)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(repo.acquisition(a).state()).isEqualTo("PENDING_HANDOFF");assertThat(repo.find(id).workflow()).isEqualTo("HANDOFF");assertThat(remoteLeases.get(id).path("state").asText()).isEqualTo("COMPLETED");}finally{repo.jdbc().execute("DROP TRIGGER "+fault+" ON transfer_audit");repo.jdbc().execute("DROP FUNCTION "+fault+"()");}
  workflow.sync(id);assertThat(repo.find(id).state()).isEqualTo("COMPLETED");verify(dependencies,times(1)).command(eq("complete"),any());
 }
 @Test void databaseRejectsDuplicatePendingAcquisitionAndFinancialRewrites(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);
  assertThatThrownBy(()->repo.jdbc().update("INSERT INTO transfer_acquisitions(id,listing_id,buyer_id,amount,currency,expires_at,state) VALUES(?,?,?,100000,'VND',?,'PENDING')",UUID.randomUUID(),id,customer().id(),java.sql.Timestamp.from(repo.acquisition(a).expiresAt()))).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("one_unresolved_acquisition");
  UUID payment=UUID.randomUUID();payments.apply(outcome(a,payment,"SUCCESS"));workflow.sync(id);
  for(String sql:List.of("UPDATE transfer_acquisitions SET amount=1 WHERE id=?","UPDATE transfer_acquisitions SET payment_id=gen_random_uuid() WHERE id=?"))assertThatThrownBy(()->repo.jdbc().update(sql,a)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("immutable");
  assertThatThrownBy(()->repo.jdbc().update("DELETE FROM transfer_payment_receipts WHERE payment_id=?",payment)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("immutable");assertThatThrownBy(()->repo.jdbc().update("UPDATE transfer_listings SET state='ACTIVE' WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
 }
 @Test void unknownAcquisitionKeepsFinancialEvidenceForManualReview(){
  UUID a=UUID.randomUUID(),payment=UUID.randomUUID();var event=outcome(a,payment,"SUCCESS");payments.apply(event);var replay=(com.fasterxml.jackson.databind.node.ObjectNode)event.deepCopy();replay.put("eventId",UUID.randomUUID().toString());payments.apply(replay);
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_orphan_payments WHERE payment_id=?",Integer.class,payment)).isEqualTo(1);verify(dependencies,never()).command(eq("complete"),any());
 }
 @Test void synchronizationHttpIsPrivateToSellerOrActiveBuyer()throws Exception{
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);String path="/api/v1/transfers/"+id+"/synchronization";
  for(var actor:List.of(customer(),new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of()),new Caller(UUID.randomUUID(),"Owner",Set.of("OWNER"),Map.of()),new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of()))){doReturn(actor).when(identity).current(any());http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());}
  for(var actor:List.of(seller,buyer)){doReturn(actor).when(identity).current(any());http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());}
  doReturn(buyer).when(identity).current(any());http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/retry")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
 }
 @Test void searchFiltersAndSortApplyBeforeLimitAndSkipInvalidBookingRows(){
  var validBooks=new HashMap<UUID,JsonNode>();UUID wanted=null;
  for(int i=0;i<105;i++){
   UUID next=UUID.randomUUID();var data=(com.fasterxml.jackson.databind.node.ObjectNode)booking.deepCopy();data.put("id",next.toString());validBooks.put(next,data);doReturn(data).when(dependencies).booking(next);
   UUID id=historicalListing(data,start.minusSeconds(3600));if(i==104)repo.jdbc().update("UPDATE transfer_listings SET price=50000 WHERE id=?",id);workflow.sync(id);if(i==104)wanted=id;
  }
  assertThat(service.market(null,null,null,null,new BigDecimal("50000"),"price",buyer)).extracting(Listing::id).containsExactly(wanted);assertThat(service.market(null,null,null,null,null,"price",buyer).getFirst().id()).isEqualTo(wanted);
  // Make the first 100 rows in start order invalid; search must continue to the valid tail.
  var order=repo.rows("SELECT * FROM transfer_listings WHERE seller_id=? ORDER BY starts_at,id",seller.id());for(int i=0;i<100;i++){var data=(com.fasterxml.jackson.databind.node.ObjectNode)validBooks.get(order.get(i).bookingId());data.put("status","CANCELLED");}
  assertThat(service.market(null,null,null,null,null,"start",buyer)).hasSize(5);
 }
 @Test void searchValidationAndTemporaryDependencyFailureDoNotReturnStaleAvailability(){
  UUID id=listing();assertThatThrownBy(()->service.market(null,null,start,start.minusSeconds(1),null,"start",buyer)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.market(null,null,null,null,null,"unknown",buyer)).isInstanceOf(IllegalArgumentException.class);
  doThrow(new TransferDependencies.Unavailable("BOOKING_UNAVAILABLE")).when(dependencies).booking(book);assertThatThrownBy(()->service.market(null,null,null,null,null,"start",buyer)).isInstanceOf(TransferDependencies.Unavailable.class);assertThat(repo.find(id).state()).isEqualTo("ACTIVE");
 }
 @Test void marketplaceHidesListingsAtPolicyCutoffBeforeStoredListingDeadline(){
  UUID id=listing();when(dependencies.policy(facility)).thenReturn(json.valueToTree(Map.of("configured",true,"enabled",true,"minLeadSeconds",86400)));assertThat(service.detail(id,buyer).available()).isFalse();assertThat(service.market(null,null,null,null,null,"start",buyer)).noneMatch(l->l.id().equals(id));
 }
 @Test void businessHandoffReviewCannotBeReopenedAsTechnicalRetry(){
  UUID id=listing();UUID a=historicalAcquisition(id,buyer);workflow.sync(id);payments.apply(outcome(a,UUID.randomUUID(),"SUCCESS"));doThrow(new ConflictException("Booking already checked in")).when(dependencies).command(eq("complete"),any());workflow.sync(id);
  assertThat(service.synchronization(id,buyer).get("retryable")).isEqualTo(false);assertThatThrownBy(()->service.retrySynchronization(id,buyer)).isInstanceOf(ConflictException.class);assertThat(repo.acquisition(a).state()).isEqualTo("PENDING_AUDIT");
 }

 @Autowired com.sporthub.transfer.web.TransferCaseReferenceController caseReference;
 @Test void originalListingReferenceRequiresAdminAndDoesNotLeakContactOrUsageToken(){UUID id=listing();var request=new org.springframework.mock.web.MockHttpServletRequest();for(String role:List.of("CUSTOMER","OWNER","STAFF")){when(identity.current(request)).thenReturn(new Caller(UUID.randomUUID(),role,Set.of(role),Map.of()));assertThatThrownBy(()->caseReference.reference(id,null,request)).isInstanceOf(ForbiddenException.class);}when(identity.current(request)).thenReturn(new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of()));var reference=caseReference.reference(id,null,request).getData();assertThat(reference).containsEntry("listingId",id).containsEntry("bookingId",book);assertThat(json.valueToTree(reference).toString()).doesNotContain("phone","email","checkinToken","priceSnapshot");}
 @Test void administrativeReferencePreservesHistoricalAcquisitionTermsAfterListingEdit(){UUID id=listing();UUID old=historicalAcquisition(id,buyer);workflow.sync(id);service.cancel(old,buyer);workflow.sync(id);repo.jdbc().update("UPDATE transfer_listings SET price=90000 WHERE id=?",id);var request=new org.springframework.mock.web.MockHttpServletRequest();when(identity.current(request)).thenReturn(new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of()));var reference=caseReference.reference(id,old,request).getData();var acquisition=(Acquisition)reference.get("acquisition");assertThat(acquisition.amount()).isEqualByComparingTo("100000");assertThat(acquisition.state()).isEqualTo("CANCELLED");assertThat(repo.find(id).price()).isEqualByComparingTo("90000");}

}
