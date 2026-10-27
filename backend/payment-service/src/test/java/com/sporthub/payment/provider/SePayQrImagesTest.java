package com.sporthub.payment.provider;

import static org.assertj.core.api.Assertions.*;

import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import com.sporthub.common.exception.ActionPolicyException;
import com.sporthub.payment.service.BankDestinations.*;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class SePayQrImagesTest {
  HttpServer server;
  String origin;
  AtomicReference<byte[]> image;
  AtomicReference<String> query;
  AtomicReference<String> auth;
  AtomicInteger status;
  AtomicReference<String> contentType;
  AtomicInteger delay;

  @BeforeEach
  void fixture() throws Exception {
    image = new AtomicReference<>(png("000123456789", "80000", "SH1"));
    query = new AtomicReference<>();
    auth = new AtomicReference<>();
    status = new AtomicInteger(200);
    contentType = new AtomicReference<>("image/png");
    delay = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/img",
        exchange -> {
          query.set(exchange.getRequestURI().getRawQuery());
          auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getResponseHeaders().set("Content-Type", contentType.get());
          exchange.getResponseHeaders().set("Location", origin + "/redirected");
          try {
            exchange.sendResponseHeaders(status.get(), image.get().length);
            if (delay.get() > 0) Thread.sleep(delay.get());
            exchange.getResponseBody().write(image.get());
          } catch (Exception ignored) {
          } finally {
            exchange.close();
          }
        });
    server.start();
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private SePayQrImages client() {
    return new SePayQrImages(origin + "/img", true, new MockEnvironment());
  }

  private PrivateDestination bank() {
    return new PrivateDestination(
        new Destination(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "970436",
            "VCB",
            "***6789",
            "A",
            "a".repeat(64),
            Instant.now(),
            "SELF_DECLARED"),
        "000123456789");
  }

  @Test
  void fetchesBoundPngAndDecodesExactRecipientAmountMemoWithoutAuthorization() {
    byte[] result = client().fetch(bank(), new BigDecimal("80000"), "SH1");
    assertThat(result).isEqualTo(image.get());
    assertThat(query.get())
        .isEqualTo("acc=000123456789&bank=970436&amount=80000&des=SH1&template=qronly");
    assertThat(auth.get()).isNull();
  }

  @Test
  void facilityPaymentQrUsesItsOwnBoundMemoAndAccount() throws Exception {
    image.set(png("000123456789", "100000", "SC0000001"));
    assertThat(client().fetch("970436", "000123456789", new BigDecimal("100000"), "SC0000001"))
        .isEqualTo(image.get());
    assertThat(query.get()).contains("des=SC0000001", "amount=100000", "acc=000123456789");
  }

  @Test
  void validFacilityQrWithFinderLikeDataStillRequiresExactReceiverAndMemo() throws Exception {
    image.set(png("000555666777", "120000", "SC1"));
    assertThat(client().fetch("970436", "000555666777", new BigDecimal("120000"), "SC1"))
        .isEqualTo(image.get());
    assertThatThrownBy(
            () -> client().fetch("970436", "000555666777", new BigDecimal("120000"), "SC2"))
        .isInstanceOf(ActionPolicyException.class);
  }

  @Test
  void rejectsWrongEncodedAccountAmountMemoAndMalformedImage() throws Exception {
    for (byte[] invalid :
        List.of(
            png("123456789", "80000", "SH1"),
            png("000123456789", "80001", "SH1"),
            png("000123456789", "80000", "SH2"),
            new byte[20],
            new byte[512 * 1024 + 1])) {
      image.set(invalid);
      assertRedactedFailure();
    }
    image.set(png("000123456789", "80000", "SH1"));
    contentType.set("text/html");
    assertRedactedFailure();
    contentType.set("image/png");
    status.set(302);
    assertRedactedFailure();
  }

  @Test
  void timesOutEvenWhenHeadersHaveArrivedButImageBodyStalls() {
    delay.set(7000);
    long started = System.nanoTime();
    assertRedactedFailure();
    assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(6500);
  }

  @Test
  void refusesArbitraryOriginsAndLoopbackOverrideInProduction() {
    for (String url :
        List.of(
            "http://vietqr.app/img",
            "https://vietqr.app.evil/img",
            "https://vietqr.app/img?bank=970436",
            "http://127.0.0.1:42/img#fragment"))
      assertThatThrownBy(() -> new SePayQrImages(url, true, new MockEnvironment()))
          .isInstanceOf(IllegalStateException.class);
    for (String profile : List.of("live", "production", "prod")) {
      var env = new MockEnvironment();
      env.setActiveProfiles(profile, "local");
      assertThatThrownBy(() -> new SePayQrImages(origin + "/img", true, env))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(() -> new SePayQrImages(origin + "/img", false, new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class);
  }

  private void assertRedactedFailure() {
    assertThatThrownBy(() -> client().fetch(bank(), new BigDecimal("80000"), "SH1"))
        .isInstanceOf(ActionPolicyException.class)
        .hasNoCause()
        .hasMessageNotContaining("000123456789")
        .hasMessageNotContaining(origin);
  }

  @Test
  void recordedOfficialProviderImageHasTheExactSyntheticRecipientAmountAndMemo() throws Exception {
    try (var input = getClass().getResourceAsStream("/peer-qr/sepay-vcb-synthetic.png")) {
      assertThat(input).isNotNull();
      PeerVietQrImage.verify(input.readAllBytes(), "970436", "000123456789", "80000", "SH1");
    }
  }

  // A synthetic EMV/NAPAS image fixture, not a bank receipt or a live transfer.
  public static byte[] png(String account, String amount, String memo) throws Exception {
    String merchant =
        tag("00", "A000000727")
            + tag("01", tag("00", "970436") + tag("01", account))
            + tag("02", "QRIBFTTA");
    String payload =
        tag("00", "01")
            + tag("01", "12")
            + tag("38", merchant)
            + tag("53", "704")
            + tag("54", amount)
            + tag("58", "VN")
            + tag("62", tag("08", memo))
            + "6304";
    int checksum = 0xffff;
    for (char c : payload.toCharArray()) {
      checksum ^= c << 8;
      for (int bit = 0; bit < 8; bit++)
        checksum = (checksum & 0x8000) != 0 ? (checksum << 1) ^ 0x1021 : checksum << 1;
      checksum &= 0xffff;
    }
    payload += String.format(Locale.ROOT, "%04X", checksum);
    var matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 480, 480);
    var bitmap = new BufferedImage(480, 480, BufferedImage.TYPE_BYTE_BINARY);
    for (int y = 0; y < 480; y++)
      for (int x = 0; x < 480; x++) bitmap.setRGB(x, y, matrix.get(x, y) ? 0xff000000 : 0xffffffff);
    var output = new ByteArrayOutputStream();
    ImageIO.write(bitmap, "png", output);
    return output.toByteArray();
  }

  private static String tag(String id, String value) {
    return id + String.format(Locale.ROOT, "%02d", value.length()) + value;
  }
}
