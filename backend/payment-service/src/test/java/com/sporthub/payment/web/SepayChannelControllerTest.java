package com.sporthub.payment.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.payment.service.SepayChannels;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SepayChannelControllerTest {
  final SepayChannels channels = mock(SepayChannels.class);
  final RemoteIdentity identity = mock(RemoteIdentity.class);
  final UUID facility = UUID.randomUUID();
  final Caller owner = new Caller(UUID.randomUUID(), "Owner", Set.of("OWNER"), Map.of());
  MockMvc http;

  @BeforeEach
  void setup() {
    when(identity.current(any())).thenReturn(owner);
    http =
        MockMvcBuilders.standaloneSetup(new SepayChannelController(channels, identity))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void firstConfigurationReturnsNoContentInsteadOfAnInvalidNullEnvelope() throws Exception {
    http.perform(get(path()).header("Authorization", "Bearer owner"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    verify(channels).own(facility, owner, "Bearer owner");
  }

  @Test
  void configuredChannelUsesTheNormalMaskedDataEnvelope() throws Exception {
    var channel =
        new SepayChannels.Channel(
            UUID.randomUUID(), facility, UUID.randomUUID(), 1, "READY", "SEPAY", "970436",
            "******6789", "FACILITY OWNER", "https://sporthub.example/webhook");
    when(channels.own(facility, owner, "Bearer owner")).thenReturn(channel);
    http.perform(get(path()).header("Authorization", "Bearer owner"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("READY"))
        .andExpect(jsonPath("$.data.maskedAccountNumber").value("******6789"))
        .andExpect(jsonPath("$.data.accountNumber").doesNotExist())
        .andExpect(jsonPath("$.data.webhookApiKey").doesNotExist());
  }

  @Test
  void emptyStateStillRequiresAuthenticationAndFacilityOwnership() throws Exception {
    when(channels.own(facility, owner, "Bearer owner"))
        .thenThrow(new ForbiddenException("Không thuộc cơ sở của bạn."));
    http.perform(get(path()).header("Authorization", "Bearer owner"))
        .andExpect(status().isForbidden());
    when(identity.current(any())).thenThrow(new UnauthorizedException("Cần đăng nhập."));
    http.perform(get(path())).andExpect(status().isUnauthorized());
  }

  String path() {
    return "/api/v1/payments/facilities/" + facility + "/sepay-channel";
  }
}
