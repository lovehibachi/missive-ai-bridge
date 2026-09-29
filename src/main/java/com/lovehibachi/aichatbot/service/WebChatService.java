package com.lovehibachi.aichatbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.ChatMessage;
import com.lovehibachi.aichatbot.domain.ConversationState;
import com.lovehibachi.aichatbot.repository.ChatConversationRepository;
import com.lovehibachi.aichatbot.repository.ChatMessageRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.time.ZoneId;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Browser-facing message operations for the first-party Custom Channel UI. */
@Service
public class WebChatService {
    private static final int MAX_MESSAGE_LENGTH = 8000;
    // This product only needs a Chinese-versus-English greeting choice. Do not
    // introduce a probabilistic language detector: any Han character means the
    // visitor receives Chinese copy; every other message receives English copy.
    private static final Pattern HAN_CHARACTER = Pattern.compile("[\\u3400-\\u4DBF\\u4E00-\\u9FFF]");
    private static final Safelist WEB_CHAT_HTML = Safelist.none().addTags("p", "br", "strong", "b", "em", "i",
            "ul", "ol", "li", "blockquote", "code", "pre", "h1", "h2", "h3", "h4", "a")
            .addAttributes("a", "href", "target", "rel")
            .addProtocols("a", "href", "https");
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final MissiveClient missiveClient;
    private final HandoffService handoffService;
    private final WebhookIntakeService intakeService;
    private final WebChatNotifier notifier;
    private final ObjectMapper objectMapper;
    private final BridgeProperties properties;

    public WebChatService(ChatConversationRepository conversationRepository,
                          ChatMessageRepository messageRepository,
                          MissiveClient missiveClient,
                          HandoffService handoffService,
                          WebhookIntakeService intakeService,
                          WebChatNotifier notifier,
                          ObjectMapper objectMapper,
                          BridgeProperties properties) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.missiveClient = missiveClient;
        this.handoffService = handoffService;
        this.intakeService = intakeService;
        this.notifier = notifier;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** Sends a browser visitor message to Missive, then asynchronously starts or continues Fin. */
    @Transactional
    public void receiveVisitorMessage(String sessionToken, String clientMessageId, String body, String browserTimezone) {
        validateSession(sessionToken);
        String plainBody = normalizeVisitorBody(body);
        String externalId = isBlank(clientMessageId) ? "web:" + UUID.randomUUID().toString() : "web:" + clientMessageId;
        ChatConversation existing = conversationRepository.findByWebChatSessionToken(sessionToken).orElse(null);
        String greetingKind = greetingKind(existing);
        String greetingLanguage = containsChinese(plainBody) ? "zh" : "en";
        MissiveClient.CustomChannelMessageReceipt receipt = missiveClient.receiveCustomChannelMessage(
                sessionToken, escapeHtml(plainBody), externalId,
                existing == null ? null : existing.getMissiveConversationId());
        ChatConversation conversation = existing == null ? new ChatConversation() : existing;
        if (existing == null) {
            conversation.setMissiveConversationId(receipt.getConversationId());
            conversation.setLiveChatAccountId(properties.getMissive().getCustomChannelAccountId());
            conversation.setFinVisitorId("missive:" + sessionToken);
            conversation.setVisitorToFields(visitorToFields(sessionToken));
            conversation.setWebChatSessionToken(sessionToken);
            conversation.setState(ConversationState.AI_HANDLING);
        }
        String timezone = normalizeTimezone(browserTimezone);
        if (timezone != null) { conversation.setWebChatTimezone(timezone); }
        conversation.setLastMissiveMessageId(receipt.getMessageId());
        conversationRepository.save(conversation);
        persistIfAbsent(conversation, receipt.getMessageId(), "user", escapeHtml(plainBody));
        final String payload = syntheticInboundPayload(receipt.getConversationId(), receipt.getMessageId(), sessionToken,
                plainBody, greetingKind, greetingLanguage);
        afterCommit(() -> {
            // The async Fin worker must not race the transaction that created the
            // conversation and visitor message. It can safely query them now.
            intakeService.accept("missive", receipt.getMessageId(), "custom_channel_visitor_message", payload);
            notifier.notifyMessage(sessionToken);
        });
    }

    @Transactional(readOnly = true)
    public List<WebChatMessage> messages(String sessionToken, String afterMessageId) {
        validateSession(sessionToken);
        ChatConversation conversation = conversationRepository.findByWebChatSessionToken(sessionToken).orElse(null);
        if (conversation == null) { return new ArrayList<WebChatMessage>(); }
        List<ChatMessage> records = messageRepository.findTop200ByConversation_IdOrderByCreatedAtAsc(conversation.getId());
        List<WebChatMessage> result = new ArrayList<WebChatMessage>();
        boolean markerFound = isBlank(afterMessageId);
        if (!markerFound) {
            for (ChatMessage record : records) {
                if (afterMessageId.equals(record.getId())) { markerFound = true; break; }
            }
        }
        boolean include = isBlank(afterMessageId);
        if (!markerFound) { include = true; }
        for (ChatMessage record : records) {
            if (include) { result.add(toWebMessage(record)); }
            if (!include && afterMessageId.equals(record.getId())) { include = true; }
        }
        return result;
    }

