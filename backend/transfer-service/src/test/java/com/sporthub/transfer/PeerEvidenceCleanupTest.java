package com.sporthub.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sporthub.common.exception.*;
import com.sporthub.transfer.service.PeerEvidenceCleanup;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PeerEvidenceCleanupTest extends PeerEvidenceFixture {
  @Test
  void peerCleanupRetainsOpenCasesAndReadyEvidenceThenCleansOnlyUnreferencedStages() {
    var ready = evidence.upload(attempt, photo, key(), buyer);
    doThrow(new ActionPolicyException(503, "STORAGE_TIMEOUT", "Chưa rõ kết quả tải ảnh.", Map.of()))
        .when(storage)
        .put(anyString(), anyString(), any());
    String key = key();
    assertThatThrownBy(() -> evidence.upload(attempt, photo, key, buyer))
        .isInstanceOf(ActionPolicyException.class);
    var staged =
        repo.jdbc()
            .queryForMap(
                "SELECT * FROM transfer_evidence WHERE uploader_id=? AND idempotency_key=?",
                buyer.id(),
                key);
    UUID id = (UUID) staged.get("id");
    String object = staged.get("object_key").toString();
    objects.put(object, new byte[] {1});
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              objects.remove(call.getArgument(0));
              return null;
            })
        .when(storage)
        .remove(anyString());
    when(clock.instant()).thenReturn(now.plusSeconds(86401));
    when(authority.retention(attempt))
        .thenReturn(
            json.createObjectNode()
                .put("acquisitionId", attempt.toString())
                .put("canDiscardStaged", false));
    try (var cleanup = new PeerEvidenceCleanup(repo, authority, storage, clock, manager)) {
      cleanup.drain();
      assertThat(state(id)).isEqualTo("STAGED");
      verify(storage, never()).remove(anyString());
      when(authority.retention(attempt))
          .thenAnswer(
              call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return json.createObjectNode()
                    .put("acquisitionId", attempt.toString())
                    .put("canDiscardStaged", true);
              });
      cleanup.drain();
      cleanup.drain();
      assertThat(state(id)).isEqualTo("PURGING");
      assertThatThrownBy(() -> evidence.upload(attempt, photo, key, buyer))
          .isInstanceOf(ConflictException.class);
      verify(storage, never()).remove(anyString());
      when(clock.instant()).thenReturn(now.plusSeconds(86432));
      cleanup.drain();
      cleanup.drain();
      assertThat(objects).doesNotContainKey(object);
      assertThat(state(ready.id())).isEqualTo("READY");
      assertThat(evidence.image(ready.id(), seller, "unused").bytes()).isNotEmpty();
      // A paused upload writer may finish late, so the retained tombstone deletes it again.
      objects.put(object, new byte[] {2});
      when(clock.instant()).thenReturn(now.plusSeconds(90033));
      cleanup.drain();
      cleanup.drain();
      assertThat(objects).doesNotContainKey(object);
      assertThat(state(id)).isEqualTo("PURGING");
    }
  }

  private String state(UUID id) {
    return repo.jdbc()
        .queryForObject("SELECT state FROM transfer_evidence WHERE id=?", String.class, id);
  }
}
