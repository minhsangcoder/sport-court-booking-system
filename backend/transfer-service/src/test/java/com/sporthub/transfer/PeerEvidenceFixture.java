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

/** Opens one peer attempt for the party command tests; holds no @Test methods. */
abstract class PeerEvidenceFixture extends PeerTransferFlowFixture {
  UUID attempt;

  @BeforeEach
  void attemptFixture() {
    UUID listing = listing();
    attempt = acquisitions.acquire(listing, key(), buyer, terms.path("termsHash").asText());
  }
}
