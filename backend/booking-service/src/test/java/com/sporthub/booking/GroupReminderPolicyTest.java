package com.sporthub.booking;

import com.sporthub.booking.service.GroupReminderPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

class GroupReminderPolicyTest {
    @Test void propertyOverrideChangesCooldownAndRemainingAmountIsUsed(){
        Instant last=Instant.parse("2026-10-06T00:00:00Z"),now=last.plusSeconds(60);UUID owner=UUID.randomUUID(),member=UUID.randomUUID();
        for(String value:new String[]{"PT30S","PT2M"})new ApplicationContextRunner().withUserConfiguration(GroupReminderPolicy.class).withPropertyValues("booking.group.payment-reminder.min-interval="+value).run(c->{
            var policy=c.getBean(GroupReminderPolicy.class);assertThat(policy.nextAllowedAt(last)).isEqualTo(last.plus(Duration.parse(value)));
            assertThat(policy.blockedReason(owner,member,true,true,new BigDecimal("100"),new BigDecimal("40"),last,now)).isEqualTo(value.equals("PT30S")?null:"COOLDOWN");
        });
    }
    @Test void invalidFrequencyFailsAtStartup(){for(String value:new String[]{"PT0S","-PT1S","invalid"})new ApplicationContextRunner().withUserConfiguration(GroupReminderPolicy.class).withPropertyValues("booking.group.payment-reminder.min-interval="+value).run(c->assertThat(c).hasFailed());}
}
