package com.sporthub.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.sporthub.common.event.ReliableOutbox;
import com.sporthub.common.money.PaymentProofFacts;
import com.sporthub.common.exception.*;
import com.sporthub.payment.service.*;
import com.sporthub.payment.web.PaymentDtos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class CounterCashFlowTest extends SepayCollectionFlowTest {
  CounterCashPayments cash() {
    var service=mock(PaymentService.class);
    when(service.own(any(),any())).thenAnswer(call -> {
      var p=f.repo.find(call.getArgument(0));
      com.sporthub.common.security.RemoteIdentity.Caller caller=call.getArgument(1);
      if(!p.payerId().equals(caller.id())) throw new ForbiddenException("Wrong payer");
      return p;
    });
    var manager=new DataSourceTransactionManager(f.repo.jdbc().getDataSource());
    var qr=new PaymentQrSessions(f.repo,f.channels,service,f.clock,mock(PaymentBankAccess.class));
    return new CounterCashPayments(f.repo,qr,f.payable,new PaymentObligations(f.repo),
        new ReliableOutbox(f.repo.jdbc(),f.json,mock(RabbitTemplate.class),manager),f.json,f.clock,manager);
  }
  Payment counter() {
    when(f.payable.payable(eq(f.booking),isNull(),any())).thenAnswer(call -> f.bill()
        .put("receiverRevisionId",channel.receiverRevisionId().toString())
        .put("payerId",f.owner.id().toString()).put("source","OFFLINE_COUNTER").put("operatorPayment",true));
    return orders.create(new CreatePayment(f.booking,null),"counter",f.owner,"token");
  }
  @Test void cashIsAuditedIdempotentAndLateBankTransferIsUnapplied() {
    var old=counter(); var service=cash(); var paid=service.collect(old.id(),f.owner,"token");
    assertThat(paid.provider()).isEqualTo("CASH");
    assertThat(paid.status()).isEqualTo("SUCCESS");
    assertThat(service.collect(old.id(),f.owner,"token").id()).isEqualTo(paid.id());
    var facts=new PaymentReportFacts(f.repo,f.json).read(new PaymentProofFacts.Request(List.of(new PaymentProofFacts.Binding(paid.id(),f.booking,null,"BOOKING"))));
    assertThat(facts.facts().getFirst().verified()).isTrue();
    assertThat(facts.facts().getFirst().realEvidence().bankCredits()).isEmpty();
    receive(webhook(old,991,old.amount().longValueExact()));
    assertThat(f.repo.jdbc().queryForObject("SELECT sum(amount) FROM real_unapplied_funds WHERE payment_id=?",java.math.BigDecimal.class,old.id())).isEqualByComparingTo(old.amount());
    assertThat(f.repo.jdbc().queryForObject("SELECT settled_payment_id FROM payment_obligations WHERE booking_id=?",UUID.class,f.booking)).isEqualTo(paid.id());
  }
  @Test void partialTransferBlocksCash() {
    var p=counter(); receive(webhook(p,992,1000));
    assertThatThrownBy(() -> cash().collect(p.id(),f.owner,"token")).isInstanceOf(ConflictException.class);
    assertThat(f.repo.jdbc().queryForObject("SELECT count(*) FROM counter_cash_receipts",Integer.class)).isZero();
  }
  @Test void wrongPayerAndExpiredPaymentCannotCollectCash() {
    var p=counter();
    assertThatThrownBy(() -> cash().collect(p.id(),f.customer,"token")).isInstanceOf(ForbiddenException.class);
    when(f.clock.instant()).thenReturn(p.expiresAt());
    assertThatThrownBy(() -> cash().collect(p.id(),f.owner,"token")).isInstanceOf(ConflictException.class);
  }
  @Test void onlinePaymentCannotBecomeCash() {
    var p=create();
    assertThatThrownBy(() -> cash().collect(p.id(),f.customer,"token")).isInstanceOf(ForbiddenException.class);
  }
}
