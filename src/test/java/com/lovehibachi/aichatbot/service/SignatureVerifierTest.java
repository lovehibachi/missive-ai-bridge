package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {
    private final SignatureVerifier verifier = new SignatureVerifier();

    @Test
    void acceptsAValidSha256Signature() throws Exception {
        String secret = "test-secret";
        String body = "{\"event_name\":\"fin_replied\"}";
        assertTrue(verifier.isValid(secret, "sha256=" + hmac(secret, body), body));
    }

    @Test
    void rejectsModifiedBodyAndMissingSecret() throws Exception {
        String body = "{\"event_name\":\"fin_replied\"}";
        String signature = hmac("test-secret", body);
        assertFalse(verifier.isValid("test-secret", signature, "{\"event_name\":\"changed\"}"));
        assertFalse(verifier.isValid("", signature, body));
    }

    @Test
    void acceptsAValidIntercomHubSignature() throws Exception {
        String secret = "intercom-client-secret";
        String body = "{\"event_name\":\"fin_replied\"}";
        assertTrue(verifier.isValidIntercomHubSignature(secret,
                "sha1=" + hmac("HmacSHA1", secret, body), body));
    }

    private String hmac(String secret, String body) throws Exception {
        return hmac("HmacSHA256", secret, body);
    }

    private String hmac(String algorithm, String secret, String body) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        byte[] bytes = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) { hex.append(String.format("%02x", value & 0xff)); }
        return hex.toString();
    }
}
