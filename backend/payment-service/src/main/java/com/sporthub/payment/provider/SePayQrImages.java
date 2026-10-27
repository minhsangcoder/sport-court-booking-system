package com.sporthub.payment.provider;

import com.sporthub.common.exception.ActionPolicyException;
import com.sporthub.payment.service.BankDestinations.PrivateDestination;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.*;
import org.springframework.stereotype.Component;

/** Image-only SePay integration. No webhook, payment order or bank-confirmation side effect. */
@Component
public class SePayQrImages {
  static final int MAX_BYTES = 512 * 1024;
  private final URI endpoint;
  private final HttpClient http;

  public SePayQrImages(
      @Value("${SEPAY_QR_IMAGE_URL:https://vietqr.app/img}") String url,
      @Value("${PAYMENT_TEST_PROVIDER_ALLOWED:false}") boolean allowTest,
      Environment environment) {
    endpoint = URI.create(url);
    boolean production = environment.acceptsProfiles(Profiles.of("live", "prod", "production"));
    boolean loopback =
        "http".equals(endpoint.getScheme())
            && Set.of("localhost", "127.0.0.1").contains(endpoint.getHost())
            && endpoint.getUserInfo() == null
            && endpoint.getQuery() == null
            && endpoint.getFragment() == null;
    if (!"https://vietqr.app/img".equals(url) && !(allowTest && !production && loopback))
      throw new IllegalStateException("SEPAY_QR_IMAGE_URL phải dùng địa chỉ HTTPS chính thức.");
    http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public byte[] fetch(PrivateDestination recipient, BigDecimal amount, String memo) {
    if (recipient == null) throw new IllegalArgumentException("Thiếu người nhận QR.");
    return fetch(recipient.destination().bankBin(), recipient.accountNumber(), amount, memo);
  }

  public byte[] fetch(String bankBin, String accountNumber, BigDecimal amount, String memo) {
    if (bankBin == null
        || !bankBin.matches("[0-9]{6}")
        || accountNumber == null
        || !accountNumber.matches("[0-9]{1,19}")
        || amount == null
        || amount.signum() <= 0
        || amount.stripTrailingZeros().scale() > 0
        || amount.compareTo(new BigDecimal("9999999999")) > 0
        || memo == null
        || !memo.matches("(?:SH|SC)[0-9]{1,18}"))
      throw new IllegalArgumentException("Thông tin mã QR chưa hợp lệ.");
    String value = amount.toBigIntegerExact().toString();
    URI uri =
        URI.create(
            endpoint
                + "?acc="
                + accountNumber
                + "&bank="
                + bankBin
                + "&amount="
                + value
                + "&des="
                + memo
                + "&template=qronly");
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      var request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(4))
              .header("Accept", "image/png")
              .GET()
              .build();
      pending = http.sendAsync(request, info -> new BoundedImage());
      var response = pending.get(5, TimeUnit.SECONDS);
      if (response.statusCode() != 200
          || !response
              .headers()
              .firstValue("Content-Type")
              .orElse("")
              .split(";")[0]
              .trim()
              .equalsIgnoreCase("image/png")) throw new IllegalStateException();
      PeerVietQrImage.verify(response.body(), bankBin, accountNumber, value, memo);
      return response.body();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (Exception ex) {
      // Provider failures may contain the complete account URL; never propagate their cause.
      throw unavailable();
    } finally {
      if (pending != null && !pending.isDone()) pending.cancel(true);
    }
  }

  public static ActionPolicyException unavailable() {
    return new ActionPolicyException(
        503,
        "PEER_QR_IMAGE_UNAVAILABLE",
        "Chưa tạo được mã QR. Vui lòng thử lại trong cùng phiên; thời hạn thanh toán không đổi.",
        Map.of());
  }

  private static final class BoundedImage implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    public CompletionStage<byte[]> getBody() {
      return result;
    }

    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      value.request(1);
    }

    public void onNext(List<ByteBuffer> parts) {
      for (var part : parts) {
        if ((long) bytes.size() + part.remaining() > MAX_BYTES) {
          subscription.cancel();
          result.completeExceptionally(new IllegalStateException());
          return;
        }
        byte[] chunk = new byte[part.remaining()];
        part.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    public void onError(Throwable error) {
      result.completeExceptionally(new IllegalStateException());
    }

    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
