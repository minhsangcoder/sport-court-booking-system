package com.sporthub.payment;

import static org.assertj.core.api.Assertions.*;

import com.sporthub.common.money.CollectionRuntime;
import com.sporthub.payment.provider.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class RealModeGuardsTest {
  @Test
  void productionCombinedWithDemoNeverRegistersSimulationProvider() {
    for (String profile : new String[] {"prod", "production", "live"}) {
      try (var context = new AnnotationConfigApplicationContext()) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profile, "demo", "local");
        context.setEnvironment(environment);
        context.register(DemoPaymentProvider.class);
        context.refresh();
        assertThat(context.getBeansOfType(DemoPaymentProvider.class)).isEmpty();
        assertThatThrownBy(() -> new CollectionRuntime("DEMO", environment).requireDemo())
            .isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
      }
    }
  }

  @Test
  void realModeCannotBeDowngraded() {
    var environment = new MockEnvironment();
    environment.setActiveProfiles("local");
    assertThatThrownBy(() -> new CollectionRuntime("REAL", environment).requireDemo())
        .isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
  }
}
