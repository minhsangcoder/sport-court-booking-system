package com.sporthub.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.*;
import com.sporthub.transfer.service.*;
import com.sporthub.transfer.web.TransferDtos.Edit;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;

class PeerListingCommandsTest extends PeerTransferFlowFixture {
  PeerListingCommands commands;
  Map<String, JsonNode> committed;
  AtomicBoolean lost;

  @BeforeEach
  void listingCommands() {
    commands = new PeerListingCommands(repo, listings, authority, projection, clock, json, manager);
    committed = new HashMap<>();
    lost = new AtomicBoolean();
    doAnswer(
            call -> {
              noTransaction();
              PeerMarketContract.Publish input = json.convertValue(call.getArgument(1),PeerMarketContract.Publish.class);
              var current = remoteListings.get(input.listingId());
              String key = call.getArgument(2);
              if (committed.containsKey(key)) return committed.get(key);
              if (current == null) {
                current =
                    json.createObjectNode()
                        .put("id", input.listingId().toString())
                        .put("state", "ACTIVE")
                        .put("version", 0);
                current.set("terms", input.terms().deepCopy());
                remoteListings.put(input.listingId(), current);
              } else {
                if (!current.path("state").asText().equals("ACTIVE")
                    || current.path("version").asLong() != input.expectedVersion())
                  throw new ActionPolicyException(409, "CONFLICT", "Tin đã thay đổi", Map.of());
                current.put("version", input.expectedVersion() + 1);
                current.set("terms", input.terms().deepCopy());
              }
              committed.put(key, current.deepCopy());
              if (lost.getAndSet(false)) throw uncertain();
              return current.deepCopy();
            })
        .when(authority)
        .market(eq("publish"), any(), anyString(), any());
    doAnswer(
            call -> {
              noTransaction();
              PeerMarketContract.Withdraw input = json.convertValue(call.getArgument(1),PeerMarketContract.Withdraw.class);
              var current = remoteListings.get(input.listingId());
              if (current.path("version").asLong() != input.version())
                throw new ActionPolicyException(409, "CONFLICT", "Tin đã thay đổi", Map.of());
              current.put("state", "RELEASED");
              committed.put(call.getArgument(2), current.deepCopy());
              if (lost.getAndSet(false)) throw uncertain();
              return current.deepCopy();
            })
        .when(authority)
        .market(eq("withdraw"), any(), anyString(), any());
    doAnswer(
            call -> {
              noTransaction();
              return committed.getOrDefault(
                  call.getArgument(2), json.createObjectNode().put("state", "UNKNOWN"));
            })
        .when(authority)
        .market(eq("result"), any(), anyString(), any());
  }

  Edit edit(long version) {
    return new Edit(
        new BigDecimal(terms.path("amount").asText()),
        start.minusSeconds(60),
        null,
        terms.path("termsHash").asText(),
        UUID.fromString(terms.path("bankDestinationRevisionId").asText()),
        version);
  }

  void newTerms() {
    var updated = (ObjectNode) terms.deepCopy();
    updated.put("amount", "90000");
    updated.put("termsHash", PeerResaleTerms.sellerHash(updated));
    terms = updated;
  }

  @Test
  void peerListingLostEditReplyIsRecoveredReadOnlyAndPreservesTheAcceptedConsent() {
    UUID id = listing();
    newTerms();
    var input = edit(0);
    String key = key();
    lost.set(true);
    assertThatThrownBy(() -> commands.edit(id, input, key, seller))
        .isInstanceOf(ActionPolicyException.class);
    assertThat(repo.find(id).price()).isEqualByComparingTo("100000");
    clearInvocations(authority);
    commands.recover();
    assertThat(repo.find(id).price()).isEqualByComparingTo("90000");
    assertThat(repo.find(id).version()).isEqualTo(1);
    assertThat(repo.find(id).financeTerms().path("bankSharingConsent").asText())
        .isEqualTo("BUYER_AND_SEPAY_QR_V1");
    verify(authority, never()).market(eq("publish"), any(), any(), any());
    commands.edit(id, input, key, seller);
    assertThat(repo.find(id).version()).isEqualTo(1);
    assertThatThrownBy(() -> commands.edit(id, edit(0), key(), seller))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> commands.quote(id, input, buyer))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE peer_listing_operations SET command='{}'::jsonb WHERE actor_id=?",
                        seller.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void peerListingUnknownCommandNeverStartsAnEditFromTheWorkerAndWithdrawResponseLossIsSafe() {
    UUID id = listing();
    newTerms();
    var input = edit(0);
    doThrow(uncertain()).when(authority).market(eq("publish"), any(), anyString(), any());
    assertThatThrownBy(() -> commands.edit(id, input, key(), seller))
        .isInstanceOf(ActionPolicyException.class);
    clearInvocations(authority);
    commands.recover();
    assertThat(repo.find(id).price()).isEqualByComparingTo("100000");
    verify(authority, never()).market(eq("publish"), any(), any(), any());
    String key = key();
    lost.set(true);
    assertThatThrownBy(() -> commands.withdraw(id, 0, key, seller))
        .isInstanceOf(ActionPolicyException.class);
    commands.recover();
    assertThat(repo.find(id).state()).isEqualTo("WITHDRAWN");
    commands.withdraw(id, 0, key, seller);
    assertThat(repo.find(id).state()).isEqualTo("WITHDRAWN");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM peer_listing_operations WHERE listing_id=? AND"
                        + " action='withdraw'",
                    Integer.class,
                    id))
        .isEqualTo(1);
  }
}
