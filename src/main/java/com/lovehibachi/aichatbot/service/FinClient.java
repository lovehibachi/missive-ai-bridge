package com.lovehibachi.aichatbot.service;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.ChatMessage;
import com.lovehibachi.aichatbot.domain.FinSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class FinClient {
    private final RestTemplate restTemplate;
    private final BridgeProperties properties;

    public FinClient(RestTemplate restTemplate, BridgeProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public void start(FinSession session, ChatConversation conversation, String body, List<ChatMessage> history) {
        Map<String, Object> request = request(session, conversation, body);
        if (history != null && !history.isEmpty()) {
            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            List<ChatMessage> chronological = new ArrayList<ChatMessage>(history);
            Collections.reverse(chronological);
            for (ChatMessage message : chronological) {
                Map<String, Object> prior = new LinkedHashMap<String, Object>();
                prior.put("author", message.getAuthor());
                prior.put("body", message.getBody());
                prior.put("timestamp", message.getCreatedAt().toString());
                messages.add(prior);
            }
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            metadata.put("history", messages);
            request.put("conversation_metadata", metadata);
        }
        post("/fin/start", request);
    }

    public void reply(FinSession session, ChatConversation conversation, String body) {
        post("/fin/reply", request(session, conversation, body));
    }

    public void escalate(FinSession session, String reason) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("conversation_id", session.getFinConversationId());
        body.put("context", reason);
        post("/fin/escalate", body);
    }

    private Map<String, Object> request(FinSession session, ChatConversation conversation, String text) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("conversation_id", session.getFinConversationId());
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("author", "user");
        message.put("body", text);
        message.put("timestamp", Instant.now().toString());
        body.put("message", message);
        Map<String, Object> user = new HashMap<String, Object>();
        user.put("id", conversation.getFinVisitorId());
        body.put("user", user);
        return body;
    }

    private void post(String path, Map<String, Object> body) {
        if (isBlank(properties.getFin().getApiKey())) { throw new IllegalStateException("Missing FIN_API_KEY"); }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getFin().getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Intercom-Version", properties.getFin().getApiVersion());
        restTemplate.postForEntity(properties.getFin().getApiBaseUrl() + path, new HttpEntity<Map<String, Object>>(body, headers), String.class);
    }

    private boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
