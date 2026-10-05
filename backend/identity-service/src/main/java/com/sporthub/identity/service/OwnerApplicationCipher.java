package com.sporthub.identity.service;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import com.sporthub.identity.exception.IdentityException;
import org.springframework.http.HttpStatus;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/** Identity-owned legal data. The deployment key is separate from JWT and service-call keys. */
@Component
public class OwnerApplicationCipher {
 private final String configuredKey;
 public OwnerApplicationCipher(@Value("${OWNER_APPLICATION_DATA_KEY:}") String key){configuredKey=key;}
 public String seal(String plain,java.util.UUID application){try{byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);var cipher=cipher(Cipher.ENCRYPT_MODE,nonce);cipher.updateAAD(application.toString().getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));}catch(IdentityException ex){throw ex;}catch(Exception ex){throw new IllegalStateException("Could not protect application data",ex);}}
 public String open(String encrypted,java.util.UUID application){try{String[] parts=encrypted.split("\\.",2);var cipher=cipher(Cipher.DECRYPT_MODE,Base64.getDecoder().decode(parts[0]));cipher.updateAAD(application.toString().getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);}catch(IdentityException ex){throw ex;}catch(Exception ex){throw new IllegalStateException("Could not read protected application data",ex);}}
 private Cipher cipher(int mode,byte[] nonce)throws Exception{byte[] key;try{key=Base64.getDecoder().decode(configuredKey);if(key.length!=32)throw new IllegalArgumentException();}catch(Exception ex){throw new IdentityException(HttpStatus.SERVICE_UNAVAILABLE,"IDENTITY-APPLICATION-KEY","Owner application data key must be configured");}var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));return cipher;}
}
