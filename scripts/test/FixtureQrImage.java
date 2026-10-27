import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Loopback test fixture only; uses the project's existing ZXing dependency. */
public class FixtureQrImage {
  public static void main(String[] args) throws Exception {
    if (args.length != 1 || args[0].length() > 4096)
      throw new IllegalArgumentException("QR payload required");
    var matrix = new QRCodeWriter().encode(args[0], BarcodeFormat.QR_CODE, 480, 480);
    var image = new BufferedImage(480, 480, BufferedImage.TYPE_BYTE_BINARY);
    for (int y = 0; y < 480; y++)
      for (int x = 0; x < 480; x++) image.setRGB(x, y, matrix.get(x, y) ? 0xff000000 : 0xffffffff);
    ImageIO.write(image, "png", System.out);
  }
}
