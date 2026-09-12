package com.lovehibachi.aichatbot.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SignatureVerifier {
    public boolean isValid(String secret, String signatureHeader, String body) {
        return isValid(secret, signatureHeader, body, "sha256=", "HmacSHA256");
    }

    /** Verifies Intercom's standard X-Hub-Signature webhook format. */
    public boolean isValidIntercomHubSignature(String clientSecret, String signatureHeader, String body) {
        return isValid(clientSecret, signatureHeader, body, "sha1=", "HmacSHA1");
    }

    private boolean isValid(String secret, String signatureHeader, String body,
                            String prefix, String algorithm) {
        if (secret == null || secret.trim().isEmpty() || signatureHeader == null || body == null) {
            return false;
        }
        String supplied = signatureHeader.startsWith(prefix)
                ? signatureHeader.substring(prefix.length()) : signatureHeader;
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            String expected = toHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    supplied.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            return false;
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) { value.append(String.format("%02x", current)); }
        return value.toString();
    }
}
