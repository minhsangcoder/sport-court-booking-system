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

class PeerPartyCommandsTest extends PeerPartyCommandsFixture {
  @Test
  void peerPartyBuyerClaimThenSellerAcknowledgmentCompletesWithoutEvidenceOrBankAssurance() {
    String declaredKey = key(), confirmedKey = key();
    var declared = parties.declare(attempt, command(0), declaredKey, buyer);
    assertThat(declared.path("phase").asText()).isEqualTo("PAYMENT_DECLARED");
    assertThat(repo.acquisition(attempt).state()).isEqualTo("PENDING");
    assertThat(count("peer_seller_acknowledgments")).isZero();
    var completed = parties.confirm(attempt, command(1), confirmedKey, seller);
    assertThat(completed.path("confirmationMethod").asText())
        .isEqualTo("COUNTERPARTY_ACKNOWLEDGED");
    assertThat(repo.acquisition(attempt).state()).isEqualTo("SUCCESS");
    assertThat(repo.acquisition(attempt).paymentId()).isNull();
    when(clock.instant()).thenReturn(start.plusSeconds(60));
    assertThat(parties.declare(attempt, command(0), declaredKey, buyer))
        .isEqualTo(declared);
    assertThat(parties.confirm(attempt, command(1), confirmedKey, seller)).isEqualTo(completed);
    assertThat(count("peer_seller_acknowledgments")).isEqualTo(1);
    assertThat(committed.get()).isEqualTo(2);
    assertThat(repo.acquisition(attempt).financeTerms().toString())
        .doesNotContain("feeBps", "sellerNetAmount", "BANK_VERIFIED");
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE peer_transfer_commands SET command='{}' WHERE acquisition_id=?",
                        attempt))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void peerPartyLostReplyRecoversOnlyCommittedResultsAndUnknownNeedsTheSameHumanRetry() {
    String key = key();
    beforeWrite.set(true);
    assertThatThrownBy(() -> parties.declare(attempt, command(0), key, buyer))
        .isInstanceOf(ActionPolicyException.class);
    parties.recover();
    assertThat(committed.get()).isZero();
    verify(authority, times(1)).party(any(), anyString(), any(), any(), any(), any(), anyString());
    loseReply.set(true);
    assertThatThrownBy(() -> parties.declare(attempt, command(0), key, buyer))
        .isInstanceOf(ActionPolicyException.class);
    var restarted =
        new PeerPartyCommands(
            new PeerPartyAccess(repo, authority),
            new PeerPartyCommandStore(repo, clock, manager),
            authority,
            projection,
            json,
            clock);
    restarted.recover();
    assertThat(repo.acquisition(attempt).state()).isEqualTo("PENDING");
    loseReply.set(true);
    String confirmKey = key();
    assertThatThrownBy(() -> restarted.confirm(attempt, command(1), confirmKey, seller))
        .isInstanceOf(ActionPolicyException.class);
    restarted.recover();
    assertThat(repo.acquisition(attempt).state()).isEqualTo("SUCCESS");
    assertThat(count("peer_seller_acknowledgments")).isEqualTo(1);
    assertThat(committed.get()).isEqualTo(2);
    verify(authority, times(3)).party(any(), anyString(), any(), any(), any(), any(), anyString());
  }

  @Test
  void peerPartyRejectsImpersonationAndStaleVersions() {
    assertThatThrownBy(() -> parties.declare(attempt, command(0), key(), seller))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> parties.confirm(attempt, command(0), key(), buyer))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> parties.deny(attempt, command(0), key(), buyer))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> parties.confirm(attempt, command(9), key(), seller))
        .isInstanceOf(ActionPolicyException.class);
    assertThat(committed.get()).isZero();
    assertThat(count("peer_seller_acknowledgments")).isZero();
    assertThat(repo.acquisition(attempt).state()).isEqualTo("PENDING");
  }

  @Test
  void peerPartySellerDenialLeavesTheCourtWithTheSellerWithoutInventingAPayment() {
    parties.declare(attempt, command(0), key(), buyer);
    assertThat(parties.deny(attempt, command(1), key(), seller).path("phase").asText())
        .isEqualTo("ABORTED_A_RETAINS");
    assertThat(count("peer_seller_acknowledgments")).isZero();
    assertThat(repo.acquisition(attempt).paymentId()).isNull();
    assertThatThrownBy(() -> parties.confirm(attempt, command(2), key(), seller))
        .isInstanceOf(ActionPolicyException.class);
    assertThat(count("peer_seller_acknowledgments")).isZero();
  }

  @Test
  void peerPartyStaleDeclarationDoesNotPoisonTheBuyersLaterCorrectDeclaration() {
    assertThatThrownBy(() -> parties.declare(attempt, command(9), key(), buyer))
        .isInstanceOf(ConflictException.class);
    assertThat(parties.declare(attempt, command(0), key(), buyer).path("phase").asText())
        .isEqualTo("PAYMENT_DECLARED");
    assertThat(count("peer_seller_acknowledgments")).isZero();
  }
}
