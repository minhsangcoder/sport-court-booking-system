package com.sporthub.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.*;
import com.sporthub.transfer.service.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

/** Shared state for PeerPartyCommandsTest and the tests that build on its fixture; holds no @Test methods. */
abstract class PeerPartyCommandsFixture extends PeerEvidenceFixture {
  PeerPartyCommands parties;
  PeerPartyCommandStore store;
  AtomicBoolean loseReply, beforeWrite;
  AtomicInteger committed;
  Map<String, JsonNode> results;

  @BeforeEach
  void partyFixture() {
    store = new PeerPartyCommandStore(repo, clock, manager);
    parties =
        new PeerPartyCommands(
            new PeerPartyAccess(repo, authority),
            store,
            authority,
            projection,
            json,
            clock);
    loseReply = new AtomicBoolean();
    beforeWrite = new AtomicBoolean();
    committed = new AtomicInteger();
    results = new HashMap<>();
    when(authority.party(any(), anyString(), any(), any(), any(), any(), anyString()))
        .thenAnswer(
            call -> {
              noTransaction();
              UUID id = call.getArgument(0), actor = call.getArgument(2);
              String target = call.getArgument(1), key = call.getArgument(6);
              String action =
                  switch (target) {
                    case "declare" -> "PEER_DECLARE";
                    case "confirm" -> "PEER_CONFIRM";
                    case "deny" -> "PEER_DENY";
                    default -> "PEER_OPEN_DISPUTE";
                  };
              String resultKey = actor + "|" + action + "|" + key;
              if (results.containsKey(resultKey)) return results.get(resultKey);
              if (beforeWrite.getAndSet(false)) throw uncertain();
              var attempt = (ObjectNode) remoteAttempts.get(id).path("attempt");
              JsonNode input = call.getArgument(3);
              if (input.path("version").asLong() != attempt.path("version").asLong()
                  || !input.path("termsHash").asText().equals(attempt.path("termsHash").asText()))
                throw new ActionPolicyException(409, "STALE", "Phiên đã thay đổi.", Map.of());
              switch (target) {
                case "declare" -> {
                  if (!attempt.path("phase").asText().equals("WAITING_PAYMENT")
                      || !clock.instant().isBefore(repo.acquisition(id).expiresAt()))
                    throw new ActionPolicyException(409, "EXPIRED", "Đã hết hạn.", Map.of());
                  attempt
                      .put("phase", "PAYMENT_DECLARED")
                      .put("confirmationMethod", "BUYER_DECLARED");
                }
                case "confirm" -> {
                  assertThat(actor).isEqualTo(seller.id());
                  if (!attempt.path("phase").asText().equals("PAYMENT_DECLARED"))
                    throw new ActionPolicyException(
                        409, "NOT_DECLARED", "Chưa có lời khai hợp lệ.", Map.of());
                  attempt
                      .put("phase", "COMPLETED")
                      .put("confirmationMethod", "COUNTERPARTY_ACKNOWLEDGED");
                }
                case "deny" -> {
                  assertThat(actor).isEqualTo(seller.id());
                  attempt.put("phase", "ABORTED_A_RETAINS");
                }
                default -> throw new IllegalArgumentException();
              }
              attempt.put("version", attempt.path("version").asLong() + 1);
              JsonNode result = attempt.deepCopy();
              results.put(resultKey, result);
              committed.incrementAndGet();
              if (loseReply.getAndSet(false)) throw uncertain();
              return result;
            });
    when(authority.commandResult(any(), any(), anyString(), anyString()))
        .thenAnswer(
            call -> {
              noTransaction();
              return results.getOrDefault(
                  call.getArgument(1) + "|" + call.getArgument(2) + "|" + call.getArgument(3),
                  json.createObjectNode().put("phase", "UNKNOWN"));
            });
  }

  PeerResaleContract.Command command(long version) {
    return new PeerResaleContract.Command(
        version, repo.acquisition(attempt).financeTerms().path("termsHash").asText());
  }

  int count(String table) {
    return repo.jdbc()
        .queryForObject(
            "SELECT count(*) FROM " + table + " WHERE acquisition_id=?", Integer.class, attempt);
  }
}
