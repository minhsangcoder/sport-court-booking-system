package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sporthub.booking.web.BookingDtos.CodeInput;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.PeerResaleContract;
import com.sporthub.common.security.RemoteIdentity.Caller;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class PeerUsageRaceTest extends PeerUsageIntegrationTest {
  @org.springframework.beans.factory.annotation.Autowired
  com.sporthub.booking.service.BookingUsage usage;

  @Test
  void peerRaceCodeResolvedBeforeHandoffIsRevalidatedAfterTakingTheCourtLock() {
    var sale = sale();
    declare(sale);
    String resolvedCode = sale.original().bookingCode();
    peer.confirm(
        sale.acquisition(), new PeerResaleContract.Command(1, sale.hash()), key(), customer.id());
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_CHECK_IN")));
    var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        usage.checkin(sale.original().id(), resolvedCode, staff, "token", true)))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThat(repo.find(sale.original().id()).currentHolderId()).isEqualTo(sale.buyer().id());
    assertThat(repo.find(sale.original().id()).status()).isEqualTo("CONFIRMED");
    assertThat(
            service
                .checkinByCode(
                    facility,
                    new CodeInput(repo.find(sale.original().id()).bookingCode()),
                    staff,
                    "token")
                .status())
        .isEqualTo("CHECKED_IN");
  }

  private List<Boolean> race(Callable<?>... actions) throws Exception {
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(actions.length)) {
      var jobs = new ArrayList<Future<Boolean>>();
      for (var action : actions)
        jobs.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    action.call();
                    return true;
                  } catch (ConflictException
                      | ForbiddenException
                      | ResourceNotFoundException expected) {
                    return false;
                  }
                }));
      gate.countDown();
      var results = new ArrayList<Boolean>();
      for (var job : jobs) results.add(job.get(10, TimeUnit.SECONDS));
      return results;
    }
  }

}