    @Transactional
    public void receiveOutboundCustomChannelMessage(String missiveConversationId, String externalMessageId, String body) {
        ChatConversation conversation = conversationRepository.findByMissiveConversationId(missiveConversationId).orElse(null);
        if (conversation == null || isBlank(conversation.getWebChatSessionToken())) {
            return;
        }
        persistIfAbsent(conversation, externalMessageId, "agent", sanitizeHtml(body));
        final String sessionToken = conversation.getWebChatSessionToken();
        afterCommit(() -> notifier.notifyMessage(sessionToken));
    }

    /** Explicit visitor action; it never exposes a Missive conversation ID to the browser. */
    @Transactional
    public void requestHuman(String sessionToken) {
        validateSession(sessionToken);
        ChatConversation conversation = conversationRepository.findByWebChatSessionToken(sessionToken)
                .orElseThrow(() -> new IllegalArgumentException("Start a chat before requesting a human"));
        handoffService.requestHuman(conversation, "Visitor requested a human from website chat");
    }

    private void persistIfAbsent(ChatConversation conversation, String externalMessageId, String author, String body) {
        if (messageRepository.existsByExternalMessageId(externalMessageId)) { return; }
        ChatMessage message = new ChatMessage();
        message.setConversation(conversation);
        message.setExternalMessageId(externalMessageId);
        message.setAuthor(author);
        message.setBody(body);
        messageRepository.save(message);
    }

    private WebChatMessage toWebMessage(ChatMessage message) {
        return new WebChatMessage(message.getId(), message.getAuthor(), sanitizeHtml(message.getBody()), message.getCreatedAt().toString());
    }

    private String syntheticInboundPayload(String conversationId, String messageId, String sessionToken, String body,
                                           String greetingKind, String greetingLanguage) {
        try {
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("conversation", java.util.Collections.singletonMap("id", conversationId));
            Map<String, Object> message = new LinkedHashMap<String, Object>();
            message.put("id", messageId);
            message.put("body", body);
            message.put("account", java.util.Collections.singletonMap("id", properties.getMissive().getCustomChannelAccountId()));
            Map<String, Object> from = new LinkedHashMap<String, Object>();
            from.put("id", sessionToken);
            from.put("username", "visitor-" + sessionToken.substring(0, Math.min(12, sessionToken.length())));
            from.put("name", "Website visitor");
            message.put("from_field", from);
            root.put("message", message);
            if (greetingKind != null) {
                Map<String, String> webChat = new LinkedHashMap<String, String>();
                webChat.put("greeting_kind", greetingKind);
                webChat.put("greeting_language", greetingLanguage);
                root.put("web_chat", webChat);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize Custom Channel inbound message", exception);
        }
    }

    private String visitorToFields(String sessionToken) {
        try {
            Map<String, String> visitor = new LinkedHashMap<String, String>();
            visitor.put("id", sessionToken);
            visitor.put("username", "visitor-" + sessionToken.substring(0, Math.min(12, sessionToken.length())));
            visitor.put("name", "Website visitor");
            return objectMapper.writeValueAsString(java.util.Collections.singletonList(visitor));
        } catch (Exception exception) { throw new IllegalStateException("Unable to serialize visitor", exception); }
    }

    private String normalizeVisitorBody(String value) {
        if (value == null) { throw new IllegalArgumentException("Message body is required"); }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) { throw new IllegalArgumentException("Message body is required"); }
        if (trimmed.length() > MAX_MESSAGE_LENGTH) { throw new IllegalArgumentException("Message is too long"); }
        return trimmed;
    }
    private String greetingKind(ChatConversation conversation) {
        if (conversation == null) { return "first"; }
        ChatMessage latest = messageRepository.findTopByConversation_IdOrderByCreatedAtDesc(conversation.getId()).orElse(null);
        if (latest == null) { return "first"; }
        return latest.getCreatedAt().isBefore(Instant.now().minusSeconds(3 * 60 * 60)) ? "returning" : null;
    }
    private boolean containsChinese(String value) { return HAN_CHARACTER.matcher(value).find(); }
    private String normalizeTimezone(String value) {
        if (isBlank(value)) { return null; }
        try { return ZoneId.of(value.trim()).getId(); }
        catch (Exception ignored) { return null; }
    }
    private String sanitizeHtml(String value) {
        String clean = Jsoup.clean(value == null ? "" : value, "", WEB_CHAT_HTML);
        Document document = Jsoup.parseBodyFragment(clean);
        document.select("a").attr("target", "_blank").attr("rel", "noopener noreferrer");
        return document.body().html();
    }
    private String escapeHtml(String value) { return org.jsoup.parser.Parser.unescapeEntities(value, false).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private void validateSession(String token) {
        if (isBlank(token) || token.length() < 32 || token.length() > 128) { throw new IllegalArgumentException("Invalid chat session"); }
    }
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
    private boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }

    public static class WebChatMessage {
        private final String id;
        private final String author;
        private final String body;
        private final String createdAt;
        WebChatMessage(String id, String author, String body, String createdAt) {
            this.id = id; this.author = author; this.body = body; this.createdAt = createdAt;
        }
        public String getId() { return id; }
        public String getAuthor() { return author; }
        public String getBody() { return body; }
        public String getCreatedAt() { return createdAt; }
    }
}
