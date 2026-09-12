package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.config.BridgeProperties;
import org.junit.jupiter.api.Test;

class MissiveInboundMessageTest {
    @Test
    void extractsStableVisitorAndReplyTargetFromIncomingMessage() throws Exception {
        BridgeProperties properties = new BridgeProperties();
        properties.getMissive().setLiveChatAccountId("fallback-account");
        MissiveInboundMessage parser = new MissiveInboundMessage(new ObjectMapper(), properties);
        String payload = "{\"conversation\":{\"id\":\"conversation-1\"},"
                + "\"latest_message\":{\"id\":\"message-1\",\"body\":\"I need help\","
                + "\"account\":{\"id\":\"live-account-1\"},"
                + "\"from_field\":{\"id\":\"visitor-1\",\"username\":\"Visitor abc\",\"name\":\"Visitor\"}}}";

        MissiveInboundMessage.Snapshot snapshot = parser.parse(payload);

        assertEquals("conversation-1", snapshot.getConversationId());
        assertEquals("message-1", snapshot.getMessageId());
        assertEquals("missive:visitor-1", snapshot.getVisitorId());
        assertEquals("live-account-1", snapshot.getAccountId());
        assertTrue(snapshot.getToFieldsJson().contains("visitor-1"));
    }

    @Test
    void supportsNativeLiveChatMessagePayload() throws Exception {
        BridgeProperties properties = new BridgeProperties();
        properties.getMissive().setLiveChatAccountId("live-chat-account");
        MissiveInboundMessage parser = new MissiveInboundMessage(new ObjectMapper(), properties);
        String payload = "{\"conversation\":{\"id\":\"conversation-2\"},"
                + "\"message\":{\"id\":\"message-2\",\"preview\":\"What is the price?\","
                + "\"from_field\":{\"id\":\"visitor-2\",\"name\":\"Visitor\"}}}";

        MissiveInboundMessage.Snapshot snapshot = parser.parse(payload);

        assertEquals("conversation-2", snapshot.getConversationId());
        assertEquals("message-2", snapshot.getMessageId());
        assertEquals("missive:visitor-2", snapshot.getVisitorId());
        assertEquals("What is the price?", snapshot.getBody());
        assertEquals("live-chat-account", snapshot.getAccountId());
    }
}
