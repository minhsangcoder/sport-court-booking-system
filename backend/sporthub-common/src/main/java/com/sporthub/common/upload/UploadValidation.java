package com.sporthub.common.upload;

import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Existing Facility signature/type/dimension limits, reused before signup persistence. */
public final class UploadValidation {
    private UploadValidation() {}
    public static void size(MultipartFile file,String label) {
        if(file==null||file.isEmpty()||file.getSize()>10L*1024*1024)
            throw new IllegalArgumentException(label+" must be between 1 byte and 10 MB");
    }
    public static String document(MultipartFile file) {
        size(file,"Document");
        try(var stream=file.getInputStream()) {
            if(new String(stream.readNBytes(5),StandardCharsets.US_ASCII).equals("%PDF-"))return "application/pdf";
        }catch(IOException ex){throw new IllegalArgumentException("Invalid document",ex);}
        return image(file);
    }
    public static String image(MultipartFile file) {
        size(file,"Image");
        try(var input=javax.imageio.ImageIO.createImageInputStream(file.getInputStream())) {
            var readers=javax.imageio.ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw new IllegalArgumentException("Only JPEG and PNG images, or PDF documents, are supported");
            var reader=readers.next();
            try {
                reader.setInput(input);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("png","jpeg","jpg").contains(format)||(long)reader.getWidth(0)*reader.getHeight(0)>20_000_000)
                    throw new IllegalArgumentException("Unsupported image or image dimensions exceed the limit");
                return format.equals("png")?"image/png":"image/jpeg";
            }finally{reader.dispose();}
        }catch(IOException ex){throw new IllegalArgumentException("Invalid image content",ex);}
    }
    public record FileData(String name,byte[] bytes) implements MultipartFile {
        public String getName(){return "file";}
        public String getOriginalFilename(){return name;}
        public String getContentType(){return "application/octet-stream";}
        public boolean isEmpty(){return bytes==null||bytes.length==0;}
        public long getSize(){return bytes==null?0:bytes.length;}
        public byte[] getBytes(){return bytes;}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
        public void transferTo(File dest)throws IOException{java.nio.file.Files.write(dest.toPath(),bytes);}
    }
}
