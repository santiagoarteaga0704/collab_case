package com.collabcase.runtime.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class RuntimeTokenService {
    private final SecureRandom random=new SecureRandom();
    public String generate(){byte[] bytes=new byte[32];random.nextBytes(bytes);return "cc_rt_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public String hash(String token){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public boolean matches(String token,String expectedHash){if(token==null||expectedHash==null)return false;return MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),expectedHash.getBytes(StandardCharsets.US_ASCII));}
}
