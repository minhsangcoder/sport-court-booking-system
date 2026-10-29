package com.sporthub.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import com.sporthub.transfer.repository.TransferRepository;
import com.sporthub.transfer.service.*;
import com.sporthub.transfer.web.TransferDtos.Create;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;

class PeerTransferFlowTest extends PeerTransferFlowFixture {
  @Test
  void lostPublishReplyRecoversOnlyTheCommittedResultAndDoesNotCreateAnotherListing() {
    losePublish.set(true);
    String key = key();
    assertThatThrownBy(() -> listings.create(input(), key, seller))
        .isInstanceOf(ActionPolicyException.class);
    UUID id =
        repo.jdbc()
            .queryForObject(
                "SELECT resource_id FROM request_idempotency WHERE actor_id=? AND key=?",
                UUID.class,
                seller.id(),
                key);
    assertThat(repo.find(id).workflow()).isEqualTo("REGISTER");
    clearInvocations(authority);
    projection.recover();
    assertThat(repo.find(id).workflow()).isEqualTo("READY");
    verify(authority, never()).market(anyString(), any(), any(), any());
    assertThat(listings.create(input(), key, seller)).isEqualTo(id);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM transfer_listings WHERE booking_id=?",
                    Integer.class,
                    book))
        .isEqualTo(1);
    assertThat(repo.view(repo.find(id), buyer.id(), true).financeTerms().toString())
        .doesNotContain("sellerId", "bankDestinationRevisionId", "feeBps", "sellerNetAmount");
  }

  @Test
  void lostAcquireReplyKeepsOnePrivateConversationAndRecoveryNeverStartsAnotherClaim() {
    UUID id = listing();
    String key = key();
    loseAcquire.set(true);
    assertThatThrownBy(() -> acquisitions.acquire(id, key, buyer, terms.path("termsHash").asText()))
        .isInstanceOf(ActionPolicyException.class);
    UUID purchase =
        repo.jdbc()
            .queryForObject(
                "SELECT resource_id FROM request_idempotency WHERE actor_id=? AND key=?",
                UUID.class,
                buyer.id(),
                key);
    assertThat(repo.acquisition(purchase).conversationId()).isNotNull();
    assertThat(repo.acquisition(purchase).paymentId()).isNull();
    clearInvocations(authority);
    projection.recover();
    assertThat(repo.find(id).state()).isEqualTo("LOCKED");
    verify(authority, never()).market(anyString(), any(), any(), any());
    assertThat(acquisitions.acquire(id, key, buyer, terms.path("termsHash").asText()))
        .isEqualTo(purchase);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM transfer_conversations WHERE listing_id=?",
                    Integer.class,
                    id))
        .isEqualTo(1);
    assertThat(acquisitions.read(purchase, seller).path("conversationId").asText())
        .isEqualTo(repo.acquisition(purchase).conversationId().toString());
    assertThatThrownBy(() -> acquisitions.read(purchase, customer()))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void onlyCanonicalCompletionProjectsSuccessAndItNeverFabricatesANativePayment() {
    UUID id = listing(),
        purchase = acquisitions.acquire(id, key(), buyer, terms.path("termsHash").asText());
    var attempt = (ObjectNode) remoteAttempts.get(purchase).path("attempt");
    attempt.put("phase", "COMPLETED").put("confirmationMethod", "BANK_VERIFIED");
    assertThatThrownBy(() -> projection.acquisition(purchase))
        .isInstanceOf(ConflictException.class);
    assertThat(repo.acquisition(purchase).state()).isEqualTo("PENDING");
    attempt.put("confirmationMethod", "COUNTERPARTY_ACKNOWLEDGED");
    projection.acquisition(purchase);
    projection.acquisition(purchase);
    assertThat(repo.acquisition(purchase).state()).isEqualTo("SUCCESS");
    assertThat(repo.find(id).state()).isEqualTo("COMPLETED");
    assertThat(repo.acquisition(purchase).paymentId()).isNull();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM transfer_payment_receipts WHERE acquisition_id=?",
                    Integer.class,
                    purchase))
        .isZero();
  }

  @Test
  void oldAttemptExpiryCannotUnlockTheNextBuyerOrCloseTheirConversation() {
    UUID id = listing(),
        first = acquisitions.acquire(id, key(), buyer, terms.path("termsHash").asText());
    ((ObjectNode) remoteAttempts.get(first).path("attempt")).put("phase", "EXPIRED");
    remoteListings.get(id).put("state", "ACTIVE").remove("acquisitionId");
    projection.acquisition(first);
    var nextBuyer = customer();
    UUID next = acquisitions.acquire(id, key(), nextBuyer, terms.path("termsHash").asText());
    projection.acquisition(first);
    assertThat(repo.find(id).activeAcquisitionId()).isEqualTo(next);
    assertThat(repo.find(id).state()).isEqualTo("LOCKED");
    assertThat(repo.acquisition(next).state()).isEqualTo("PENDING");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM transfer_conversations WHERE listing_id=?",
                    Integer.class,
                    id))
        .isEqualTo(2);
  }

  @Test
  void aDefinitiveAdmissionRejectionDoesNotReserveTheListingForFiveMinutes() {
    UUID id = listing();
    rejectAcquire.set(true);
    assertThatThrownBy(
            () -> acquisitions.acquire(id, key(), buyer, terms.path("termsHash").asText()))
        .isInstanceOf(ActionPolicyException.class);
    assertThat(repo.find(id).state()).isEqualTo("ACTIVE");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT state FROM transfer_acquisitions WHERE listing_id=?", String.class, id))
        .isEqualTo("FAILED");
    UUID accepted = acquisitions.acquire(id, key(), customer(), terms.path("termsHash").asText());
    assertThat(repo.acquisition(accepted).state()).isEqualTo("PENDING");
  }
}
