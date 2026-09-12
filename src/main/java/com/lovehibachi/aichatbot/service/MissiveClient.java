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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class MissiveClient {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final BridgeProperties properties;

    public MissiveClient(RestTemplate restTemplate, ObjectMapper objectMapper, BridgeProperties properties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void sendFinReply(ChatConversation conversation, String htmlBody) {
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
            post("/v1/drafts", request);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to build Missive Draft request", exception);
        }
    }

    public void createHandoffPost(ChatConversation conversation, String reason) {
        Map<String, Object> post = new LinkedHashMap<String, Object>();
        post.put("conversation", conversation.getMissiveConversationId());
        post.put("organization", properties.getMissive().getOrganizationId());
        post.put("username", "Fin AI");
        post.put("markdown", "## 🤖 Fin 请求人工介入\n\n原因：" + safeReason(reason));
        Map<String, Object> notification = new LinkedHashMap<String, Object>();
        notification.put("title", "需要人工介入");
        notification.put("body", safeReason(reason));
        post.put("notification", notification);
        post.put("add_shared_labels", java.util.Collections.singletonList(properties.getMissive().getNeedHumanLabelId()));
        post.put("conversation_color", "warning");
        post.put("reopen", true);
        post.put("add_to_inbox", true);
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("posts", post);
        post("/v1/posts", request);
    }

    private void post(String path, Map<String, Object> body) {
        if (isBlank(properties.getMissive().getFinAiPat())) { throw new IllegalStateException("Missing MISSIVE_FIN_AI_PAT"); }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getMissive().getFinAiPat());
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.postForEntity(properties.getMissive().getApiBaseUrl() + path, new HttpEntity<Map<String, Object>>(body, headers), String.class);
    }
    private String safeReason(String reason) { return reason == null || reason.trim().isEmpty() ? "需要人工处理" : reason; }
    private boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
