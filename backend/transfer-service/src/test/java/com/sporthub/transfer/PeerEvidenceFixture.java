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

/** Shared state for PeerEvidenceTest and the tests that build on its fixture; holds no @Test methods. */
abstract class PeerEvidenceFixture extends PeerTransferFlowFixture {
  PeerEvidence evidence;
  PeerEvidenceStorage storage;
  UUID attempt;
  Map<String, byte[]> objects;
  MockMultipartFile photo;

  @BeforeEach
  void evidenceFixture() throws Exception {
    UUID listing = listing();
    attempt = acquisitions.acquire(listing, key(), buyer, terms.path("termsHash").asText());
    objects = new ConcurrentHashMap<>();
    storage = mock(PeerEvidenceStorage.class);
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              objects.put(call.getArgument(0), call.getArgument(2));
              return null;
            })
        .when(storage)
        .put(anyString(), anyString(), any());
    when(storage.get(anyString()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return objects.get(call.getArgument(0));
            });
    evidence =
        new PeerEvidence(repo, new PeerEvidenceAccess(repo, authority), storage, clock, manager);
    var png = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png);
    photo = new MockMultipartFile("file", "receipt.png", "image/png", png.toByteArray());
  }

  static byte[] pixelBomb() throws IOException {
    var data = new ByteArrayOutputStream();
    var out = new DataOutputStream(data);
    out.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    var header = new ByteArrayOutputStream();
    var fields = new DataOutputStream(header);
    fields.writeBytes("IHDR");
    fields.writeInt(5000);
    fields.writeInt(5000);
    fields.write(new byte[] {8, 2, 0, 0, 0});
    out.writeInt(13);
    out.write(header.toByteArray());
    var crc = new CRC32();
    crc.update(header.toByteArray());
    out.writeInt((int) crc.getValue());
    out.writeInt(0);
    out.writeBytes("IEND");
    out.writeInt((int) 0xae426082L);
    return data.toByteArray();
  }
}
