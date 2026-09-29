package com.lovehibachi.aichatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.config.BridgeProperties;
import java.util.Iterator;
import org.springframework.stereotype.Component;

@Component
public class MissiveInboundMessage {
    private final ObjectMapper objectMapper;
    private final BridgeProperties properties;

    public MissiveInboundMessage(ObjectMapper objectMapper, BridgeProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Snapshot parse(String rawPayload) throws Exception {
        JsonNode root = objectMapper.readTree(rawPayload);
        JsonNode conversation = root.path("conversation");
        // Missive generic webhooks commonly use latest_message, while native Live
        // Chat deliveries put the incoming message directly in message.
        JsonNode message = root.hasNonNull("message") ? root.path("message") : root.path("latest_message");
        String conversationId = required(conversation.path("id"), "conversation.id");
        String messageId = required(message.path("id"), "message.id");
        JsonNode from = message.path("from_field");
        String senderId = text(from.path("id"));
        String username = text(from.path("username"));
        String name = text(from.path("name"));
        String visitorId = senderId != null ? "missive:" + senderId
                : username != null ? "missive:" + username
                : "anonymous-conversation:" + conversationId;
        String body = firstText(message.path("body"), message.path("text"), message.path("preview"));
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("Missive inbound event has no message body; verify live chat webhook payload during POC");
        }
        JsonNode target = objectMapper.createObjectNode()
                .put("id", senderId == null ? visitorId : senderId)
                .put("username", username == null ? "" : username)
                .put("name", name == null ? (username == null ? "Visitor" : username) : name);
        String accountId = firstText(message.path("account").path("id"), root.path("account").path("id"));
        if (accountId == null || accountId.isEmpty()) { accountId = properties.getMissive().getLiveChatAccountId(); }
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new IllegalStateException("Missing MISSIVE_LIVE_CHAT_ACCOUNT_ID");
        }
        String greetingKind = text(root.path("web_chat").path("greeting_kind"));
        if (!"first".equals(greetingKind) && !"returning".equals(greetingKind)) { greetingKind = null; }
        String greetingLanguage = text(root.path("web_chat").path("greeting_language"));
        if (!"zh".equals(greetingLanguage)) { greetingLanguage = "en"; }
        return new Snapshot(conversationId, messageId, visitorId, body,
                objectMapper.writeValueAsString(objectMapper.createArrayNode().add(target)), accountId, greetingKind, greetingLanguage);
    }

    private String required(JsonNode node, String field) {
        String value = text(node);
        if (value == null || value.isEmpty()) { throw new IllegalArgumentException("Missing " + field); }
        return value;
    }
    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) { String value = text(node); if (value != null && !value.isEmpty()) { return value; } }
        return null;
    }
    private String text(JsonNode node) { return node == null || node.isMissingNode() || node.isNull() ? null : node.asText(); }

    public static class Snapshot {
        private final String conversationId;
        private final String messageId;
        private final String visitorId;
        private final String body;
        private final String toFieldsJson;
        private final String accountId;
        private final String webChatGreetingKind;
        private final String webChatGreetingLanguage;
        Snapshot(String conversationId, String messageId, String visitorId, String body, String toFieldsJson, String accountId) {
            this(conversationId, messageId, visitorId, body, toFieldsJson, accountId, null);
        }
        Snapshot(String conversationId, String messageId, String visitorId, String body, String toFieldsJson,
                 String accountId, String webChatGreetingKind) {
            this(conversationId, messageId, visitorId, body, toFieldsJson, accountId, webChatGreetingKind, "en");
        }
        Snapshot(String conversationId, String messageId, String visitorId, String body, String toFieldsJson,
                 String accountId, String webChatGreetingKind, String webChatGreetingLanguage) {
            this.conversationId = conversationId; this.messageId = messageId; this.visitorId = visitorId;
            this.body = body; this.toFieldsJson = toFieldsJson; this.accountId = accountId;
            this.webChatGreetingKind = webChatGreetingKind;
            this.webChatGreetingLanguage = webChatGreetingLanguage;
        }
        public String getConversationId() { return conversationId; }
        public String getMessageId() { return messageId; }
        public String getVisitorId() { return visitorId; }
        public String getBody() { return body; }
        public String getToFieldsJson() { return toFieldsJson; }
        public String getAccountId() { return accountId; }
        public String getWebChatGreetingKind() { return webChatGreetingKind; }
        public String getWebChatGreetingLanguage() { return webChatGreetingLanguage; }
    }
}
