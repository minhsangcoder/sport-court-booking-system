package com.sporthub.payment.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.sporthub.common.exception.ConflictException;
import com.sporthub.common.security.RemoteIdentity.Caller;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class IncomingQrImages {
  private final PaymentQrSessions sessions;
  private final SepaySessions sepay;

  public IncomingQrImages(PaymentQrSessions sessions, SepaySessions sepay) {
    this.sessions = sessions;
    this.sepay = sepay;
  }

  public String image(UUID payment, Caller actor, String token) {
    String value = sessions.own(payment, actor, token).qrCode();
    if (value != null && value.equals("SEPAY_QR_" + payment)) return sepay.image(payment);
    if (value == null || value.isBlank() || value.length() > 4096)
      throw new ConflictException("QR thanh toán chưa sẵn sàng hoặc đã hết hạn.");
    try {
      var matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 480, 480);
      var image =
          new java.awt.image.BufferedImage(480, 480, java.awt.image.BufferedImage.TYPE_BYTE_BINARY);
      for (int y = 0; y < 480; y++)
        for (int x = 0; x < 480; x++)
          image.setRGB(x, y, matrix.get(x, y) ? 0xff000000 : 0xffffffff);
      var bytes = new java.io.ByteArrayOutputStream();
      javax.imageio.ImageIO.write(image, "png", bytes);
      return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    } catch (Exception ex) {
      throw new IllegalStateException("Không dựng được ảnh QR thanh toán.");
    }
  }
}
