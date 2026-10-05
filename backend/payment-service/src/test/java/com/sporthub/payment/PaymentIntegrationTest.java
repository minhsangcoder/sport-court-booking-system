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
import java.util.*;

@SpringBootTest(properties={"spring.profiles.active=local","DEMO_SEED_ENABLED=false","spring.rabbitmq.username=test","spring.rabbitmq.password=test",
 "spring.rabbitmq.listener.simple.auto-startup=false",
 "sporthub.payment.demo.callback-secret=local-callback-key-longer-than-thirty-two-characters"})
class PaymentIntegrationTest {
 static PostgreSQLContainer<?> postgres;
 @DynamicPropertySource static void db(DynamicPropertyRegistry p){String url=System.getenv("SPORTHUB_PAYMENT_TEST_DB_URL");if(url!=null){p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv("SPORTHUB_TEST_DB_USER"));p.add("spring.datasource.password",()->System.getenv("SPORTHUB_TEST_DB_PASSWORD"));}else{postgres=new PostgreSQLContainer<>("postgres:16-alpine");postgres.start();p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}}
 @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
 @Autowired PaymentService service;@Autowired PaymentRepository repo;@Autowired DemoPaymentProvider provider;@Autowired ObjectMapper json;
 @Autowired GroupRefundConsumer refunds;
 @MockBean PayableClient payable;@MockBean ReliableOutbox outbox;
 Caller user;UUID booking;
 @BeforeEach void setup(){user=new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of());booking=UUID.randomUUID();when(payable.payable(eq(booking),isNull(),any())).thenReturn(json.valueToTree(Map.of("bookingId",booking,"payerId",user.id(),"amount",150000,"currency","VND","expiresAt",Instant.now().plusSeconds(600),"purpose","BOOKING")));}
 String key(){return UUID.randomUUID().toString();}
 Callback callback(Payment p,String status){return new Callback(p.id(),p.providerReference(),"TX-"+p.id(),p.amount(),p.currency(),status,Instant.now().getEpochSecond());}
 @Test void createAndSignedCallbackAreIdempotent(){String key=key();var p=service.create(new CreatePayment(booking,null),key,user,"token");assertThat(service.create(new CreatePayment(booking,null),key,user,"token").id()).isEqualTo(p.id());var callback=callback(p,"SUCCESS");service.callback(callback,provider.sign(callback));service.callback(callback,provider.sign(callback));assertThat(repo.find(p.id()).status()).isEqualTo("SUCCESS");assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM provider_callbacks WHERE payment_id=?",Integer.class,p.id())).isEqualTo(1);verify(outbox,times(1)).record(any(),any());}
 @Test void alternateCreateKeyKeepsItsResultAfterCallbackAndAdminCannotSimulate(){
  var input=new CreatePayment(booking,null);var p=service.create(input,key(),user,"token");String alias=key();
  assertThat(service.create(input,alias,user,"token").id()).isEqualTo(p.id());
  assertThatThrownBy(()->service.demo(p.id(),new DemoOutcome("SUCCESS"),new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of()))).isInstanceOf(ForbiddenException.class);
  var c=callback(p,"SUCCESS");service.callback(c,provider.sign(c));
  assertThat(service.create(input,alias,user,"token").id()).isEqualTo(p.id());
 }
 @Test void groupTimeoutRefundInboxKeepsOriginalPayerAndDoesNotExecuteUnapprovedPolicy(){
  UUID member=UUID.randomUUID();when(payable.payable(eq(booking),eq(member),any())).thenReturn(json.valueToTree(Map.of("bookingId",booking,"memberId",member,"payerId",user.id(),"amount",75000,"currency","VND","expiresAt",Instant.now().plusSeconds(600),"purpose","GROUP_CONTRIBUTION")));
  var p=service.create(new CreatePayment(booking,member),key(),user,"token");var c=callback(p,"SUCCESS");service.callback(c,provider.sign(c));
  var event=json.valueToTree(com.sporthub.common.event.DomainEvent.create("booking.group.refund.requested",1,"booking-service",booking,Map.of("bookingId",booking,"paymentId",p.id(),"payerId",user.id(),"reason","GROUP_TIMEOUT")));
  refunds.apply(event);refunds.apply(event);var rows=repo.jdbc().queryForList("SELECT * FROM refunds WHERE payment_id=?",p.id());assertThat(rows).hasSize(1);assertThat(rows.get(0).get("requester_id")).isEqualTo(user.id());assertThat(rows.get(0).get("state")).isEqualTo("BLOCKED_RULE");assertThat(rows.get(0).get("amount")).isNull();
 }
 @Test void invalidSignatureAmountAndForeignPayerAreRejected(){var p=service.create(new CreatePayment(booking,null),key(),user,"token");var c=callback(p,"SUCCESS");assertThatThrownBy(()->service.callback(c,"forged")).isInstanceOf(ForbiddenException.class);var wrong=new Callback(p.id(),p.providerReference(),c.transactionId(),new BigDecimal("1"),"VND","SUCCESS",c.timestamp());assertThatThrownBy(()->service.callback(wrong,provider.sign(wrong))).isInstanceOf(ForbiddenException.class);assertThat(repo.find(p.id()).status()).isEqualTo("PENDING");assertThatThrownBy(()->service.own(p.id(),new Caller(UUID.randomUUID(),"Other",Set.of("CUSTOMER"),Map.of()))).isInstanceOf(ForbiddenException.class);}
 @Test void failedResultIsFinalAndRefundPolicyRemainsIsolated(){var p=service.create(new CreatePayment(booking,null),key(),user,"token");var c=callback(p,"FAILED");service.callback(c,provider.sign(c));assertThat(repo.find(p.id()).status()).isEqualTo("FAILED");var changed=callback(p,"SUCCESS");assertThatThrownBy(()->service.callback(changed,provider.sign(changed))).isInstanceOf(ConflictException.class);
  var paid=service.create(new CreatePayment(booking,null),key(),user,"token");var success=callback(paid,"SUCCESS");service.callback(success,provider.sign(success));var refund=service.requestRefund(paid.id(),new RefundInput("Request review"),key(),user);assertThat(refund.state()).isEqualTo("BLOCKED_RULE");assertThat(refund.amount()).isNull();
 }
}
