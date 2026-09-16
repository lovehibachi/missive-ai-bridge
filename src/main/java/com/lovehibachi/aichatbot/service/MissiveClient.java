package com.lovehibachi.aichatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import java.util.LinkedHashMap;
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
        post.put("conversation_color", "warning");
        // Do not set Missive's reopen flag: true means keep a closed conversation
        // closed when adding the post. add_to_inbox below makes it actionable.
        post.put("add_to_inbox", true);
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("posts", post);
        post("create_handoff_post", "/v1/posts", request, conversation.getMissiveConversationId());
    }

    private void post(String operation, String path, Map<String, Object> body, String missiveConversationId) {
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
    private boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
