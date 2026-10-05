package com.sporthub.common.security;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.sporthub.common.exception.ForbiddenException;
import java.time.Instant;
class ServiceCallsTest {
 @Test void privateSignatureBindsMethodPathTimeAndExactBody(){String secret="test-private-key-longer-than-thirty-two-characters",path="/api/v1/internal/bookings/transfer/complete",body="{\"bookingId\":\"123\"}";long now=Instant.now().getEpochSecond();String signature=ServiceCalls.sign(secret,"POST",path,now,body);assertThatCode(()->ServiceCalls.verify(secret,"POST",path,""+now,body,signature)).doesNotThrowAnyException();assertThatThrownBy(()->ServiceCalls.verify(secret,"POST",path,""+now,body+" ",signature)).isInstanceOf(ForbiddenException.class);assertThatThrownBy(()->ServiceCalls.verify(secret,"GET",path,""+now,body,signature)).isInstanceOf(ForbiddenException.class);assertThatThrownBy(()->ServiceCalls.verify(secret,"POST",path,""+(now-301),body,ServiceCalls.sign(secret,"POST",path,now-301,body))).isInstanceOf(ForbiddenException.class);}
}
