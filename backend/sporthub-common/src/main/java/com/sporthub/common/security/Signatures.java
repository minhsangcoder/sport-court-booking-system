package com.sporthub.common.security;

import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

public final class Signatures {
    private Signatures() {}
    public static String sha256(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(GeneralSecurityException ex) {throw new IllegalStateException(ex);}
    }
    public static String hmac(String secret,String value) {
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}
        catch(GeneralSecurityException ex) {throw new IllegalStateException(ex);}
    }
    public static boolean matches(String expected,String actual) {
        return actual!=null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));
    }
}
