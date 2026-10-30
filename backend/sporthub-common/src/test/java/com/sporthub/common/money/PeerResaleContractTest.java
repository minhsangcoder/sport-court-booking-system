package com.sporthub.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PeerResaleContractTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void onlyExplicitPeerTermsChooseThePeerProtocol() throws Exception {
    assertThat(PeerResaleContract.isPeer(null)).isFalse();
    assertThat(PeerResaleContract.isPeer(json.readTree("{}"))).isFalse();
    assertThat(PeerResaleContract.isPeer(json.readTree("{\"protocol\":\"LEGACY_OWNER\"}")))
        .isFalse();
    assertThat(PeerResaleContract.isPeer(json.readTree("{\"protocol\":\"PEER_RESALE_V1\"}")))
        .isTrue();
  }

  @Test
  void peerConfirmationCannotBeMistakenForBankVerification() {
    assertThat(PeerResaleContract.ConfirmationMethod.values())
        .extracting(Enum::name)
        .doesNotContain("BANK_VERIFIED", "SUCCESS");
  }
}
