package com.sporthub.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.transfer.service.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;

class PeerMoneyCommandsTest extends PeerPartyCommandsFixture {
  PeerMoneyCommands money;

  @BeforeEach
  void moneyFixture() {
    money =
        new PeerMoneyCommands(
            new PeerEvidenceAccess(repo, authority), evidence, store, parties, authority, json);
    remoteAttempts.get(attempt).withObject("attempt").put("phase", "ABORTED_A_RETAINS");
    projection.acquisition(attempt);
    for (String target : List.of("money-report", "return-declare", "return-ack", "money-no-claim"))
      doAnswer(
              call -> {
                noTransaction();
                UUID id = call.getArgument(0), actor = call.getArgument(2);
                String key = call.getArgument(6);
                String action =
                    switch (target) {
                      case "money-report" -> "PEER_MONEY_REPORT";
                      case "return-declare" -> "PEER_RETURN_DECLARE";
                      case "return-ack" -> "PEER_RETURN_ACK";
                      default -> "PEER_MONEY_NO_CLAIM";
                    };
                String resultKey = actor + "|" + action + "|" + key;
                if (results.containsKey(resultKey)) return results.get(resultKey);
                if (beforeWrite.getAndSet(false)) throw uncertain();
                JsonNode result = remoteAttempts.get(id).path("attempt").deepCopy();
                results.put(resultKey, result);
                committed.incrementAndGet();
                if (loseReply.getAndSet(false)) throw uncertain();
                return result;
              })
          .when(authority)
          .party(eq(attempt), eq(target), any(), any(), any(), any(), anyString());
  }

  private String hash() {
    return repo.acquisition(attempt).financeTerms().path("termsHash").asText();
  }

  private PeerMoneyContract.ReturnInput input(UUID photo) {
    return new PeerMoneyContract.ReturnInput(
        hash(), new BigDecimal("50000"), "A RETURN", List.of(photo));
  }

  @Test
  void
      peerMoneyBridgeLostDeclareReplyAndEarlyBuyerAckRecoverInDependencyOrderWithoutNativePayment() {
    var bill = evidence.upload(attempt, photo, key(), seller);
    String key = key();
    loseReply.set(true);
    assertThatThrownBy(() -> money.returned(attempt, input(bill.id()), key, seller))
        .isInstanceOf(ActionPolicyException.class);
    var pending =
        store.pending().stream()
            .filter(value -> value.action().equals("PEER_RETURN_DECLARE"))
            .findFirst()
            .orElseThrow();
    UUID returned = UUID.fromString(pending.payload().path("input").path("returnId").asText());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM peer_return_declarations WHERE acquisition_id=?",
                    Integer.class,
                    attempt))
        .isZero();
    String acknowledged = key();
    var ack = new PeerResaleContract.Command(0, hash());
    assertThatThrownBy(() -> money.acknowledge(attempt, returned, ack, acknowledged, buyer))
        .isInstanceOfSatisfying(
            ActionPolicyException.class, error -> assertThat(error.status()).isEqualTo(503));
    parties.recover();
    parties.recover();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM peer_return_declarations WHERE acquisition_id=?",
                    Integer.class,
                    attempt))
        .isEqualTo(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM peer_return_acknowledgments WHERE return_id=?",
                    Integer.class,
                    returned))
        .isEqualTo(1);
    money.returned(attempt, input(bill.id()), key, seller);
    money.acknowledge(attempt, returned, ack, acknowledged, buyer);
    assertThat(committed.get()).isEqualTo(2);
    assertThat(repo.acquisition(attempt).paymentId()).isNull();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT state FROM peer_transfer_commands WHERE actor_id=? AND"
                        + " action='PEER_RETURN_ACK' AND idempotency_key=?",
                    String.class,
                    buyer.id(),
                    acknowledged))
        .isEqualTo("COMPLETED");
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update("UPDATE peer_return_declarations SET amount=1 WHERE id=?", returned))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void peerMoneyBridgeRequiresCorrectPartyReadyProofAndKeepsOwnerOutOfReturnsAndNoClaims() {
    var buyerBill = evidence.upload(attempt, photo, key(), buyer);
    assertThatThrownBy(() -> money.returned(attempt, input(buyerBill.id()), key(), buyer))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> money.returned(attempt, input(buyerBill.id()), key(), seller))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () ->
                money.acknowledge(
                    attempt,
                    UUID.randomUUID(),
                    new PeerResaleContract.Command(0, hash()),
                    key(),
                    seller))
        .isInstanceOf(ForbiddenException.class);
    var ownerActor = new Caller(UUID.randomUUID(), "Owner", Set.of("OWNER"), Map.of());
    assertThatThrownBy(
            () -> money.noClaim(attempt, new PeerMoneyContract.NoClaim(hash()), key(), ownerActor))
        .isInstanceOf(ForbiddenException.class);
    assertThat(committed.get()).isZero();
    money.report(
        attempt,
        new PeerMoneyContract.Report(hash(), "LATE", List.of(buyerBill.id())),
        key(),
        buyer);
    assertThat(repo.acquisition(attempt).state()).isNotEqualTo("SUCCESS");
    assertThat(repo.acquisition(attempt).paymentId()).isNull();
  }

  @Test
  void peerMoneyBridgeNoClaimHistoryNeedsEachActualCounterpartyAndNeverGrantsUsage() {
    var input = new PeerMoneyContract.NoClaim(hash());
    String first = key(), second = key();
    money.noClaim(attempt, input, first, seller);
    money.noClaim(attempt, input, second, buyer);
    money.noClaim(attempt, input, first, seller);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM peer_money_no_claims WHERE acquisition_id=?",
                    Integer.class,
                    attempt))
        .isEqualTo(2);
    assertThat(repo.acquisition(attempt).state()).isNotEqualTo("SUCCESS");
    assertThat(repo.acquisition(attempt).paymentId()).isNull();
    assertThat(committed.get()).isEqualTo(2);
  }
}
