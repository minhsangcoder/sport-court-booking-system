package com.sporthub.common.money;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;

class StrictPeerInputsTest {
  @Test
  void moneyActorAndBackdatedAttendanceFieldsAreRejectedEvenIfTheApplicationIgnoresOtherUnknownJsonFields() throws Exception {
    var json=new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    assertThatThrownBy(()->json.readValue("{\"version\":1,\"termsHash\":\""+"a".repeat(64)+"\",\"holderId\":\"buyer\"}",PeerResaleContract.Command.class))
        .isInstanceOf(JsonMappingException.class);
  }
}
