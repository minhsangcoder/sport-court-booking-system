package com.sporthub.booking;
import com.sporthub.booking.service.CheckinQr;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CheckinQrTest {
 @Test void downloadedPngCanBeScannedToTheExactInvitationUrl()throws Exception {
  String value="http://localhost:3000/customer/groups/join?code=signed-invitation";
  String data=new CheckinQr().pngDataUrl(value);var bytes=java.util.Base64.getDecoder().decode(data.substring(data.indexOf(',')+1));
  var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));int width=image.getWidth(),height=image.getHeight();int[] pixels=image.getRGB(0,0,width,height,null,0,width);
  var result=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width,height,pixels))));
  assertThat(result.getText()).isEqualTo(value);assertThat(width).isEqualTo(640);
 }
}
