package com.lovehibachi.aichatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Component
public class MissiveClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(MissiveClient.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final BridgeProperties properties;

    public MissiveClient(RestTemplate restTemplate, ObjectMapper objectMapper, BridgeProperties properties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void sendFinReply(ChatConversation conversation, String htmlBody) {
        sendCustomerReply(conversation, htmlBody, "send_fin_reply");
    }

    /**
     * Receives a visitor message for the Missive Custom Channel. The browser never
     * calls Missive directly, so the workspace token remains server-side.
     */
    public CustomChannelMessageReceipt receiveCustomChannelMessage(String sessionToken, String body,
                                                                    String clientMessageId,
                                                                    String existingConversationId) {
        requireCustomChannelConfiguration();
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("account", properties.getMissive().getCustomChannelAccountId());
        message.put("body", body);
        message.put("external_id", clientMessageId);
        Map<String, Object> from = new LinkedHashMap<String, Object>();
        from.put("id", sessionToken);
        from.put("username", "visitor-" + shortToken(sessionToken));
        from.put("name", "Website visitor");
        message.put("from_field", from);
        Map<String, Object> recipient = new LinkedHashMap<String, Object>();
        recipient.put("id", properties.getMissive().getCustomChannelRecipientId());
        recipient.put("username", properties.getMissive().getCustomChannelRecipientUsername());
        recipient.put("name", properties.getMissive().getCustomChannelRecipientName());
        List<Map<String, Object>> recipients = new ArrayList<Map<String, Object>>();
        recipients.add(recipient);
        message.put("to_fields", recipients);
        if (!isBlank(existingConversationId)) { message.put("conversation", existingConversationId); }
        if (!isBlank(properties.getMissive().getAiTeamId())) {
            message.put("team", properties.getMissive().getAiTeamId());
        }
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("messages", Collections.singletonList(message));
        String response = postForBody("receive_custom_channel_message", "/v1/messages", request,
                existingConversationId == null ? "new" : existingConversationId);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode returned = root.path("messages").isArray() && root.path("messages").size() > 0
                    ? root.path("messages").get(0) : root.path("message");
            String messageId = text(returned.path("id"));
            String conversationId = text(returned.path("conversation").path("id"));
            if (isBlank(conversationId)) { conversationId = text(root.path("conversation").path("id")); }
            if (isBlank(messageId) || isBlank(conversationId)) {
                throw new IllegalStateException("Missive Custom Channel response omitted message or conversation id");
            }
            return new CustomChannelMessageReceipt(messageId, conversationId);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read Missive Custom Channel response", exception);
        }
    }

    public boolean usesCustomChannel(ChatConversation conversation) {
        return conversation != null && !isBlank(properties.getMissive().getCustomChannelAccountId())
                && properties.getMissive().getCustomChannelAccountId().equals(conversation.getLiveChatAccountId());
    }

    /**
     * Sends the visitor-facing acknowledgement after the bridge has transferred a
     * conversation to the support team. It uses the same Live Chat account and
     * recipient fields as a normal Fin reply, so it continues in the existing
     * customer chat rather than creating a new conversation.
     */
    public void sendHumanHandoffAcknowledgement(ChatConversation conversation) {
        sendCustomerReply(conversation,
                "<p>We’re connecting you with a member of our team. Please hold on.</p>",
                "send_human_handoff_acknowledgement");
    }

    /**
     * Sends a single customer-facing follow-up after an unanswered Fin reply.
     * Missive Live Chat strips HTML anchors in API-sent messages, so use its
     * text-link syntax. The visible link label avoids exposing a long campaign
     * URL in the visitor's chat window.
     */
    public void sendLowPeakFollowUp(ChatConversation conversation) {
        String bookingUrl = properties.getPromotions().getLowPeakBookingUrl();
        if (isBlank(bookingUrl)) { throw new IllegalStateException("Missing LOW_PEAK_BOOKING_URL"); }
        String body = "<p>We have special offers for non-peak weekend times. You can click "
                + "{{ link:" + bookingUrl + " here }} to submit a booking request.</p>";
        sendCustomerReply(conversation, body, "send_low_peak_follow_up");
    }

    private void sendCustomerReply(ChatConversation conversation, String htmlBody, String operation) {
        try {
            Map<String, Object> draft = new LinkedHashMap<String, Object>();
            draft.put("account", conversation.getLiveChatAccountId());
            draft.put("conversation", conversation.getMissiveConversationId());
            JsonNode toFields = objectMapper.readTree(conversation.getVisitorToFields());
            draft.put("to_fields", objectMapper.convertValue(toFields, Object.class));
            draft.put("body", htmlBody);
            draft.put("send", true);
            Map<String, Object> request = new LinkedHashMap<String, Object>();
            request.put("drafts", draft);
            post(operation, "/v1/drafts", request, conversation.getMissiveConversationId());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to build Missive Draft request", exception);
        }
    }

    public void createHandoffPost(ChatConversation conversation, String reason) {
        if (isBlank(properties.getMissive().getHandoffTeamId())) {
            throw new IllegalStateException("Missing MISSIVE_HANDOFF_TEAM_ID");
        }
        Map<String, Object> post = new LinkedHashMap<String, Object>();
        post.put("conversation", conversation.getMissiveConversationId());
        // Missive requires organization when applying shared labels. This must be
        // the organization accessible to the configured AI API token.
        post.put("organization", properties.getMissive().getOrganizationId());
        post.put("username", "Fin AI");
        post.put("markdown", "## 🤖 Fin 请求人工介入\n\n原因：" + safeReason(reason));
        Map<String, Object> notification = new LinkedHashMap<String, Object>();
        notification.put("title", "需要人工介入");
        notification.put("body", safeReason(reason));
        post.put("notification", notification);
        post.put("add_shared_labels", java.util.Collections.singletonList(properties.getMissive().getNeedHumanLabelId()));
        // Keep the existing conversation and its full Live Chat history, but
        // move it to the dedicated handoff Team Inbox. force_team is required
        // because the conversation is initially attached to the AI Chat team.
        // Do not set add_assignees: agents claim the unassigned conversation
        // manually from that shared inbox.
        post.put("team", properties.getMissive().getHandoffTeamId());
        post.put("force_team", true);
        // Do not set Missive's reopen flag: true means keep a closed conversation
        // closed when adding the post. add_to_inbox below makes it actionable.
        post.put("add_to_inbox", true);
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("posts", post);
        post("create_handoff_post", "/v1/posts", request, conversation.getMissiveConversationId());
    }

    /**
     * Moves an escalated conversation back to the AI Team Inbox and clears the
     * shared label that identifies it as requiring a human. This is an internal
     * Missive post, so the visitor does not receive another chat message.
     */
    public void resumeAiHandling(ChatConversation conversation) {
        if (isBlank(properties.getMissive().getAiTeamId())) {
            throw new IllegalStateException("Missing MISSIVE_AI_TEAM_ID");
        }
        if (isBlank(properties.getMissive().getOrganizationId())) {
            throw new IllegalStateException("Missing MISSIVE_ORGANIZATION_ID");
        }
        if (isBlank(properties.getMissive().getNeedHumanLabelId())) {
            throw new IllegalStateException("Missing MISSIVE_NEED_HUMAN_LABEL_ID");
        }
        Map<String, Object> post = new LinkedHashMap<String, Object>();
        post.put("conversation", conversation.getMissiveConversationId());
        post.put("organization", properties.getMissive().getOrganizationId());
        post.put("username", "Fin AI");
        post.put("markdown", "🤖 已恢复由 Fin AI 处理");
        // Missive validates every Post as a notification, including an internal
        // post used only to move a conversation between Team Inboxes.
        Map<String, Object> notification = new LinkedHashMap<String, Object>();
        notification.put("title", "已恢复 AI 处理");
        notification.put("body", "后续客户消息将由 Fin AI 处理。");
        post.put("notification", notification);
        post.put("remove_shared_labels", java.util.Collections.singletonList(properties.getMissive().getNeedHumanLabelId()));
        post.put("team", properties.getMissive().getAiTeamId());
        post.put("force_team", true);
        post.put("add_to_inbox", true);
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("posts", post);
        post("resume_ai_handling", "/v1/posts", request, conversation.getMissiveConversationId());
    }

    private void post(String operation, String path, Map<String, Object> body, String missiveConversationId) {
        postForBody(operation, path, body, missiveConversationId);
    }

    private String postForBody(String operation, String path, Map<String, Object> body, String missiveConversationId) {
        if (isBlank(properties.getMissive().getFinAiPat())) { throw new IllegalStateException("Missing MISSIVE_FIN_AI_PAT"); }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getMissive().getFinAiPat());
        headers.setContentType(MediaType.APPLICATION_JSON);
        long startedAt = System.nanoTime();
        LOGGER.info("Calling Missive API: operation={}, path={}, missiveConversationId={}",
                operation, path, missiveConversationId);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(properties.getMissive().getApiBaseUrl() + path,
                    new HttpEntity<Map<String, Object>>(body, headers), String.class);
            LOGGER.info("Missive API call succeeded: operation={}, path={}, missiveConversationId={}, httpStatus={}, durationMs={}",
                    operation, path, missiveConversationId, response.getStatusCodeValue(), elapsedMillis(startedAt));
            return response.getBody() == null ? "{}" : response.getBody();
        } catch (RestClientResponseException exception) {
            LOGGER.warn("Missive API call failed: operation={}, path={}, missiveConversationId={}, httpStatus={}, durationMs={}, errorType={}",
                    operation, path, missiveConversationId, exception.getRawStatusCode(), elapsedMillis(startedAt),
                    exception.getClass().getSimpleName());
            throw exception;
        } catch (RestClientException exception) {
            LOGGER.warn("Missive API call failed: operation={}, path={}, missiveConversationId={}, durationMs={}, errorType={}",
                    operation, path, missiveConversationId, elapsedMillis(startedAt), exception.getClass().getSimpleName());
            throw exception;
        }
    }
    private long elapsedMillis(long startedAt) { return (System.nanoTime() - startedAt) / 1000000L; }
    private String safeReason(String reason) { return reason == null || reason.trim().isEmpty() ? "需要人工处理" : reason; }
    private String shortToken(String value) { return value == null ? "anonymous" : value.substring(0, Math.min(12, value.length())); }
    private String text(JsonNode value) { return value == null || value.isMissingNode() || value.isNull() ? null : value.asText(); }
    private void requireCustomChannelConfiguration() {
        if (isBlank(properties.getMissive().getCustomChannelAccountId())) {
            throw new IllegalStateException("Missing MISSIVE_CUSTOM_CHANNEL_ACCOUNT_ID");
        }
        if (isBlank(properties.getMissive().getCustomChannelRecipientId())) {
            throw new IllegalStateException("Missing MISSIVE_CUSTOM_CHANNEL_RECIPIENT_ID");
        }
    }
    private boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }

    public static class CustomChannelMessageReceipt {
        private final String messageId;
        private final String conversationId;
        CustomChannelMessageReceipt(String messageId, String conversationId) {
            this.messageId = messageId;
            this.conversationId = conversationId;
        }
        public String getMessageId() { return messageId; }
        public String getConversationId() { return conversationId; }
    }
}
