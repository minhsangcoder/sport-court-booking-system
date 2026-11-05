package com.sporthub.payment;
import static com.sporthub.payment.web.PaymentDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sporthub.payment.service.*;
import com.sporthub.payment.provider.*;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.exception.*;
import com.sporthub.common.event.ReliableOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

@SpringBootTest(properties={"spring.profiles.active=local","DEMO_SEED_ENABLED=false","spring.rabbitmq.username=test","spring.rabbitmq.password=test",
 "spring.rabbitmq.listener.simple.auto-startup=false","SERVICE_CALL_SECRET=synthetic-wallet-test-key-over-thirty-two-characters",
 "sporthub.payment.demo.callback-secret=local-callback-key-longer-than-thirty-two-characters","sporthub.events.outbox.delay-ms=3600000","sporthub.payment.expiry-delay-ms=3600000","sporthub.payment.sync.delay-ms=3600000","sporthub.finance.movements.delay-ms=3600000"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PaymentIntegrationTest {
 static PostgreSQLContainer<?> postgres;
 @DynamicPropertySource static void db(DynamicPropertyRegistry p){String url=System.getenv("SPORTHUB_PAYMENT_TEST_DB_URL");if(url!=null){p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv("SPORTHUB_TEST_DB_USER"));p.add("spring.datasource.password",()->System.getenv("SPORTHUB_TEST_DB_PASSWORD"));}else{postgres=new PostgreSQLContainer<>("postgres:16-alpine");postgres.start();p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}}
 @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
 @Autowired org.springframework.test.web.servlet.MockMvc http;@Autowired org.springframework.transaction.PlatformTransactionManager txManager;@Autowired PaymentExpiry expiry;
 @Autowired PaymentService service;@Autowired PaymentRepository repo;@Autowired DemoPaymentProvider provider;@Autowired ObjectMapper json;
 @Autowired GroupRefundConsumer refunds;
 @Autowired TransferRefundConsumer transferRefunds;
 @Autowired com.sporthub.payment.web.InternalOwnerWalletController wallets;
 @Autowired com.sporthub.payment.web.PaymentMonitoringController monitoring;
 @Autowired SellerPayoutAccounts sellerAccounts;
 @Autowired PaymentReportFacts reportFacts;
 @Autowired TransferSettlementService settlements;
 @Autowired MovementExecutions movements;
 @Autowired DemoMovementProvider movementProvider;
 @Autowired FinancialLedger ledger;
 @MockBean com.sporthub.common.security.RemoteIdentity identity;
 @MockBean PayableClient payable;@MockBean java.time.Clock clock;@MockBean BookingReceiptClient receipts;@Autowired PaymentSynchronization synchronization;@org.springframework.boot.test.mock.mockito.SpyBean ReliableOutbox outbox;
 Caller user;UUID booking;Instant testNow;
 @BeforeEach void setup(){testNow=Instant.now();when(clock.instant()).thenReturn(testNow);when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);user=new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of());booking=UUID.randomUUID();when(payable.payable(eq(booking),isNull(),any())).thenReturn(json.valueToTree(Map.of("bookingId",booking,"payerId",user.id(),"amount",150000,"currency","VND","expiresAt",testNow.plusSeconds(600),"purpose","BOOKING")));}
 String key(){return UUID.randomUUID().toString();}
 record FinancialBill(UUID acquisition,com.fasterxml.jackson.databind.node.ObjectNode bill,com.fasterxml.jackson.databind.node.ObjectNode terms){}
 FinancialBill financialBill(){
  var seller=new Caller(UUID.randomUUID(),"Seller A",Set.of("CUSTOMER"),Map.of());UUID acquisition=UUID.randomUUID(),facility=UUID.randomUUID(),court=UUID.randomUUID();
  var destination=sellerAccounts.register(new SellerPayoutAccounts.Input(null,"DEMO","DEMO destination 1234","Seller consents to a demo payout destination"),key(),seller);
  var beneficiary=json.valueToTree(sellerAccounts.verified(destination.id(),seller.id()));
  var policy=json.valueToTree(Map.of("configured",true,"id",UUID.randomUUID(),"version",1,"rateBps",8000,"currency","VND","rounding","HALF_UP","termsHash",com.sporthub.common.security.Signatures.sha256(facility+"|1|8000|VND|HALF_UP|FACILITY_POLICY_CANCELLATION")));
  var expires=testNow.plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  var quote=com.sporthub.common.money.TransferFinancialTerms.seller(json,booking,facility,court,seller.id(),new BigDecimal("100000"),expires.plusSeconds(3600),beneficiary,policy);
  var terms=com.sporthub.common.money.TransferFinancialTerms.acquisition(quote,UUID.randomUUID(),1,acquisition,user.id(),expires,testNow.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
  var bill=json.createObjectNode().put("bookingId",booking.toString()).put("payerId",user.id().toString()).put("sellerId",seller.id().toString()).put("acquisitionId",acquisition.toString()).put("purpose","TRANSFER").put("amount",100000).put("currency","VND").put("expiresAt",expires.toString());bill.set("transferTerms",terms);
  when(payable.transfer(eq(acquisition),any())).thenReturn(bill);return new FinancialBill(acquisition,bill,terms);
 }
 FinancialBill demoBill(){
  UUID acquisition=UUID.randomUUID(),facility=UUID.randomUUID(),court=UUID.randomUUID(),seller=UUID.randomUUID(),owner=UUID.randomUUID();
  var expires=testNow.plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  var source=json.createObjectNode().put("id",booking.toString()).put("facilityId",facility.toString()).put("courtId",court.toString());
  source.set("refundPolicy",json.valueToTree(Map.of("configured",true,"id",UUID.randomUUID(),"version",1,"rateBps",8000,"currency","VND","rounding","HALF_UP","termsHash",com.sporthub.common.security.Signatures.sha256(facility+"|1|8000|VND|HALF_UP|FACILITY_POLICY_CANCELLATION"))));
  var receiver=json.createObjectNode().put("id",facility.toString()).put("ownerId",owner.toString()).put("name","Demo receiving facility");
  var fee=json.createObjectNode().put("configured",true).put("feeBps",500).put("version",1).put("termsHash",com.sporthub.common.security.Signatures.sha256(facility+"|1|500|VND|HALF_UP|FACILITY_TRANSFER_FEE"));
  var sellerTerms=com.sporthub.common.money.DemoTransferTerms.seller(json,source,receiver,fee,seller,new BigDecimal("100000"),expires.plusSeconds(3600));
  var terms=com.sporthub.common.money.TransferFinancialTerms.acquisition(sellerTerms,UUID.randomUUID(),1,acquisition,user.id(),expires,testNow.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
  var bill=json.createObjectNode().put("bookingId",booking.toString()).put("payerId",user.id().toString()).put("sellerId",seller.toString()).put("acquisitionId",acquisition.toString()).put("purpose","TRANSFER").put("amount",100000).put("currency","VND").put("expiresAt",expires.toString());bill.set("transferTerms",terms);
  when(payable.transfer(eq(acquisition),any())).thenReturn(bill);return new FinancialBill(acquisition,bill,terms);
 }
 @Test void demoTransferPaymentCapturesFacilityTermsWithoutAnyBankSetupAndEmitsCanonicalHandoff(){
  var f=demoBill();String request=key();var input=new CreatePayment(booking,null,f.acquisition());var p=service.create(input,request,user,"token");
  var stored=repo.jdbc().queryForObject("SELECT snapshot::text FROM demo_transfer_payment_terms WHERE payment_id=?",String.class,p.id());
  assertThat(stored).contains(f.terms().path("receiverOwnerId").asText()).doesNotContain("beneficiaryRevisionId");
  assertThat(service.create(input,request,user,"token").id()).isEqualTo(p.id());
  var completed=callback(p,"SUCCESS");service.callback(completed,provider.sign(completed));assertThat(repo.find(p.id()).status()).isEqualTo("SUCCESS");
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_escrow WHERE payment_id=?",Integer.class,p.id())).isZero();
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_settlements WHERE payment_id=?",Integer.class,p.id())).isZero();
  var events=repo.jdbc().queryForList("SELECT body::text FROM event_outbox WHERE body->'payload'->>'paymentId'=?",String.class,p.id().toString());
  assertThat(events).anySatisfy(e->assertThat(e).contains("transfer.collection.verified").contains(f.terms().path("termsHash").asText()));
  assertThat(events).anySatisfy(e->assertThat(e).contains("payment.completed").contains("transferTerms").contains("DEMO"));
  assertThatThrownBy(()->repo.jdbc().update("UPDATE demo_transfer_payment_terms SET snapshot='{}'::jsonb WHERE payment_id=?",p.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
 }
 @Test void demoTransferRejectsTamperedServerBindingAndFailedCollectionNeverStartsHandoff(){
  var f=demoBill();f.bill().put("amount",1);
  assertThatThrownBy(()->service.create(new CreatePayment(booking,null,f.acquisition()),key(),user,"token")).isInstanceOf(ConflictException.class);
  f.bill().put("amount",100000);var p=service.create(new CreatePayment(booking,null,f.acquisition()),key(),user,"token");
  var failed=callback(p,"FAILED");service.callback(failed,provider.sign(failed));
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM event_outbox WHERE routing_key='transfer.collection.verified' AND body->'payload'->>'paymentId'=?",Integer.class,p.id().toString())).isZero();
 }
 @Test void financialTermsCaptureServerAcquisitionAndReplayKeepsTheAcceptedSnapshot(){
  var f=financialBill();String request=key();var input=new CreatePayment(booking,null,f.acquisition());var p=service.create(input,request,user,"token");
  var stored=repo.jdbc().queryForMap("SELECT * FROM transfer_payment_terms WHERE payment_id=?",p.id());assertThat(stored.get("terms_hash")).isEqualTo(f.terms().path("termsHash").asText());assertThat((BigDecimal)stored.get("quoted_fee_amount")).isEqualByComparingTo("2000");assertThat((BigDecimal)stored.get("quoted_seller_net_amount")).isEqualByComparingTo("98000");
  assertThat(service.create(input,request,user,"token").id()).isEqualTo(p.id());assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_payment_terms WHERE payment_id=?",Integer.class,p.id())).isEqualTo(1);
 }
 @Test void financialTermsRejectMalformedOrDifferentBuyerWithoutLeavingAnOrder(){
  var f=financialBill();f.terms().put("quotedFeeAmount","0");assertThatThrownBy(()->service.create(new CreatePayment(booking,null,f.acquisition()),key(),user,"token")).isInstanceOf(ConflictException.class);
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_orders WHERE booking_id=?",Integer.class,booking)).isZero();
 }
 @Test void financialTermsIgnoreForgedBrowserFeeSellerAndDestinationFields()throws Exception{
  var f=financialBill();when(identity.current(any())).thenReturn(user);
  String body=json.writeValueAsString(Map.of("bookingId",booking,"acquisitionId",f.acquisition(),"feeBps",0,"sellerNetAmount","100000","sellerId",UUID.randomUUID(),"beneficiaryRevisionId",UUID.randomUUID()));
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/payments").contentType("application/json").header("Idempotency-Key",key()).header("Authorization","Bearer fixture-token").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
  assertThat(repo.jdbc().queryForObject("SELECT quoted_fee_amount FROM transfer_payment_terms WHERE booking_id=?",BigDecimal.class,booking)).isEqualByComparingTo("2000");
  assertThat(repo.jdbc().queryForObject("SELECT buyer_id FROM transfer_payment_terms WHERE booking_id=?",UUID.class,booking)).isEqualTo(user.id());
 }
 @Test void financialCollectionCreatesOneCanonicalProofAndHoldsFundsWithoutEarningFeeBeforeHandoff(){
  var f=financialBill();var p=service.create(new CreatePayment(booking,null,f.acquisition()),key(),user,"token");var callback=callback(p,"SUCCESS");service.callback(callback,provider.sign(callback));service.callback(callback,provider.sign(callback));
  assertThat(repo.jdbc().queryForObject("SELECT state FROM transfer_settlements WHERE payment_id=?",String.class,p.id())).isEqualTo("WAITING_HANDOFF");
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM verified_payment_collections WHERE payment_id=? AND canonical AND money_class='DEMO'",Integer.class,p.id())).isEqualTo(1);
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM financial_journals WHERE payment_id=?",Integer.class,p.id())).isEqualTo(2);
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM money_movement_orders WHERE payment_id=?",Integer.class,p.id())).isZero();
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM event_outbox WHERE body->>'eventType'='transfer.collection.verified' AND body->'payload'->>'paymentId'=?",Integer.class,p.id().toString())).isEqualTo(1);
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM financial_postings p JOIN financial_accounts a ON a.id=p.account_id JOIN financial_journals j ON j.id=p.journal_id WHERE j.payment_id=? AND a.kind='TRANSFER_FEE_REVENUE'",Integer.class,p.id())).isZero();
 }
 @Test void financialCollectionTwoSuccessesSettleOnlyOneCanonicalPaymentAndKeepExtraFundsSeparate(){
  var f=financialBill();var input=new CreatePayment(booking,null,f.acquisition());var first=service.create(input,key(),user,"token");
  // Simulate the first provider order expiring early while the acquisition remains payable.
  repo.jdbc().update("UPDATE payment_orders SET status='EXPIRED' WHERE id=?",first.id());var second=service.create(input,key(),user,"token");
  for(var p:List.of(second,first)){var callback=callback(p,"SUCCESS");service.callback(callback,provider.sign(callback));}
  assertThat(repo.jdbc().queryForObject("SELECT payment_id FROM transfer_settlements WHERE acquisition_id=?",UUID.class,f.acquisition())).isEqualTo(second.id());
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM verified_payment_collections WHERE acquisition_id=? AND canonical",Integer.class,f.acquisition())).isEqualTo(1);
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM event_outbox WHERE body->>'eventType'='transfer.collection.verified' AND body->'payload'->>'acquisitionId'=?",Integer.class,f.acquisition().toString())).isEqualTo(1);
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM financial_journals WHERE payment_id IN(?,?)",Integer.class,first.id(),second.id())).isEqualTo(4);
 }
 com.fasterxml.jackson.databind.JsonNode completedEvent(FinancialBill f,Payment original){
  var p=repo.find(original.id());var terms=f.terms();String hash=com.sporthub.common.money.TransferHandoff.fingerprint(UUID.fromString(terms.path("listingId").asText()),booking,p.sellerId(),f.acquisition(),p.payerId(),p.id(),p.amount(),p.currency(),p.paidAt(),Instant.parse(terms.path("expiresAt").asText()),Instant.parse(terms.path("deadline").asText()));
  var payload=json.createObjectNode();for(String field:List.of("acquisitionId","listingId","bookingId","facilityId","courtId","sellerId","buyerId","termsHash","amount","currency"))payload.set(field,terms.path(field));
  payload.put("amount",p.amount());var handed=testNow.plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.MICROS);payload.put("paymentId",p.id().toString());payload.put("commandFingerprint",hash);payload.put("handoffAt",handed.toString());payload.put("bookingVersion",3);payload.put("decisionRef",f.acquisition().toString());
  var decision=payload.deepCopy();decision.put("origin","ACQUISITION_DECISION");decision.put("decision","COMPLETED");decision.put("decidedAt",handed.toString());doReturn(decision).when(receipts).acquisitionDecision(f.acquisition());
  return json.valueToTree(com.sporthub.common.event.DomainEvent.create("transfer.handoff.completed",1,"transfer-service",f.acquisition(),payload));
 }
 @Test void financialHandoffAllocatesFeeAndSellerDebtExactlyOnceBeforePlay(){
  var f=financialBill();var p=service.create(new CreatePayment(booking,null,f.acquisition()),key(),user,"token");var callback=callback(p,"SUCCESS");service.callback(callback,provider.sign(callback));
  var event=completedEvent(f,p);settlements.completed(event);settlements.completed(event);
  assertThat(repo.jdbc().queryForObject("SELECT state FROM transfer_settlements WHERE payment_id=?",String.class,p.id())).isEqualTo("ALLOCATED");
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM financial_journals WHERE payment_id=? AND effect_type='TRANSFER_ALLOCATION'",Integer.class,p.id())).isEqualTo(1);
  var order=repo.jdbc().queryForMap("SELECT * FROM money_movement_orders WHERE payment_id=?",p.id());assertThat(order.get("state")).isEqualTo("NEW");assertThat((BigDecimal)order.get("amount")).isEqualByComparingTo("98000");assertThat(order.get("payee_id")).isEqualTo(p.sellerId());
  assertThat(repo.jdbc().queryForObject("SELECT sum(l.amount) FROM financial_postings l JOIN financial_accounts a ON a.id=l.account_id JOIN financial_journals j ON j.id=l.journal_id WHERE j.payment_id=? AND a.kind='TRANSFER_FEE_REVENUE' AND l.side='C'",BigDecimal.class,p.id())).isEqualByComparingTo("2000");
 }
 @Test void financialHandoffRejectsWrongDecisionAndDoesNotAllocateWhileBookingIsUnavailable(){
  var f=financialBill();var p=service.create(new CreatePayment(booking,null,f.acquisition()),key(),user,"token");var callback=callback(p,"SUCCESS");service.callback(callback,provider.sign(callback));var event=completedEvent(f,p);
  var bad=((com.fasterxml.jackson.databind.node.ObjectNode)event).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("payload")).put("commandFingerprint","0".repeat(64));assertThatThrownBy(()->settlements.completed(bad)).isInstanceOf(ConflictException.class);
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM money_movement_orders WHERE payment_id=?",Integer.class,p.id())).isZero();
  when(receipts.acquisitionDecision(f.acquisition())).thenThrow(new BookingReceiptClient.Unavailable());assertThatThrownBy(()->settlements.completed(event)).isInstanceOf(BookingReceiptClient.Unavailable.class);
  assertThat(repo.jdbc().queryForObject("SELECT state FROM transfer_settlements WHERE payment_id=?",String.class,p.id())).isEqualTo("WAITING_HANDOFF");
  settlements.completed(completedEvent(f,p));
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM money_movement_orders WHERE payment_id=?",Integer.class,p.id())).isEqualTo(1);
 }
 UUID payoutOrder(){var f=financialBill();var p=service.create(new CreatePayment(booking,null,f.acquisition()),key(),user,"token");var callback=callback(p,"SUCCESS");service.callback(callback,provider.sign(callback));settlements.completed(completedEvent(f,p));return repo.jdbc().queryForObject("SELECT id FROM money_movement_orders WHERE payment_id=?",UUID.class,p.id());}
 MovementProvider.Command movementCommand(UUID attempt)throws Exception{return json.readValue(repo.jdbc().queryForObject("SELECT request_body::text FROM money_movement_attempts WHERE id=?",String.class,attempt),MovementProvider.Command.class);}
 String movementState(UUID order){return repo.jdbc().queryForObject("SELECT state FROM money_movement_orders WHERE id=?",String.class,order);}
 @Test void movementTwoWorkersReserveOneAttemptAndEarlyFinalCallbackCannotBePaidTwice()throws Exception{
  UUID order=payoutOrder();var go=new java.util.concurrent.CountDownLatch(1);var attempts=new ArrayList<UUID>();
  try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){var jobs=new ArrayList<java.util.concurrent.Future<Optional<UUID>>>();for(int i=0;i<2;i++)jobs.add(workers.submit(()->{go.await();return movements.claim(order);}));go.countDown();for(var job:jobs)attempts.add(job.get().orElseThrow());}
  assertThat(attempts.getFirst()).isEqualTo(attempts.getLast());UUID attempt=attempts.getFirst();var command=movementCommand(attempt);var early=movementProvider.submit(command);movements.accept(early,"WEBHOOK");movements.accept(early,"WEBHOOK");movements.dispatch(attempt,true);
  assertThat(movementState(order)).isEqualTo("SUCCEEDED");assertThat(repo.jdbc().queryForObject("SELECT executed_total FROM money_movement_orders WHERE id=?",BigDecimal.class,order)).isEqualByComparingTo("98000");
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM money_movement_attempts WHERE order_id=?",Integer.class,order)).isEqualTo(1);assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM demo_provider_movement_commands WHERE order_id=?",Integer.class,order)).isEqualTo(1);
  assertThat(repo.jdbc().queryForObject("SELECT remaining_amount FROM financial_funding_reservations WHERE attempt_id=?",BigDecimal.class,attempt)).isZero();assertThat(movements.claim(order)).isEmpty();
 }
 @Test void movementUnknownAfterBankFinalQueriesOriginalKeyWithoutAnotherDebit()throws Exception{
  UUID order=payoutOrder(),attempt=movements.claim(order).orElseThrow();movementProvider.fault(order,DemoMovementProvider.Fault.TIMEOUT_AFTER_SUCCESS);movements.dispatch(attempt,true);
  assertThat(movementState(order)).isEqualTo("UNKNOWN");assertThat(movements.claim(order)).contains(attempt);
  when(clock.instant()).thenReturn(testNow.plusSeconds(41));movements.dispatch(attempt,true);assertThat(movementState(order)).isEqualTo("SUCCEEDED");
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM demo_provider_movement_commands WHERE order_id=?",Integer.class,order)).isEqualTo(1);assertThat(repo.jdbc().queryForObject("SELECT submit_count FROM money_movement_attempts WHERE id=?",Integer.class,attempt)).isEqualTo(1);
 }
 @Test void movementAcceptedDebitAndFinalAreDifferentAndOnlyReturnedAmountCanBeRetried()throws Exception{
  UUID order=payoutOrder(),attempt=movements.claim(order).orElseThrow();var command=movementCommand(attempt);movementProvider.fault(order,DemoMovementProvider.Fault.ACCEPT_ONLY);movements.dispatch(attempt,true);
  assertThat(movementState(order)).isEqualTo("PROCESSING");assertThat(repo.jdbc().queryForObject("SELECT executed_total FROM money_movement_orders WHERE id=?",BigDecimal.class,order)).isZero();
  var debit=movementProvider.event(command,"DEBITED",new BigDecimal("98000"),key());movements.accept(debit,"WEBHOOK");assertThat(movementState(order)).isEqualTo("PROCESSING");
  var finalProof=movementProvider.event(command,"FINAL",new BigDecimal("98000"),key());movements.accept(finalProof,"WEBHOOK");movements.accept(debit,"QUERY");assertThat(movementState(order)).isEqualTo("SUCCEEDED");
  var available=movementProvider.event(command,"RETURN_AVAILABLE",new BigDecimal("20000"),key());movements.accept(available,"WEBHOOK");assertThat(movementState(order)).isEqualTo("SUCCEEDED");
  var returned=movementProvider.event(command,"RETURN",new BigDecimal("20000"),key());movements.accept(returned,"WEBHOOK");movements.accept(returned,"QUERY");assertThat(movementState(order)).isEqualTo("RETURNED");
  var second=movements.claim(order).orElseThrow();assertThat(movementCommand(second).amount()).isEqualByComparingTo("20000");movementProvider.fault(order,DemoMovementProvider.Fault.SUCCESS);movements.dispatch(second,true);
  assertThat(movementState(order)).isEqualTo("SUCCEEDED");assertThat(repo.jdbc().queryForObject("SELECT executed_total-returned_total FROM money_movement_orders WHERE id=?",BigDecimal.class,order)).isEqualByComparingTo("98000");
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM financial_journals WHERE movement_id=? AND effect_type='MOVEMENT_FINAL'",Integer.class,order)).isEqualTo(2);
 }
 @Test void movementDefiniteFailureRestoresDebtAndForgedProofCannotReleaseReservation()throws Exception{
  UUID order=payoutOrder(),attempt=movements.claim(order).orElseThrow();var command=movementCommand(attempt);movementProvider.fault(order,DemoMovementProvider.Fault.FAIL_NO_OUTFLOW);movements.dispatch(attempt,true);assertThat(movementState(order)).isEqualTo("FAILED_RETRYABLE");
  UUID retry=movements.claim(order).orElseThrow();assertThat(retry).isNotEqualTo(attempt);var second=movementCommand(retry);movementProvider.fault(order,DemoMovementProvider.Fault.ACCEPT_ONLY);movements.dispatch(retry,true);var proof=movementProvider.event(second,"FINAL",second.amount(),key());
  var fake=new MovementProvider.Event(proof.provider(),proof.account(),proof.region(),proof.eventId(),proof.reference(),proof.orderId(),proof.attemptId(),proof.kind(),proof.amount(),proof.currency(),proof.counterparty(),proof.occurredAt(),"forged");assertThatThrownBy(()->movements.accept(fake,"WEBHOOK")).isInstanceOf(ForbiddenException.class);
  assertThat(movementState(order)).isEqualTo("PROCESSING");assertThat(repo.jdbc().queryForObject("SELECT remaining_amount FROM financial_funding_reservations WHERE attempt_id=?",BigDecimal.class,retry)).isEqualByComparingTo("98000");
  movements.accept(proof,"WEBHOOK");assertThat(movementState(order)).isEqualTo("SUCCEEDED");
 }
 @Test void reportFactsVerifyCallbackCanonicalOutcomeAndNeverMutateSyncOutboxOrMoney(){
  var p=service.create(new CreatePayment(booking,null),key(),user,"token");var callback=callback(p,"SUCCESS");service.callback(callback,provider.sign(callback));
  var input=new com.sporthub.common.money.PaymentProofFacts.Request(List.of(new com.sporthub.common.money.PaymentProofFacts.Binding(p.id(),booking,null,"BOOKING")));
  String before=repo.jdbc().queryForObject("SELECT to_jsonb(s)::text FROM payment_booking_sync s WHERE payment_id=?",String.class,p.id());int events=repo.jdbc().queryForObject("SELECT count(*) FROM event_outbox",Integer.class);
  var result=reportFacts.read(input);var fact=result.facts().getFirst();assertThat(fact.verified()).isTrue();assertThat(fact.canonical()).isTrue();assertThat(fact.moneyClass()).isEqualTo("DEMO");assertThat(fact.amount()).isEqualTo("150000.00");assertThat(fact.proofSource()).isEqualTo("VERIFIED_COLLECTION");
  assertThat(repo.jdbc().queryForObject("SELECT to_jsonb(s)::text FROM payment_booking_sync s WHERE payment_id=?",String.class,p.id())).isEqualTo(before);assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM event_outbox",Integer.class)).isEqualTo(events);
  var mismatch=reportFacts.read(new com.sporthub.common.money.PaymentProofFacts.Request(List.of(new com.sporthub.common.money.PaymentProofFacts.Binding(p.id(),UUID.randomUUID(),null,"BOOKING"))));assertThat(mismatch.facts()).isEmpty();assertThat(mismatch.problems().getFirst().reason()).isEqualTo("BINDING_MISMATCH");
 }
 @Test void reportFactsRequireServiceSignatureAndRejectMoreThanFiveHundredBindings()throws Exception{
  String path="/api/v1/internal/payments/report-facts";var bindings=new ArrayList<com.sporthub.common.money.PaymentProofFacts.Binding>();for(int i=0;i<501;i++)bindings.add(new com.sporthub.common.money.PaymentProofFacts.Binding(UUID.randomUUID(),UUID.randomUUID(),null,"BOOKING"));
  String body=json.writeValueAsString(new com.sporthub.common.money.PaymentProofFacts.Request(bindings));long timestamp=Instant.now().getEpochSecond();
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").header("Authorization","Bearer browser-token").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").header("X-Service-Time",timestamp).header("X-Service-Signature",com.sporthub.common.security.ServiceCalls.sign("synthetic-wallet-test-key-over-thirty-two-characters","POST",path,timestamp,body)).content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
  body=json.writeValueAsString(new com.sporthub.common.money.PaymentProofFacts.Request(bindings.subList(0,1)));String valid=body;
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").header("X-Service-Time",timestamp).header("X-Service-Signature",com.sporthub.common.security.ServiceCalls.sign("synthetic-wallet-test-key-over-thirty-two-characters","POST",path,timestamp,valid)).content(valid)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
 }
 Callback callback(Payment p,String status){return new Callback(p.id(),p.providerReference(),"TX-"+p.id(),p.amount(),p.currency(),status,clock.instant().getEpochSecond());}
 private org.springframework.mock.web.MockHttpServletRequest walletRequest(String body,boolean valid){
  var r=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/internal/owner-wallets/prepare");long time=Instant.now().getEpochSecond();r.addHeader("X-Service-Time",Long.toString(time));r.addHeader("X-Service-Signature",valid?com.sporthub.common.security.ServiceCalls.sign("synthetic-wallet-test-key-over-thirty-two-characters",r.getMethod(),r.getRequestURI(),time,body):"forged");return r;
 }
 @Test void walletPreparationIsSignedConcurrentIdempotentAndDoesNotCreatePaymentOrPayout() throws Exception {
  UUID owner=UUID.randomUUID(),application=UUID.randomUUID();String body=json.writeValueAsString(Map.of("applicationId",application,"userId",owner,"commissionPercent","5.00"));
  assertThatThrownBy(()->wallets.prepare(body,walletRequest(body,false))).isInstanceOf(ForbiddenException.class);
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var first=pool.submit(()->wallets.prepare(body,walletRequest(body,true)));var second=pool.submit(()->wallets.prepare(body,walletRequest(body,true)));assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS).getData().get("initialized")).isEqualTo(true);assertThat(second.get(10,java.util.concurrent.TimeUnit.SECONDS).getData().get("initialized")).isEqualTo(true);}
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM owner_wallets WHERE owner_id=?",Integer.class,owner)).isEqualTo(1);
  assertThat(repo.jdbc().queryForObject("SELECT available_balance FROM owner_wallets WHERE owner_id=?",BigDecimal.class,owner)).isEqualByComparingTo("0");
  assertThat(repo.jdbc().queryForObject("SELECT currency FROM owner_wallets WHERE owner_id=?",String.class,owner)).isEqualTo("VND");
  String different=json.writeValueAsString(Map.of("applicationId",application,"userId",owner,"commissionPercent","6.00"));assertThatThrownBy(()->wallets.prepare(different,walletRequest(different,true))).isInstanceOf(ConflictException.class);
  verify(outbox,never()).record(any(),any());
 }
 @Test void monitoringRequiresAdminAndShowsVerifiedCallbackAndActualAggregates(){
  var p=service.create(new CreatePayment(booking,null),key(),user,"token");var c=callback(p,"SUCCESS");service.callback(c,provider.sign(c));var request=new org.springframework.mock.web.MockHttpServletRequest();when(identity.current(request)).thenReturn(user);assertThatThrownBy(()->monitoring.detail(p.id(),request)).isInstanceOf(ForbiddenException.class);
  when(identity.current(request)).thenReturn(new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of()));assertThat(monitoring.list(p.id().toString(),"SUCCESS",null,null,request).getData()).hasSize(1);assertThat((List<?>)monitoring.detail(p.id(),request).getData().get("callbacks")).hasSize(1);assertThat(monitoring.statistics(null,null,request).getData().get("orders")).isInstanceOf(List.class);
 }
 @Test void transferPaymentEscrowAndRefundReviewRetainVerifiedPayer(){
  UUID acquisition=UUID.randomUUID(),seller=UUID.randomUUID();when(payable.transfer(eq(acquisition),any())).thenReturn(json.valueToTree(Map.of("bookingId",booking,"payerId",user.id(),"sellerId",seller,"acquisitionId",acquisition,"amount",100000,"currency","VND","expiresAt",testNow.plusSeconds(600),"purpose","TRANSFER")));
  String key=key();var input=new CreatePayment(booking,null,acquisition);var p=service.create(input,key,user,"token");assertThat(service.create(input,key,user,"token").id()).isEqualTo(p.id());var c=callback(p,"SUCCESS");service.callback(c,provider.sign(c));service.callback(c,provider.sign(c));var escrow=repo.jdbc().queryForList("SELECT * FROM transfer_escrow WHERE payment_id=?",p.id());assertThat(escrow).hasSize(1);assertThat(escrow.get(0).get("state")).isEqualTo("HELD_POLICY_BLOCKED");assertThat(escrow.get(0).get("seller_id")).isEqualTo(seller);
  var event=json.valueToTree(com.sporthub.common.event.DomainEvent.create("transfer.refund.review.requested",1,"transfer-service",booking,Map.of("bookingId",booking,"paymentId",p.id(),"payerId",user.id(),"reason","LATE_PAYMENT")));transferRefunds.apply(event);transferRefunds.apply(event);assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM refunds WHERE payment_id=?",Integer.class,p.id())).isEqualTo(1);assertThat(repo.jdbc().queryForObject("SELECT state FROM transfer_escrow WHERE payment_id=?",String.class,p.id())).isEqualTo("REFUND_REVIEW");
 }
 @Test void createAndSignedCallbackAreIdempotent(){String key=key();var p=service.create(new CreatePayment(booking,null),key,user,"token");assertThat(service.create(new CreatePayment(booking,null),key,user,"token").id()).isEqualTo(p.id());var callback=callback(p,"SUCCESS");service.callback(callback,provider.sign(callback));service.callback(callback,provider.sign(callback));assertThat(repo.find(p.id()).status()).isEqualTo("SUCCESS");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM provider_callbacks WHERE payment_id=?",Integer.class,p.id())).isEqualTo(1);verify(outbox,times(1)).record(any(),any());}
 @Test void alternateCreateKeyKeepsItsResultAfterCallbackAndAdminCannotSimulate(){
  var input=new CreatePayment(booking,null);var p=service.create(input,key(),user,"token");String alias=key();
  assertThat(service.create(input,alias,user,"token").id()).isEqualTo(p.id());
  assertThatThrownBy(()->service.demo(p.id(),new DemoOutcome("SUCCESS"),new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of()))).isInstanceOf(ForbiddenException.class);
  var c=callback(p,"SUCCESS");service.callback(c,provider.sign(c));
  assertThat(service.create(input,alias,user,"token").id()).isEqualTo(p.id());
 }
 @Test void groupTimeoutRefundInboxKeepsOriginalPayerAndDoesNotExecuteUnapprovedPolicy(){
  UUID member=UUID.randomUUID();when(payable.payable(eq(booking),eq(member),any())).thenReturn(json.valueToTree(Map.of("bookingId",booking,"memberId",member,"payerId",user.id(),"amount",75000,"currency","VND","expiresAt",testNow.plusSeconds(600),"purpose","GROUP_CONTRIBUTION")));
  var p=service.create(new CreatePayment(booking,member),key(),user,"token");var c=callback(p,"SUCCESS");service.callback(c,provider.sign(c));
  var event=json.valueToTree(com.sporthub.common.event.DomainEvent.create("booking.group.refund.requested",1,"booking-service",booking,Map.of("bookingId",booking,"paymentId",p.id(),"payerId",user.id(),"reason","GROUP_TIMEOUT")));
  refunds.apply(event);refunds.apply(event);var rows=repo.jdbc().queryForList("SELECT * FROM refunds WHERE payment_id=?",p.id());assertThat(rows).hasSize(1);assertThat(rows.get(0).get("requester_id")).isEqualTo(user.id());assertThat(rows.get(0).get("state")).isEqualTo("BLOCKED_RULE");assertThat(rows.get(0).get("amount")).isNull();
 }
 @Test void invalidSignatureAmountAndForeignPayerAreRejected(){var p=service.create(new CreatePayment(booking,null),key(),user,"token");var c=callback(p,"SUCCESS");assertThatThrownBy(()->service.callback(c,"forged")).isInstanceOf(ForbiddenException.class);var wrong=new Callback(p.id(),p.providerReference(),c.transactionId(),new BigDecimal("1"),"VND","SUCCESS",c.timestamp());assertThatThrownBy(()->service.callback(wrong,provider.sign(wrong))).isInstanceOf(ForbiddenException.class);assertThat(repo.find(p.id()).status()).isEqualTo("PENDING");assertThatThrownBy(()->service.own(p.id(),new Caller(UUID.randomUUID(),"Other",Set.of("CUSTOMER"),Map.of()))).isInstanceOf(ForbiddenException.class);}
 @Test void failedResultIsFinalAndRefundPolicyRemainsIsolated(){var p=service.create(new CreatePayment(booking,null),key(),user,"token");var c=callback(p,"FAILED");service.callback(c,provider.sign(c));assertThat(repo.find(p.id()).status()).isEqualTo("FAILED");var changed=callback(p,"SUCCESS");assertThatThrownBy(()->service.callback(changed,provider.sign(changed))).isInstanceOf(ConflictException.class);
  booking=UUID.randomUUID();when(payable.payable(eq(booking),isNull(),any())).thenReturn(json.valueToTree(Map.of("bookingId",booking,"payerId",user.id(),"amount",150000,"currency","VND","expiresAt",testNow.plusSeconds(600),"purpose","BOOKING")));var paid=service.create(new CreatePayment(booking,null),key(),user,"token");var success=callback(paid,"SUCCESS");service.callback(success,provider.sign(success));
 }

 @Test void otherPayersCannotReadOrSimulateAndOnlyAdminCanMonitorPayments() {
  var payment=service.create(new CreatePayment(booking,null),key(),user,"token");
  var outsider=new Caller(UUID.randomUUID(),"Outsider",Set.of("CUSTOMER"),Map.of());
  assertThatThrownBy(()->service.own(payment.id(),outsider)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.demo(payment.id(),new DemoOutcome("SUCCESS"),outsider)).isInstanceOf(ForbiddenException.class);
  for(String role:List.of("CUSTOMER","OWNER","STAFF")) {
   when(identity.current(any())).thenReturn(new Caller(UUID.randomUUID(),role,Set.of(role),Map.of()));
   assertThatThrownBy(()->monitoring.detail(payment.id(),new org.springframework.mock.web.MockHttpServletRequest())).isInstanceOf(ForbiddenException.class);
  }
  assertThat(repo.find(payment.id()).status()).isEqualTo("PENDING");
 }



 com.fasterxml.jackson.databind.JsonNode bill(UUID id,UUID payer,UUID member,Instant expires,BigDecimal amount){var map=new HashMap<String,Object>();map.put("bookingId",id);map.put("payerId",payer);map.put("memberId",member);map.put("amount",amount);map.put("currency","VND");map.put("expiresAt",expires);map.put("purpose",member==null?"BOOKING":"GROUP_CONTRIBUTION");return json.valueToTree(map);}
 Payment pending(){return service.create(new CreatePayment(booking,null),key(),user,"token");}
 void paid(Payment p){var c=callback(p,"SUCCESS");service.callback(c,provider.sign(c));}
 <T> List<T> parallel(int count,java.util.concurrent.Callable<T> task)throws Exception{
  var ready=new java.util.concurrent.CountDownLatch(count);var go=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(count);
  try{var jobs=new ArrayList<java.util.concurrent.Future<T>>();for(int i=0;i<count;i++)jobs.add(pool.submit(()->{ready.countDown();go.await();return task.call();}));assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();go.countDown();var results=new ArrayList<T>();for(var job:jobs)results.add(job.get(15,java.util.concurrent.TimeUnit.SECONDS));return results;}finally{go.countDown();pool.shutdownNow();}
 }
 String event(UUID payment){return repo.jdbc().queryForObject("SELECT body::text FROM event_outbox WHERE body->'payload'->>'paymentId'=?",String.class,payment.toString());}
 com.fasterxml.jackson.databind.JsonNode proof(Payment p,String decision,String reason,String status)throws Exception{
  var payload=json.readTree(event(p.id())).path("payload");String fingerprint=com.sporthub.common.security.Signatures.sha256(p.bookingId()+"|"+p.payerId()+"|"+payload.path("memberId").asText()+"|"+p.purpose()+"|"+p.status()+"|"+p.amount().stripTrailingZeros().toPlainString()+"|"+p.currency()+"|"+Instant.parse(payload.path("paidAt").asText()));
  var result=json.createObjectNode().put("paymentId",p.id().toString()).put("bookingId",p.bookingId().toString()).put("fingerprint",fingerprint).put("decision",decision).put("bookingStatus",status);if(reason!=null)result.put("reason",reason);return result;
 }
 @Test void newKeysAfterSuccessReuseSettledOrderEvenBeforeBookingConsumesEvent(){
  var p=pending();paid(p);var replay=service.create(new CreatePayment(booking,null),key(),user,"token");assertThat(replay.id()).isEqualTo(p.id());assertThat(replay.status()).isEqualTo("SUCCESS");
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_orders WHERE booking_id=?",Integer.class,booking)).isEqualTo(1);
  var other=new Caller(UUID.randomUUID(),"Other",Set.of("CUSTOMER"),Map.of());assertThatThrownBy(()->service.create(new CreatePayment(booking,null),key(),other,"token")).isInstanceOf(ConflictException.class);
 }
 @Test void legacySuccessWithoutUpdatedClaimStillBlocksAnotherPaymentOrder(){
  var p=pending();repo.jdbc().update("UPDATE payment_orders SET status='SUCCESS',paid_at=? WHERE id=?",java.sql.Timestamp.from(testNow),p.id());
  assertThat(repo.jdbc().queryForObject("SELECT settled_payment_id FROM payment_obligations WHERE booking_id=?",UUID.class,booking)).isNull();
  var replay=service.create(new CreatePayment(booking,null),key(),user,"token");assertThat(replay.id()).isEqualTo(p.id());assertThat(replay.status()).isEqualTo("SUCCESS");
  assertThat(repo.jdbc().queryForObject("SELECT settled_payment_id FROM payment_obligations WHERE booking_id=?",UUID.class,booking)).isEqualTo(p.id());assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_orders WHERE booking_id=?",Integer.class,booking)).isEqualTo(1);
 }
 @Test void concurrentDifferentKeysReuseOnePendingOrderAndConcurrentCallbacksCommitOnce()throws Exception{
  var orders=parallel(4,()->service.create(new CreatePayment(booking,null),key(),user,"token"));assertThat(new HashSet<>(orders.stream().map(Payment::id).toList())).hasSize(1);var p=orders.getFirst();var c=callback(p,"SUCCESS");parallel(4,()->service.callback(c,provider.sign(c)));
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM provider_callbacks WHERE payment_id=?",Integer.class,p.id())).isEqualTo(1);assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_audit WHERE payment_id=? AND action='CALLBACK_VERIFIED'",Integer.class,p.id())).isEqualTo(1);assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM event_outbox WHERE body->'payload'->>'paymentId'=?",Integer.class,p.id().toString())).isEqualTo(1);
 }
 @Test void groupOwnerAndMemberCannotCreateTwoOrdersForOneContribution()throws Exception{
  UUID member=UUID.randomUUID();var other=new Caller(UUID.randomUUID(),"Member",Set.of("CUSTOMER"),Map.of());var deadline=testNow.plusSeconds(600);when(payable.payable(eq(booking),eq(member),any())).thenAnswer(inv->bill(booking,inv.getArgument(2).equals("other")?other.id():user.id(),member,deadline,new BigDecimal("75000")));
  var counter=new java.util.concurrent.atomic.AtomicInteger();var results=parallel(2,()->{boolean second=counter.getAndIncrement()==1;try{return service.create(new CreatePayment(booking,member),key(),second?other:user,second?"other":"token").id();}catch(ConflictException denied){return null;}});
  assertThat(results.stream().filter(Objects::nonNull)).hasSize(1);assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_orders WHERE booking_id=? AND member_id=?",Integer.class,booking,member)).isEqualTo(1);
 }
 @Test void transferEscrowRetainsVerifiedIdentityAcrossDuplicateCallbacksAndCannotBeRewritten(){
  UUID acquisition=UUID.randomUUID(),seller=UUID.randomUUID();when(payable.transfer(eq(acquisition),any())).thenReturn(json.valueToTree(Map.of("bookingId",booking,"payerId",user.id(),"sellerId",seller,"acquisitionId",acquisition,"amount",100000,"currency","VND","expiresAt",testNow.plusSeconds(600),"purpose","TRANSFER")));
  var p=service.create(new CreatePayment(booking,null,acquisition),key(),user,"token");var c=callback(p,"SUCCESS");service.callback(c,provider.sign(c));service.callback(c,provider.sign(c));
  assertThat(repo.jdbc().queryForObject("SELECT state FROM transfer_escrow WHERE payment_id=?",String.class,p.id())).isEqualTo("HELD_POLICY_BLOCKED");assertThat(repo.jdbc().queryForObject("SELECT amount FROM transfer_escrow WHERE payment_id=?",BigDecimal.class,p.id())).isEqualByComparingTo("100000");
  for(String sql:List.of("UPDATE transfer_escrow SET amount=1 WHERE payment_id=?","UPDATE transfer_escrow SET seller_id=gen_random_uuid() WHERE payment_id=?","DELETE FROM transfer_escrow WHERE payment_id=?"))assertThatThrownBy(()->repo.jdbc().update(sql,p.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
  assertThat(service.create(new CreatePayment(booking,null,acquisition),key(),user,"token").id()).isEqualTo(p.id());assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_escrow WHERE acquisition_id=?",Integer.class,acquisition)).isEqualTo(1);
 }
 @Test void escrowCannotExistForAnUnverifiedPendingPayment(){
  UUID acquisition=UUID.randomUUID(),seller=UUID.randomUUID();when(payable.transfer(eq(acquisition),any())).thenReturn(json.valueToTree(Map.of("bookingId",booking,"payerId",user.id(),"sellerId",seller,"acquisitionId",acquisition,"amount",100000,"currency","VND","expiresAt",testNow.plusSeconds(600),"purpose","TRANSFER")));var p=service.create(new CreatePayment(booking,null,acquisition),key(),user,"token");
  assertThatThrownBy(()->repo.jdbc().update("INSERT INTO transfer_escrow(payment_id,acquisition_id,payer_id,seller_id,amount,currency,state) VALUES(?,?,?,?,100000,'VND','HELD_POLICY_BLOCKED')",p.id(),acquisition,user.id(),seller)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("verified transfer payment");assertThat(repo.find(p.id()).status()).isEqualTo("PENDING");
 }
 @Test void failedTransferAttemptCanRetrySameAcquisitionWithoutLosingItsFailedReceipt(){
  UUID acquisition=UUID.randomUUID(),seller=UUID.randomUUID();when(payable.transfer(eq(acquisition),any())).thenReturn(json.valueToTree(Map.of("bookingId",booking,"payerId",user.id(),"sellerId",seller,"acquisitionId",acquisition,"amount",100000,"currency","VND","expiresAt",testNow.plusSeconds(600),"purpose","TRANSFER")));String requestKey=key();var first=service.create(new CreatePayment(booking,null,acquisition),requestKey,user,"token");var c=callback(first,"FAILED");service.callback(c,provider.sign(c));
  assertThat(service.create(new CreatePayment(booking,null,acquisition),requestKey,user,"token").id()).isEqualTo(first.id());var next=service.create(new CreatePayment(booking,null,acquisition),key(),user,"token");paid(next);assertThat(repo.find(first.id()).status()).isEqualTo("FAILED");assertThat(repo.find(next.id()).status()).isEqualTo("SUCCESS");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_escrow WHERE acquisition_id=?",Integer.class,acquisition)).isEqualTo(1);
 }
 @Test void fractionalGroupPayableCannotCreateAnOrderOrObligation(){
  UUID member=UUID.randomUUID();when(payable.payable(eq(booking),eq(member),any())).thenReturn(bill(booking,user.id(),member,testNow.plusSeconds(600),new BigDecimal("75000.25")));
  assertThatThrownBy(()->service.create(new CreatePayment(booking,member),key(),user,"token")).isInstanceOf(ConflictException.class).hasMessageContaining("integral VND");
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_orders WHERE booking_id=?",Integer.class,booking)).isZero();assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_obligations WHERE booking_id=?",Integer.class,booking)).isZero();
  assertThatThrownBy(()->repo.jdbc().update("INSERT INTO payment_obligations(booking_id,purpose,subject_id,amount,currency) VALUES(?,'GROUP_CONTRIBUTION',?,0.25,'VND')",booking,member)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("whole_vnd_group_obligation");
 }
 @Test void groupFailedAttemptCanBeCoveredByOwnerWithoutRewritingOriginalPayer(){
  UUID member=UUID.randomUUID();var owner=new Caller(UUID.randomUUID(),"Booking Owner",Set.of("CUSTOMER"),Map.of());var deadline=testNow.plusSeconds(600);
  when(payable.payable(eq(booking),eq(member),any())).thenAnswer(i->bill(booking,i.getArgument(2).equals("owner")?owner.id():user.id(),member,deadline,new BigDecimal("75000")));
  var first=service.create(new CreatePayment(booking,member),key(),user,"token");var failed=callback(first,"FAILED");service.callback(failed,provider.sign(failed));
  var covered=service.create(new CreatePayment(booking,member),key(),owner,"owner");paid(covered);var successful=repo.find(covered.id());
  assertThat(successful.payerId()).isEqualTo(owner.id());assertThat(repo.find(first.id()).payerId()).isEqualTo(user.id());assertThat(repo.find(first.id()).status()).isEqualTo("FAILED");
  assertThat(service.create(new CreatePayment(booking,member),key(),owner,"owner").id()).isEqualTo(covered.id());
  assertThatThrownBy(()->service.create(new CreatePayment(booking,member),key(),user,"token")).isInstanceOf(ConflictException.class);
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_orders WHERE booking_id=? AND status='SUCCESS'",Integer.class,booking)).isEqualTo(1);
 }
 @Test void groupRetryCannotRepriceAnExistingObligationOrReuseKeyForAnotherMember(){
  UUID member=UUID.randomUUID(),other=UUID.randomUUID();var deadline=testNow.plusSeconds(600);
  when(payable.payable(eq(booking),eq(member),any())).thenReturn(bill(booking,user.id(),member,deadline,new BigDecimal("75000")));
  String requestKey=key();var first=service.create(new CreatePayment(booking,member),requestKey,user,"token");var failed=callback(first,"FAILED");service.callback(failed,provider.sign(failed));
  assertThat(service.create(new CreatePayment(booking,member),requestKey,user,"token").id()).isEqualTo(first.id());
  assertThatThrownBy(()->service.create(new CreatePayment(booking,other),requestKey,user,"token")).isInstanceOf(ConflictException.class);
  when(payable.payable(eq(booking),eq(member),any())).thenReturn(bill(booking,user.id(),member,deadline,new BigDecimal("75001")));
  assertThatThrownBy(()->service.create(new CreatePayment(booking,member),key(),user,"token")).isInstanceOf(ConflictException.class);
  assertThat(repo.jdbc().queryForObject("SELECT amount FROM payment_obligations WHERE booking_id=?",BigDecimal.class,booking)).isEqualByComparingTo("75000");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_orders WHERE booking_id=?",Integer.class,booking)).isEqualTo(1);
 }
 @Test void groupDeadlinePreventsNewPaymentAttemptAfterExpiration(){
  UUID member=UUID.randomUUID();var deadline=testNow.plusSeconds(60);when(payable.payable(eq(booking),eq(member),any())).thenReturn(bill(booking,user.id(),member,deadline,new BigDecimal("75000")));
  var first=service.create(new CreatePayment(booking,member),key(),user,"token");when(clock.instant()).thenReturn(deadline);service.expire(first.id());
  assertThatThrownBy(()->service.create(new CreatePayment(booking,member),key(),user,"token")).isInstanceOf(ConflictException.class).hasMessageContaining("expired");assertThat(repo.find(first.id()).status()).isEqualTo("EXPIRED");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_orders WHERE booking_id=?",Integer.class,booking)).isEqualTo(1);
 }
 @Test void unknownOutcomeAndInvalidPrecisionDoNotCreateVerifiedFinancialEvidence(){
  var p=pending();var unknown=new Callback(p.id(),p.providerReference(),"UNKNOWN-"+p.id(),p.amount(),"VND","PROCESSING",testNow.getEpochSecond());assertThatThrownBy(()->service.callback(unknown,provider.sign(unknown))).isInstanceOf(IllegalArgumentException.class);
  var fraction=new Callback(p.id(),p.providerReference(),"PRECISION-"+p.id(),new BigDecimal("150000.001"),"VND","SUCCESS",testNow.getEpochSecond());assertThatThrownBy(()->service.callback(fraction,"signed")).isInstanceOf(IllegalArgumentException.class);
  assertThat(repo.find(p.id()).status()).isEqualTo("PENDING");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM provider_callbacks WHERE payment_id=?",Integer.class,p.id())).isZero();
 }
 @Test void successThenFailedWithNewTransactionKeepsFinalMoneyAndPersistsConflictReview(){
  var p=pending();paid(p);var original=repo.find(p.id());var next=new Callback(p.id(),p.providerReference(),"REORDER-"+p.id(),p.amount(),"VND","FAILED",testNow.getEpochSecond());assertThatThrownBy(()->service.callback(next,provider.sign(next))).isInstanceOf(ConflictException.class);assertThat(repo.find(p.id())).isEqualTo(original);
  assertThat(repo.jdbc().queryForObject("SELECT reason FROM payment_callback_reviews WHERE payment_id=?",String.class,p.id())).isEqualTo("FINAL_OUTCOME_CONFLICT");
 }
 @Test void failedThenSignedSuccessRequiresReviewAndBlocksAnotherCharge(){
  var p=pending();var failed=callback(p,"FAILED");service.callback(failed,provider.sign(failed));var success=callback(p,"SUCCESS");assertThatThrownBy(()->service.callback(success,provider.sign(success))).isInstanceOf(ConflictException.class);
  assertThat(repo.find(p.id()).status()).isEqualTo("FAILED");assertThatThrownBy(()->service.create(new CreatePayment(booking,null),key(),user,"token")).isInstanceOf(ConflictException.class).hasMessageContaining("reconciliation");
 }
 @Test void expiredOrderCanRecordLateVerifiedMoneyWithoutPretendingBookingConfirmed(){
  var p=pending();when(clock.instant()).thenReturn(p.expiresAt().plusSeconds(1));expiry.run();assertThat(repo.find(p.id()).status()).isEqualTo("EXPIRED");var c=callback(p,"SUCCESS");service.callback(c,provider.sign(c));
  assertThat(repo.find(p.id()).status()).isEqualTo("SUCCESS");assertThat(synchronization.own(p.id(),user).get("state")).isEqualTo("AWAITING_BOOKING");
 }
 @Test void callbackRacingExpiryPreservesSuccessOrRecordsLateSuccessExactlyOnce()throws Exception{
  var p=pending();when(clock.instant()).thenReturn(p.expiresAt());var c=callback(p,"SUCCESS");var n=new java.util.concurrent.atomic.AtomicInteger();parallel(2,()->{if(n.getAndIncrement()==0)return service.expire(p.id());return service.callback(c,provider.sign(c));});
  assertThat(repo.find(p.id()).status()).isEqualTo("SUCCESS");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM provider_callbacks WHERE payment_id=?",Integer.class,p.id())).isEqualTo(1);
 }
 @Test void providerTransactionIdCannotBeReusedAcrossOrders(){
  var first=pending();paid(first);UUID other=UUID.randomUUID();var otherBill=bill(other,user.id(),null,testNow.plusSeconds(600),new BigDecimal("150000"));when(payable.payable(eq(other),isNull(),any())).thenReturn(otherBill);var second=service.create(new CreatePayment(other,null),key(),user,"token");var c=new Callback(second.id(),second.providerReference(),"TX-"+first.id(),second.amount(),"VND","SUCCESS",testNow.getEpochSecond());
  assertThatThrownBy(()->service.callback(c,provider.sign(c))).isInstanceOf(ConflictException.class);assertThat(repo.find(second.id()).status()).isEqualTo("PENDING");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_callback_reviews WHERE payment_id=?",Integer.class,second.id())).isEqualTo(1);
 }
 @Test void callbackReplayWithFreshTimestampDoesNotChangeOriginalPaidAt(){
  var p=pending();var first=callback(p,"SUCCESS");service.callback(first,provider.sign(first));when(clock.instant()).thenReturn(testNow.plusSeconds(90));var retry=new Callback(p.id(),p.providerReference(),first.transactionId(),p.amount(),p.currency(),"SUCCESS",clock.instant().getEpochSecond());service.callback(retry,provider.sign(retry));assertThat(repo.find(p.id()).paidAt()).isEqualTo(Instant.ofEpochSecond(first.timestamp()));
 }
 @Test void finalFinancialFieldsAndEvidenceCannotBeMutatedBySql(){
  var p=pending();paid(p);UUID id=p.id();for(String sql:List.of("UPDATE payment_orders SET amount=1 WHERE id=?","UPDATE payment_orders SET status='PENDING' WHERE id=?","UPDATE payment_orders SET paid_at=NOW()+interval '1 hour' WHERE id=?","UPDATE provider_callbacks SET body='{}' WHERE payment_id=?","UPDATE payment_audit SET details='{}' WHERE payment_id=?","UPDATE payment_requests SET booking_id=gen_random_uuid() WHERE payment_id=?"))assertThatThrownBy(()->repo.jdbc().update(sql,id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThat(repo.find(id).amount()).isEqualByComparingTo("150000");
 }
 @Test void databaseRejectsASecondLivePendingObligationEvenWithAnotherPayer(){
  var p=pending();assertThatThrownBy(()->repo.jdbc().update("INSERT INTO payment_orders(id,booking_id,payer_id,purpose,amount,currency,provider,provider_reference,status,idempotency_key,expires_at) VALUES(?, ?,?,'BOOKING',150000,'VND','DEMO',?,'PENDING',?,?)",UUID.randomUUID(),booking,UUID.randomUUID(),"DEMO-"+key(),key(),java.sql.Timestamp.from(p.expiresAt()))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
 @Test void stalePayableAndDependencyFailureRollBackClaimAndRequest(){
  var stale=bill(booking,user.id(),null,testNow.minusSeconds(1),new BigDecimal("150000"));when(payable.payable(eq(booking),isNull(),any())).thenReturn(stale);assertThatThrownBy(()->pending()).isInstanceOf(ConflictException.class);
  when(payable.payable(eq(booking),isNull(),any())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));assertThatThrownBy(()->pending()).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_obligations WHERE booking_id=?",Integer.class,booking)).isZero();assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_requests WHERE booking_id=?",Integer.class,booking)).isZero();
 }
 @Test void outboxInsertFailureRollsBackCallbackStatusClaimAndAuditThenRetrySucceeds(){
  var p=pending();var c=callback(p,"SUCCESS");doThrow(new IllegalStateException("synthetic outbox outage")).when(outbox).record(any(),any());assertThatThrownBy(()->service.callback(c,provider.sign(c))).isInstanceOf(IllegalStateException.class);
  assertThat(repo.find(p.id()).status()).isEqualTo("PENDING");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM provider_callbacks WHERE payment_id=?",Integer.class,p.id())).isZero();assertThat(repo.jdbc().queryForObject("SELECT settled_payment_id FROM payment_obligations WHERE booking_id=?",UUID.class,booking)).isNull();
  doCallRealMethod().when(outbox).record(any(),any());service.callback(c,provider.sign(c));assertThat(repo.find(p.id()).status()).isEqualTo("SUCCESS");
 }
 @Test void receiptUnavailableRetriesWithBudgetWithoutChangingSuccessAndManualRetryUsesOriginalEvent(){
  var p=pending();paid(p);when(receipts.receipt(p.id())).thenThrow(new BookingReceiptClient.Unavailable());for(int i=0;i<10;i++){when(clock.instant()).thenReturn(testNow.plusSeconds(i*100));synchronization.process(p.id());}
  var state=synchronization.own(p.id(),user);assertThat(state.get("state")).isEqualTo("REQUIRES_REVIEW");assertThat(state.get("attempts")).isEqualTo(10);assertThat(repo.find(p.id()).status()).isEqualTo("SUCCESS");
  Object original=state.get("event_id");var retry=synchronization.retry(p.id(),user);assertThat(retry.get("state")).isEqualTo("AWAITING_BOOKING");assertThat(retry.get("event_id")).isEqualTo(original);
 }
 @Test void missingReceiptReplaysImmutableOriginalEventAndProcessedProofAcknowledges()throws Exception{
  var p=pending();paid(p);String original=event(p.id());when(receipts.receipt(p.id())).thenThrow(new BookingReceiptClient.Missing());synchronization.process(p.id());assertThat(synchronization.own(p.id(),user).get("state")).isEqualTo("AWAITING_BOOKING");assertThat(event(p.id())).isEqualTo(original);
  var paid=repo.find(p.id());var response=proof(paid,"APPLIED",null,"CONFIRMED");doReturn(response).when(receipts).receipt(p.id());when(clock.instant()).thenReturn(testNow.plusSeconds(5));synchronization.process(p.id());assertThat(synchronization.own(p.id(),user).get("state")).isEqualTo("ACKNOWLEDGED");
 }
 @Test void bookingCancelledOrExpiredReviewCannotBeResolvedByReplay()throws Exception{
  var p=pending();paid(p);var response=proof(repo.find(p.id()),"REQUIRES_REVIEW","BOOKING_NOT_PENDING","CANCELLED");doReturn(response).when(receipts).receipt(p.id());synchronization.process(p.id());assertThat(synchronization.own(p.id(),user).get("state")).isEqualTo("REQUIRES_REVIEW");assertThatThrownBy(()->synchronization.retry(p.id(),user)).isInstanceOf(ConflictException.class);assertThat(repo.find(p.id()).status()).isEqualTo("SUCCESS");
 }
 @Test void bindingOrFingerprintMismatchCannotAcknowledgeMoney()throws Exception{
  var p=pending();paid(p);var response=(com.fasterxml.jackson.databind.node.ObjectNode)proof(repo.find(p.id()),"APPLIED",null,"CONFIRMED");response.put("fingerprint","forged");doReturn(response).when(receipts).receipt(p.id());synchronization.process(p.id());assertThat(synchronization.own(p.id(),user).get("last_error_code")).isEqualTo("BOOKING_RECEIPT_FINGERPRINT_MISMATCH");
 }
 @Test void failedPaymentSynchronizationAndNewAttemptDoNotShareReceiptOrChargeTwice()throws Exception{
  var p=pending();var c=callback(p,"FAILED");service.callback(c,provider.sign(c));var response=proof(repo.find(p.id()),"FAILED",null,"PENDING");doReturn(response).when(receipts).receipt(p.id());synchronization.process(p.id());assertThat(synchronization.own(p.id(),user).get("state")).isEqualTo("ACKNOWLEDGED");var next=pending();assertThat(next.id()).isNotEqualTo(p.id());assertThat(repo.find(p.id()).status()).isEqualTo("FAILED");
 }
 @Test void syncStateAndRetryAreScopedAndMissingKeysOrSignaturesFailWithoutMoneyWrites()throws Exception{
  var p=pending();paid(p);var outsider=new Caller(UUID.randomUUID(),"Other",Set.of("CUSTOMER"),Map.of());assertThatThrownBy(()->synchronization.own(p.id(),outsider)).isInstanceOf(ForbiddenException.class);assertThatThrownBy(()->synchronization.retry(p.id(),outsider)).isInstanceOf(ForbiddenException.class);
  when(identity.current(any())).thenReturn(user);http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/payments").contentType("application/json").content(json.writeValueAsString(new CreatePayment(booking,null))).header("Authorization","Bearer synthetic")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
  var fresh=callback(p,"SUCCESS");http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/payments/providers/demo/callback").contentType("application/json").content(json.writeValueAsString(fresh))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
 }

 @Autowired PaymentReportingService reports;
 private void reportClock(String instant){testNow=Instant.parse(instant);when(clock.instant()).thenReturn(testNow);when(payable.payable(eq(booking),isNull(),any())).thenReturn(json.valueToTree(Map.of("bookingId",booking,"payerId",user.id(),"amount",150000,"currency","VND","expiresAt",testNow.plusSeconds(600),"purpose","BOOKING")));}
 @Test void collectionReportUsesSuccessPaidAtHalfOpenWindowAndDemoLabel(){
  reportClock("2041-01-01T17:00:00Z");
  var payment=service.create(new CreatePayment(booking,null),key(),user,"token");var c=callback(payment,"SUCCESS");service.callback(c,provider.sign(c));service.callback(c,provider.sign(c));
  var admin=new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of());Instant paid=repo.find(payment.id()).paidAt();
  var report=reports.collections(paid,paid.plusSeconds(1),"Asia/Ho_Chi_Minh",admin);assertThat((List<?>)report.get("collections")).hasSize(1);var rows=(List<Map<String,Object>>)report.get("collections");assertThat(rows.getFirst()).containsEntry("provider","DEMO").containsEntry("purpose","BOOKING").containsEntry("payment_count",1L);assertThat((java.math.BigDecimal)rows.getFirst().get("gross_amount")).isEqualByComparingTo("150000");assertThat(rows.getFirst().get("local_date").toString()).isEqualTo(paid.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().toString());
  assertThat(report).containsEntry("settledRevenue",null).containsEntry("payout",null);assertThat(report.get("demoPolicy").toString()).contains("simulated");assertThat((List<?>)reports.collections(paid.minusSeconds(1),paid,"UTC",admin).get("collections")).isEmpty();
  assertThatThrownBy(()->reports.collections(null,null,"UTC",user)).isInstanceOf(ForbiddenException.class);
 }
 @Test void failedAndPendingPaymentsAreNotSuccessfulCollections(){
  reportClock("2042-01-01T17:00:00Z");
  var failed=service.create(new CreatePayment(booking,null),key(),user,"token");var callback=callback(failed,"FAILED");service.callback(callback,provider.sign(callback));var pending=service.create(new CreatePayment(booking,null),key(),user,"token");assertThat(pending.status()).isEqualTo("PENDING");var admin=new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of());assertThat((List<?>)reports.collections(testNow.minusSeconds(60),testNow.plusSeconds(60),"UTC",admin).get("collections")).isEmpty();
 }


 @Test void refundInsertCannotReferenceWrongBeneficiaryOrUnsuccessfulPayment(){var p=pending();assertThatThrownBy(()->repo.jdbc().update("INSERT INTO refunds(id,payment_id,requester_id,reason,state,idempotency_key,beneficiary_id) VALUES(?,?,?,'Review','BLOCKED_RULE',?,?)",UUID.randomUUID(),p.id(),user.id(),key(),user.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);paid(p);assertThatThrownBy(()->repo.jdbc().update("INSERT INTO refunds(id,payment_id,requester_id,reason,state,idempotency_key,beneficiary_id) VALUES(?,?,?,'Review','BLOCKED_RULE',?,?)",UUID.randomUUID(),p.id(),user.id(),key(),UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataAccessException.class);}
 @Test void malformedRefundEventsAreRejectedWithoutInboxOrFinancialMutation(){var bad=json.createObjectNode();bad.put("producer","transfer-service").put("eventType","transfer.refund.review.requested").put("eventVersion",1).put("eventId",UUID.randomUUID().toString());bad.putObject("payload").put("paymentId","invalid").put("bookingId",booking.toString()).put("payerId",user.id().toString()).put("reason","Original transaction requires review");assertThatThrownBy(()->transferRefunds.apply(bad)).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);var missing=json.createObjectNode();missing.put("producer","booking-service").put("eventType","booking.group.refund.requested").put("eventVersion",1);missing.putObject("payload").put("paymentId",UUID.randomUUID().toString());assertThatThrownBy(()->refunds.apply(missing)).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);}

}
