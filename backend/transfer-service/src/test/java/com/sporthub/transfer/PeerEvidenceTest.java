package com.sporthub.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.transfer.service.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PeerEvidenceTest extends PeerEvidenceFixture {
  @Test
  void peerEvidenceRetryKeepsOneReadyImmutablePrivateObjectAndValidatesUploader() throws Exception {
    String key = key();
    var saved = evidence.upload(attempt, photo, key, buyer);
    assertThat(evidence.upload(attempt, photo, key, buyer)).isEqualTo(saved);
    assertThat(saved.state()).isEqualTo("READY");
    verify(storage, times(1)).put(anyString(), eq("image/png"), any());
    assertThat(evidence.image(saved.id(), seller, "unused").bytes()).isEqualTo(photo.getBytes());
    evidence.requireReady(attempt, buyer.id(), List.of(saved.id()));
    assertThatThrownBy(
            () -> evidence.requireReady(UUID.randomUUID(), buyer.id(), List.of(saved.id())))
        .isInstanceOf(ForbiddenException.class);
    assertThat(repo.write(saved)).doesNotContain("object_key", "peer/", "minio", "url");
    assertThatThrownBy(() -> evidence.requireReady(attempt, seller.id(), List.of(saved.id())))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () -> evidence.requireReady(attempt, buyer.id(), List.of(saved.id(), saved.id())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> evidence.upload(attempt, photo, key(), customer()))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> evidence.image(saved.id(), customer(), "unused"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE transfer_evidence SET sha256=? WHERE id=?",
                        "b".repeat(64),
                        saved.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> repo.jdbc().update("DELETE FROM transfer_evidence WHERE id=?", saved.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    objects.replaceAll((name, bytes) -> new byte[] {4});
    assertThatThrownBy(() -> evidence.image(saved.id(), seller, "unused"))
        .isInstanceOf(ActionPolicyException.class);
  }

  @Test
  void peerEvidenceStorageFailureLeavesStageWithoutExtendingPayByAndSameKeyRepairsIt() {
    doThrow(new ActionPolicyException(503, "UNAVAILABLE", "Kho ảnh chưa sẵn sàng.", Map.of()))
        .doNothing()
        .when(storage)
        .put(anyString(), anyString(), any());
    String key = key();
    var deadline = repo.acquisition(attempt).expiresAt();
    assertThatThrownBy(() -> evidence.upload(attempt, photo, key, buyer))
        .isInstanceOf(ActionPolicyException.class);
    UUID staged =
        repo.jdbc()
            .queryForObject(
                "SELECT id FROM transfer_evidence WHERE uploader_id=? AND idempotency_key=?",
                UUID.class,
                buyer.id(),
                key);
    assertThat(evidence.references(attempt)).isEmpty();
    assertThatThrownBy(() -> evidence.requireReady(attempt, buyer.id(), List.of(staged)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> evidence.image(staged, buyer, "unused"))
        .isInstanceOf(ConflictException.class);
    when(clock.instant()).thenReturn(now.plusSeconds(301));
    var ready = evidence.upload(attempt, photo, key, buyer);
    assertThat(ready.id()).isEqualTo(staged);
    assertThat(ready.state()).isEqualTo("READY");
    assertThat(repo.acquisition(attempt).expiresAt()).isEqualTo(deadline);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM peer_payment_claims WHERE acquisition_id=?",
                    Integer.class,
                    attempt))
        .isZero();
  }

  @Test
  void peerEvidenceRejectsFakeMimeHugeFilesAndPixelBombsBeforeStorage() throws Exception {
    for (var invalid :
        List.of(
            new MockMultipartFile("file", "fake.png", "image/png", "not an image".getBytes()),
            new MockMultipartFile("file", "mismatch.jpg", "image/jpeg", photo.getBytes()),
            new MockMultipartFile("file", "huge.png", "image/png", new byte[5 * 1024 * 1024 + 1]),
            new MockMultipartFile("file", "pixels.png", "image/png", pixelBomb())))
      assertThatThrownBy(() -> evidence.upload(attempt, invalid, key(), buyer))
          .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(storage);
    assertThat(evidence.references(attempt)).isEmpty();
  }

  @Test
  void peerEvidenceEnforcesTenPerHourBeforeStorageButExactReplayStillWorks() {
    String firstKey = key();
    var first = evidence.upload(attempt, photo, firstKey, buyer);
    for (int n = 0; n < 9; n++) evidence.upload(attempt, photo, key(), buyer);
    assertThatThrownBy(() -> evidence.upload(attempt, photo, key(), buyer))
        .isInstanceOfSatisfying(
            ActionPolicyException.class,
            e -> {
              assertThat(e.status()).isEqualTo(429);
              assertThat(e.details()).containsKey("retryAt");
            });
    assertThat(evidence.upload(attempt, photo, firstKey, buyer)).isEqualTo(first);
    evidence.upload(attempt, photo, key(), seller);
    when(clock.instant()).thenReturn(now.plusSeconds(3600));
    evidence.upload(attempt, photo, key(), buyer);
    verify(storage, times(12)).put(anyString(), anyString(), any());
  }

  @Test
  void peerEvidenceOwnerNeedsLiveMatchingCaseGrantAndStaffCannotInspect() {
    var saved = evidence.upload(attempt, photo, key(), buyer);
    var owner = new Caller(UUID.randomUUID(), "Owner", Set.of("OWNER"), Map.of());
    when(authority.evidenceAccess(attempt, "token"))
        .thenReturn(
            json.createObjectNode()
                .put("acquisitionId", attempt.toString())
                .put("facilityId", facility.toString())
                .put("actorId", owner.id().toString())
                .put("canReadEvidence", true));
    assertThat(evidence.image(saved.id(), owner, "token").bytes()).isNotEmpty();
    when(authority.evidenceAccess(attempt, "token"))
        .thenReturn(
            json.createObjectNode()
                .put("acquisitionId", attempt.toString())
                .put("facilityId", UUID.randomUUID().toString())
                .put("actorId", owner.id().toString())
                .put("canReadEvidence", true));
    assertThatThrownBy(() -> evidence.image(saved.id(), owner, "token"))
        .isInstanceOf(ForbiddenException.class);
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_CHECK_IN")));
    assertThatThrownBy(() -> evidence.image(saved.id(), staff, "token"))
        .isInstanceOf(ForbiddenException.class);
  }
}
