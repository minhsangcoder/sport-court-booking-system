package com.sporthub.booking.service;
import org.springframework.stereotype.Component;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
@Component
public class CheckinQr {
 public String pngDataUrl(String value){try{var matrix=new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,640,640);var image=new java.awt.image.BufferedImage(640,640,java.awt.image.BufferedImage.TYPE_BYTE_BINARY);for(int y=0;y<640;y++)for(int x=0;x<640;x++)image.setRGB(x,y,matrix.get(x,y)?0xFF000000:0xFFFFFFFF);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);return "data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());}catch(Exception ex){throw new IllegalStateException("Could not create QR download",ex);}}
 public String svg(String token){try{var matrix=new QRCodeWriter().encode(token,BarcodeFormat.QR_CODE,320,320);var path=new StringBuilder();for(int y=0;y<matrix.getHeight();y++)for(int x=0;x<matrix.getWidth();x++)if(matrix.get(x,y))path.append("M").append(x).append(' ').append(y).append("h1v1h-1z");return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 320 320\"><rect width=\"320\" height=\"320\" fill=\"white\"/><path d=\""+path+"\" fill=\"black\"/></svg>";}catch(com.google.zxing.WriterException ex){throw new IllegalStateException("Could not create check-in QR",ex);}}
}
