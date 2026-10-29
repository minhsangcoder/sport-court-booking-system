package com.sporthub.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sporthub.common.exception.*;
import com.sporthub.common.money.PeerResaleContract.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.transfer.service.PeerConversations;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class PeerConversationsTest extends PeerTransferFlowFixture {
  PeerConversations chat() {
    return new PeerConversations(repo, clock);
  }

  Message send(PeerConversations chat, UUID conversation, String text, String key, Caller actor) {
    return new TransactionTemplate(manager)
        .execute(status -> chat.send(conversation, new MessageInput(text), key, actor));
  }

  UUID conversation(UUID acquisition) {
    return repo.jdbc()
        .queryForObject(
            "SELECT conversation_id FROM transfer_acquisitions WHERE id=?",
            UUID.class,
            acquisition);
  }

  UUID purchase(UUID listing, Caller actor) {
    return acquisitions.acquire(listing, key(), actor, terms.path("termsHash").asText());
  }

  @Test
  void latestHistoryAndNewMessagePagesStayBoundedAndPrivate() {
    var chat = chat();
    UUID room = conversation(purchase(listing(), buyer));
    var history = new ArrayList<Message>();
    for (int n = 0; n < 125; n++) {
      when(clock.instant()).thenReturn(now.plusSeconds(n * 61L));
      history.add(send(chat, room, "Tin " + n, key(), buyer));
    }
    assertThat(chat.messages(room, null, null, 50, seller))
        .containsExactlyElementsOf(history.subList(75, 125));
    assertThat(chat.messages(room, null, history.get(75).cursor(), 50, seller))
        .containsExactlyElementsOf(history.subList(25, 75));
    assertThat(chat.messages(room, history.getLast().cursor(), null, 50, seller)).isEmpty();
    when(clock.instant()).thenReturn(now.plusSeconds(126 * 61L));
    var newest = send(chat, room, "Tin mới", key(), buyer);
    assertThat(chat.messages(room, history.getLast().cursor(), null, 50, seller))
        .containsExactly(newest);
    assertThat(chat.messages(room, 0, 50, seller))
        .containsExactlyElementsOf(history.subList(0, 50));
    assertThatThrownBy(() -> chat.messages(room, null, null, 50, customer()))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> chat.messages(room, null, history.getLast().cursor(), 50, customer()))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> chat.messages(room, 0L, 5L, 50, buyer))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> chat.messages(room, null, 0L, 50, buyer))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void peerChatIsPrivateSurvivesExpiryAndCannotHoldOrChangeTerms() {
    var chat = chat();
    UUID listing = listing();
    UUID first = purchase(listing, buyer);
    UUID room = conversation(first);
    var message = send(chat, room, "Trao đổi giá, không đổi điều khoản đã chốt", key(), buyer);
    assertThat(chat.messages(room, 0, 50, seller)).containsExactly(message);
    remoteAttempts.get(first).withObject("attempt").put("phase", "EXPIRED");
    projection.acquisition(first);
    var other = customer();
    UUID second = purchase(listing, other);
    UUID otherRoom = conversation(second);
    assertThat(otherRoom).isNotEqualTo(room);
    assertThatThrownBy(() -> chat.messages(room, 0, 50, other))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> chat.messages(otherRoom, 0, 50, buyer))
        .isInstanceOf(ForbiddenException.class);
    for (String role : List.of("OWNER", "STAFF"))
      assertThatThrownBy(
              () ->
                  chat.messages(
                      room, 0, 50, new Caller(UUID.randomUUID(), role, Set.of(role), Map.of())))
          .isInstanceOf(ForbiddenException.class);
    send(chat, room, "Tin sau khi hết hạn không giữ lại sân", key(), buyer);
    assertThat(chat.messages(room, message.cursor(), 50, seller)).hasSize(1);
    assertThat(repo.find(listing).activeAcquisitionId()).isEqualTo(second);
    assertThat(repo.acquisition(first).financeTerms().path("amount").asText()).isEqualTo("100000");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM peer_payment_claims WHERE acquisition_id=?",
                    Integer.class,
                    first))
        .isZero();
  }

  @Test
  void peerChatConcurrentReplayAppendsOnceAndRateLimitNeverBlocksExactReplay() throws Exception {
    var chat = chat();
    UUID listing = listing();
    UUID attempt = purchase(listing, buyer);
    UUID room = conversation(attempt);
    String key = key();
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(6)) {
      var jobs = new ArrayList<Future<Message>>();
      for (int n = 0; n < 6; n++)
        jobs.add(
            pool.submit(
                () -> {
                  gate.await();
                  return send(chat, room, "Một tin", key, buyer);
                }));
      gate.countDown();
      var first = jobs.getFirst().get(5, TimeUnit.SECONDS);
      for (var job : jobs) assertThat(job.get(5, TimeUnit.SECONDS)).isEqualTo(first);
    }
    assertThat(chat.messages(room, 0, 100, buyer)).hasSize(1);
    for (int n = 0; n < 9; n++) send(chat, room, "Tin " + n, key(), buyer);
    assertThatThrownBy(() -> send(chat, room, "Tin vượt hạn", key(), buyer))
        .isInstanceOfSatisfying(
            ActionPolicyException.class,
            e -> {
              assertThat(e.status()).isEqualTo(429);
              assertThat(e.details()).containsKey("retryAt");
            });
    assertThat(send(chat, room, "Một tin", key, buyer).text()).isEqualTo("Một tin");
    assertThatThrownBy(() -> send(chat, room, "Sửa nội dung", key, buyer))
        .isInstanceOf(ConflictException.class);
    send(chat, room, "Người bán có hạn mức riêng", key(), seller);
    when(clock.instant()).thenReturn(now.plusSeconds(60));
    send(chat, room, "Đã qua cửa sổ giới hạn", key(), buyer);
    assertThat(chat.messages(room, 0, 100, seller)).hasSize(12);
  }

  @Test
  void peerChatBoundsCursorTextAndStoresMarkupAsPlainImmutableText() {
    var chat = chat();
    UUID room = conversation(purchase(listing(), buyer));
    for (String invalid : List.of("   ", "x".repeat(2001), "text\u0000"))
      assertThatThrownBy(() -> send(chat, room, invalid, key(), buyer))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> chat.messages(room, 0, 101, buyer))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> chat.messages(room, -1, 50, buyer))
        .isInstanceOf(IllegalArgumentException.class);
    var markup = send(chat, room, "<script>alert('literal')</script>", key(), buyer);
    assertThat(chat.messages(room, 0, 1, seller).getFirst().text()).isEqualTo(markup.text());
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE transfer_messages SET text='rewritten' WHERE id=?", markup.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
