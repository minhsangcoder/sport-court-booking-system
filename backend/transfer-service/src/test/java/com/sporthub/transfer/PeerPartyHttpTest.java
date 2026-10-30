package com.sporthub.transfer;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.exception.GlobalExceptionHandler;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.transfer.service.PeerPartyCommands;
import com.sporthub.transfer.web.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PeerPartyHttpTest {
  @Test
  void spoofedMoneyHolderFieldsAndMissingProofAreRejectedWithProductionMapperSettings()
      throws Exception {
    var commands = mock(PeerPartyCommands.class);
    var identity = mock(RemoteIdentity.class);
    var buyer = new Caller(UUID.randomUUID(), "B", Set.of("CUSTOMER"), Map.of());
    when(identity.current(any())).thenReturn(buyer);
    var json =
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    var http =
        MockMvcBuilders.standaloneSetup(
                new PeerPartyController(
                    commands,
                    identity,
                    mock(com.sporthub.transfer.service.PeerBookingClient.class),
                    mock(com.sporthub.transfer.service.PeerPartyAccess.class)))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new TransferPrivacyFilter())
            .build();
    UUID id = UUID.randomUUID();
    String hash = "a".repeat(64);
    String path = "/api/v1/transfers/acquisitions/" + id;
    for (String field : List.of("holderId", "buyerId", "amount", "confirmationMethod")) {
      String body = "{\"version\":0,\"termsHash\":\"" + hash + "\",\"" + field + "\":\"forged\"}";
      http.perform(
              post(path + "/confirm-receipt")
                  .contentType("application/json")
                  .content(body)
                  .header("Idempotency-Key", "key"))
          .andExpect(status().isBadRequest());
    }
    http.perform(
            post(path + "/declare-payment")
                .contentType("application/json")
                .content(
                    "{\"version\":0,\"termsHash\":\""
                        + hash
                        + "\",\"bankTransactionReference\":\"\",\"evidenceIds\":[]}")
                .header("Idempotency-Key", "key"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(commands);
    when(commands.confirm(eq(id), any(), eq("key"), eq(buyer)))
        .thenReturn(json.createObjectNode().put("phase", "COMPLETED"));
    http.perform(
            post(path + "/confirm-receipt")
                .contentType("application/json")
                .content("{\"version\":1,\"termsHash\":\"" + hash + "\"}")
                .header("Idempotency-Key", "key")
                .header("X-User-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    verify(commands).confirm(eq(id), any(), eq("key"), eq(buyer));
  }
}
