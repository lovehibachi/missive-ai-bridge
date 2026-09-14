package com.lovehibachi.aichatbot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.service.FinReplyTurnGate;
import com.lovehibachi.aichatbot.service.SignatureVerifier;
import com.lovehibachi.aichatbot.service.WebhookIntakeService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Receives externally generated events only; long-running work happens asynchronously. */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookController.class);

    private final SignatureVerifier signatureVerifier;
    private final WebhookIntakeService intakeService;
    private final FinReplyTurnGate finReplyTurnGate;
    private final BridgeProperties properties;
    private final ObjectMapper objectMapper;

    public WebhookController(SignatureVerifier signatureVerifier,
                             WebhookIntakeService intakeService,
                             FinReplyTurnGate finReplyTurnGate,
                             BridgeProperties properties,
                             ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.intakeService = intakeService;
        this.finReplyTurnGate = finReplyTurnGate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/missive/inbound")
    public ResponseEntity<Void> missiveInbound(
            @RequestHeader(value = "X-Hook-Signature", required = false) String signature,
            @RequestBody String payload) {
        verify("Missive", properties.getMissive().getWebhookSecret(), signature, payload);
        JsonNode root = read(payload);
        String messageId = firstText(root.path("latest_message").path("id"), root.path("message").path("id"));
        require(messageId, "Missive message id");
        String eventType = root.path("rule").path("type").asText("incoming_twilio_chat_message");
        LOGGER.info("Received verified Missive webhook: eventType={}, messageId={}", eventType, messageId);
        intakeService.accept("missive", messageId, eventType, payload);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /** Intercom probes configured Fin callback URLs with HEAD before sending signed events. */
    @RequestMapping(value = "/fin", method = RequestMethod.HEAD)
    public ResponseEntity<Void> finProbe() {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/fin")
    public ResponseEntity<Void> fin(
            @RequestHeader(value = "X-Fin-Agent-API-Webhook-Signature", required = false) String finSignature,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String genericSignature,
            @RequestHeader(value = "X-Hub-Signature", required = false) String hubSignature,
            HttpServletRequest request,
            @RequestBody String payload) {
        String signature = !isBlank(finSignature) ? finSignature : genericSignature;
        boolean valid = !isBlank(hubSignature)
                ? signatureVerifier.isValidIntercomHubSignature(
                        properties.getFin().getClientSecret(), hubSignature, payload)
                : signatureVerifier.isValid(properties.getFin().getWebhookSecret(), signature, payload);
        if (!valid) {
            LOGGER.warn("Rejected Fin webhook signature: finHeaderPresent={}, genericHeaderPresent={}, hubHeaderPresent={}, "
                            + "signatureHeaderNames={}, payloadBytes={}",
                    !isBlank(finSignature), !isBlank(genericSignature), !isBlank(hubSignature), signatureHeaderNames(request),
                    payload.getBytes(StandardCharsets.UTF_8).length);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
        JsonNode root = read(payload);
        String eventName = root.path("event_name").asText();
        // The Intercom settings-page test uses a signed generic webhook payload,
        // while actual Fin Agent events always carry event_name. Acknowledging the
        // test after signature verification proves the endpoint is reachable and
        // does not enqueue it as a Fin conversation event.
        if (isBlank(eventName)) {
            LOGGER.info("Accepted verified non-Fin webhook test event: rootFields={}", rootFieldNames(root));
            return ResponseEntity.ok().build();
        }
        String eventId = firstText(root.path("id"), root.path("event_id"), root.path("message").path("id"));
        if (isBlank(eventId)) {
            eventId = eventName + ":" + sha256(payload);
        }
        if ("fin_replied".equals(eventName)) {
            String finConversationId = root.path("conversation_id").asText();
            if (!isBlank(finConversationId) && !finReplyTurnGate.claimFirstReply(finConversationId)) {
                /*
                 * Claim at receipt time, before asynchronous persistence/processing.
                 * This makes arrival order decisive even when worker scheduling changes.
                 */
                LOGGER.info("Ignored later Fin reply at webhook intake: eventId={}, finConversationId={}",
                        eventId, finConversationId);
                return ResponseEntity.status(HttpStatus.ACCEPTED).build();
            }
        }
        LOGGER.info("Received verified Fin webhook: eventName={}, eventId={}", eventName, eventId);
        intakeService.accept("fin", eventId, eventName, payload);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private void verify(String provider, String secret, String signature, String payload) {
        if (!signatureVerifier.isValid(secret, signature, payload)) {
            LOGGER.warn("Rejected {} webhook signature: signatureHeaderPresent={}, payloadBytes={}",
                    provider, !isBlank(signature), payload == null ? 0 : payload.getBytes(StandardCharsets.UTF_8).length);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
    }

    private String signatureHeaderNames(HttpServletRequest request) {
        return Collections.list(request.getHeaderNames()).stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains("signature"))
                .collect(Collectors.joining(","));
    }

    private String rootFieldNames(JsonNode root) {
        StringBuilder result = new StringBuilder();
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            if (result.length() > 0) { result.append(','); }
            result.append(names.next());
        }
        return result.toString();
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
