package com.sporthub.payment.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.payment.service.FacilityBookingsClient;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PaymentMonitoringControllerTest {
  PaymentRepository repo = mock(PaymentRepository.class);
  RemoteIdentity identity = mock(RemoteIdentity.class);
  FacilityBookingsClient bookings = mock(FacilityBookingsClient.class);
  MockMvc http;

  @BeforeEach
  void setUp() {
    when(identity.current(any()))
        .thenReturn(new Caller(UUID.randomUUID(), "A", Set.of("ADMIN"), Map.of()));
    when(repo.list(anyString(), any(Object[].class))).thenReturn(List.of());
    http =
        MockMvcBuilders.standaloneSetup(new PaymentMonitoringController(repo, identity, bookings))
            .build();
  }

  @Test
  void facilityScopeFiltersOnThatFacilitysBookings() throws Exception {
    UUID facility = UUID.randomUUID(), b1 = UUID.randomUUID(), b2 = UUID.randomUUID();
    when(bookings.bookingIds(facility, "Bearer admin")).thenReturn(List.of(b1, b2));
    http.perform(
            get("/api/v1/admin/payments")
                .param("facilityId", facility.toString())
                .header("Authorization", "Bearer admin"))
        .andExpect(status().isOk());
    verify(repo)
        .list(
            contains("booking_id=ANY(?::uuid[])"),
            eq("%%"), eq("%%"), eq("%%"), isNull(), isNull(), isNull(), isNull(),
            eq("{" + b1 + "," + b2 + "}"), eq("{" + b1 + "," + b2 + "}"));
  }

  @Test
  void facilityWithoutBookingsHasNoTransactionsAndSkipsTheQuery() throws Exception {
    UUID facility = UUID.randomUUID();
    when(bookings.bookingIds(eq(facility), any())).thenReturn(List.of());
    http.perform(get("/api/v1/admin/payments").param("facilityId", facility.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isEmpty());
    verify(repo, never()).list(anyString(), any(Object[].class));
  }

  @Test
  void withoutFacilityTheListIsUnscoped() throws Exception {
    http.perform(get("/api/v1/admin/payments")).andExpect(status().isOk());
    verify(bookings, never()).bookingIds(any(), any());
    verify(repo)
        .list(
            anyString(), eq("%%"), eq("%%"), eq("%%"), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull());
  }
}
