package com.lovehibachi.aichatbot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.service.SignatureVerifier;
import com.lovehibachi.aichatbot.service.WebChatService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Receives Missive Custom Channel deliveries for Fin and human-agent replies. */
@RestController
@RequestMapping("/webhooks/missive")
public class MissiveCustomChannelWebhookController {
    private static final Logger LOGGER = LoggerFactory.getLogger(MissiveCustomChannelWebhookController.class);
    private final SignatureVerifier signatureVerifier;
    private final BridgeProperties properties;
    private final ObjectMapper objectMapper;
    private final WebChatService webChatService;

    public MissiveCustomChannelWebhookController(SignatureVerifier signatureVerifier,
                                                 BridgeProperties properties,
                                                 ObjectMapper objectMapper,
                                                 WebChatService webChatService) {
        this.signatureVerifier = signatureVerifier;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webChatService = webChatService;
    }

    @PostMapping("/custom-channel")
    public ResponseEntity<Void> outbound(
            @RequestHeader(value = "X-Hook-Signature", required = false) String signature,
            @RequestBody String payload) {
        if (!signatureVerifier.isValid(properties.getMissive().getCustomChannelWebhookSecret(), signature, payload)) {
            LOGGER.warn("Rejected Custom Channel webhook signature: signatureHeaderPresent={}, payloadBytes={}",
                    signature != null && !signature.trim().isEmpty(), payload.getBytes(StandardCharsets.UTF_8).length);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
        JsonNode root = read(payload);
        JsonNode message = message(root);
        // The outgoing webhook uses root.conversation.id. Other Missive endpoint
        // payloads may put a scalar or object conversation under the message.
        String conversationId = firstText(
                // Official Custom Channel outgoing payload: root.conversation.id.
                root.path("conversation").path("id"), root.path("conversation_id"), root.path("conversation"),
                // Accept response/incoming-message shapes as well.
                message.path("conversation").path("id"), message.path("conversation_id"), message.path("conversation"));
        String messageId = firstText(message.path("id"), message.path("external_id"));
        String body = firstText(message.path("body"), message.path("text"), message.path("preview"));
        if (isBlank(conversationId) || isBlank(messageId) || isBlank(body)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Custom Channel webhook has no message, conversation, or body");
        }
        webChatService.receiveOutboundCustomChannelMessage(conversationId, "custom:" + messageId, body,
                messageCreatedAt(message));
        LOGGER.info("Accepted Custom Channel outbound message: missiveConversationId={}, messageId={}", conversationId, messageId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private JsonNode read(String payload) {
        try { return objectMapper.readTree(payload); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON", exception); }
    }
    private JsonNode message(JsonNode root) {
        if (root.path("message").isObject()) { return root.path("message"); }
        if (root.path("messages").isArray() && root.path("messages").size() > 0) { return root.path("messages").get(0); }
        if (root.path("messages").isObject()) { return root.path("messages"); }
        return root;
    }
    private String firstText(JsonNode... values) {
        for (JsonNode value : values) { if (value != null && !isBlank(value.asText())) { return value.asText(); } }
        return null;
    }
    private boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
    private Instant messageCreatedAt(JsonNode message) {
        long seconds = message.path("created_at").asLong(0L);
        return seconds > 0L ? Instant.ofEpochSecond(seconds) : Instant.now();
    }
}
