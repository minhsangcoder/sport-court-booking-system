package com.sporthub.payment.provider;
import static com.sporthub.payment.web.PaymentDtos.*;
import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentProvider {
 String name();
 String create(UUID paymentId,BigDecimal amount,String currency);
 void verify(Callback callback,String signature);
 void simulate(Payment payment,String outcome);
 String refund(String transactionId,BigDecimal amount,String idempotencyKey);
}
