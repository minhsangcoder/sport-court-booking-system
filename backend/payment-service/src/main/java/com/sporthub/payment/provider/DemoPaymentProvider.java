package com.sporthub.payment.provider;

import static com.sporthub.payment.web.PaymentDtos.*;
import com.sporthub.common.security.Signatures;
import com.sporthub.common.exception.ForbiddenException;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Local adapter. Simulated provider sends the same signed HTTP callback as a real provider would. */
@Component @Profile({"local","demo"})
public class DemoPaymentProvider implements PaymentProvider {
 private final String secret;private final RestClient self;
 public DemoPaymentProvider(@Value("${sporthub.payment.demo.callback-secret}") String secret,@Value("${PAYMENT_SERVICE_URL:http://localhost:8085}") String url){
  if(secret.length()<32)throw new IllegalArgumentException("Demo callback secret must contain at least 32 characters");this.secret=secret;self=RestClient.create(url);
 }
 public String name(){return "DEMO";}
 public String create(UUID payment,BigDecimal amount,String currency){return "DEMO-"+payment;}
 public String sign(Callback c){return Signatures.hmac(secret,c.paymentId()+"|"+c.providerReference()+"|"+c.transactionId()+"|"+c.amount().setScale(2).toPlainString()+"|"+c.currency()+"|"+c.status()+"|"+c.timestamp());}
 public void verify(Callback c,String signature){if(Math.abs(Instant.now().getEpochSecond()-c.timestamp())>300||!Signatures.matches(sign(c),signature))throw new ForbiddenException("Callback signature or timestamp is invalid");}
 public void simulate(Payment payment,String outcome){var callback=new Callback(payment.id(),payment.providerReference(),"TX-"+payment.id(),payment.amount(),payment.currency(),outcome,Instant.now().getEpochSecond());self.post().uri("/api/v1/payments/providers/demo/callback").header("X-Provider-Signature",sign(callback)).body(callback).retrieve().toBodilessEntity();}
 public String refund(String transaction,BigDecimal amount,String idempotencyKey){return "REFUND-"+Signatures.sha256(transaction+"|"+idempotencyKey);}
}
