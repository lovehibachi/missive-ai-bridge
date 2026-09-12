package com.lovehibachi.aichatbot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.service.SignatureVerifier;
import com.lovehibachi.aichatbot.service.WebhookIntakeService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Receives externally generated events only; long-running work happens asynchronously. */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookController.class);

    private final SignatureVerifier signatureVerifier;
    private final WebhookIntakeService intakeService;
    private final BridgeProperties properties;
    private final ObjectMapper objectMapper;

    public WebhookController(SignatureVerifier signatureVerifier,
                             WebhookIntakeService intakeService,
                             BridgeProperties properties,
                             ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.intakeService = intakeService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/missive/inbound")
    public ResponseEntity<Void> missiveInbound(
            @RequestHeader(value = "X-Hook-Signature", required = false) String signature,
            @RequestBody String payload) {
        verify(properties.getMissive().getWebhookSecret(), signature, payload);
        JsonNode root = read(payload);
        String messageId = firstText(root.path("latest_message").path("id"), root.path("message").path("id"));
        require(messageId, "Missive message id");
        String eventType = root.path("rule").path("type").asText("incoming_twilio_chat_message");
        LOGGER.info("Received verified Missive webhook: eventType={}, messageId={}", eventType, messageId);
        intakeService.accept("missive", messageId, eventType, payload);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @PostMapping("/fin")
    public ResponseEntity<Void> fin(
            @RequestHeader(value = "X-Fin-Agent-API-Webhook-Signature", required = false) String finSignature,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String genericSignature,
            @RequestBody String payload) {
        verify(properties.getFin().getWebhookSecret(),
                !isBlank(finSignature) ? finSignature : genericSignature, payload);
        JsonNode root = read(payload);
        String eventName = root.path("event_name").asText();
        require(eventName, "Fin event_name");
        String eventId = firstText(root.path("id"), root.path("event_id"), root.path("message").path("id"));
        if (isBlank(eventId)) {
            eventId = eventName + ":" + sha256(payload);
        }
        LOGGER.info("Received verified Fin webhook: eventName={}, eventId={}", eventName, eventId);
        intakeService.accept("fin", eventId, eventName, payload);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private void verify(String secret, String signature, String payload) {
        if (!signatureVerifier.isValid(secret, signature, payload)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
    }

    private JsonNode read(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON payload", exception);
        }
    }

    private String firstText(JsonNode... values) {
        for (JsonNode value : values) {
            if (value != null && !isBlank(value.asText())) { return value.asText(); }
        }
        return null;
    }

    private void require(String value, String field) {
        if (isBlank(value)) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing " + field); }
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte part : hash) { result.append(String.format("%02x", part & 0xff)); }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash webhook payload", exception);
        }
    }

    private boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
