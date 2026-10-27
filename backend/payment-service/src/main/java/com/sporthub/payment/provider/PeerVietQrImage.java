package com.sporthub.payment.provider;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;

/** Reject a provider image which encodes a different recipient, amount or instruction. */
final class PeerVietQrImage {
  private PeerVietQrImage() {}

  static void verify(byte[] png, String bin, String account, String amount, String memo)
      throws Exception {
    if (png.length < 8
        || !Arrays.equals(
            Arrays.copyOf(png, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
      throw new IOException();
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IOException();
      var reader = readers.next();
      try {
        reader.setInput(input, true, true);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 64 || height < 64 || (long) width * height > 2_000_000) throw new IOException();
        var image = reader.read(0);
        var luminance =
            new RGBLuminanceSource(
                width, height, image.getRGB(0, 0, width, height, null, 0, width));
        var bitmap = new BinaryBitmap(new HybridBinarizer(luminance));
        String payload;
        try {
          payload =
              new MultiFormatReader()
                  .decode(
                      bitmap,
                      Map.of(
                          DecodeHintType.POSSIBLE_FORMATS,
                          List.of(BarcodeFormat.QR_CODE),
                          DecodeHintType.TRY_HARDER,
                          true))
                  .getText();
        } catch (NotFoundException ex) {
          // qronly images can contain finder-like data; pure decoding still requires exact
          // CRC/binding below.
          payload =
              new MultiFormatReader()
                  .decode(
                      bitmap,
                      Map.of(
                          DecodeHintType.PURE_BARCODE,
                          true,
                          DecodeHintType.POSSIBLE_FORMATS,
                          List.of(BarcodeFormat.QR_CODE)))
                  .getText();
        }
        verifyPayload(payload, bin, account, amount, memo);
      } finally {
        reader.dispose();
      }
    }
  }

  static void verifyPayload(String value, String bin, String account, String amount, String memo)
      throws IOException {
    if (value == null
        || value.length() > 4096
        || !value.matches("[\\x20-\\x7e]+")
        || value.length() < 8
        || !value.substring(value.length() - 8, value.length() - 4).equals("6304")
        || !crc(value.substring(0, value.length() - 4))
            .equalsIgnoreCase(value.substring(value.length() - 4))) throw new IOException();
    var root = tags(value);
    var merchant = tags(root.get("38"));
    var recipient = tags(merchant.get("01"));
    var additional = tags(root.get("62"));
    try {
      if (!"01".equals(root.get("00"))
          || !"12".equals(root.get("01"))
          || !"A000000727".equals(merchant.get("00"))
          || !"QRIBFTTA".equals(merchant.get("02"))
          || !bin.equals(recipient.get("00"))
          || !account.equals(recipient.get("01"))
          || !"704".equals(root.get("53"))
          || !"VN".equals(root.get("58"))
          || new BigDecimal(amount).compareTo(new BigDecimal(root.get("54"))) != 0
          || !memo.equals(additional.get("08"))) throw new IOException();
    } catch (RuntimeException ex) {
      throw new IOException();
    }
  }

  private static Map<String, String> tags(String value) throws IOException {
    if (value == null) throw new IOException();
    var result = new HashMap<String, String>();
    for (int offset = 0; offset < value.length(); ) {
      if (offset + 4 > value.length() || !value.substring(offset, offset + 4).matches("[0-9]{4}"))
        throw new IOException();
      String tag = value.substring(offset, offset + 2);
      int size = Integer.parseInt(value.substring(offset + 2, offset + 4));
      if (offset + 4 + size > value.length()
          || result.putIfAbsent(tag, value.substring(offset + 4, offset + 4 + size)) != null)
        throw new IOException();
      offset += 4 + size;
    }
    return result;
  }

  private static String crc(String value) {
    int crc = 0xffff;
    for (byte part : value.getBytes(StandardCharsets.US_ASCII)) {
      crc ^= (part & 0xff) << 8;
      for (int bit = 0; bit < 8; bit++)
        crc = ((crc << 1) ^ ((crc & 0x8000) != 0 ? 0x1021 : 0)) & 0xffff;
    }
    return String.format(Locale.ROOT, "%04X", crc);
  }
}
