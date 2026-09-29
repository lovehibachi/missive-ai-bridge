package com.lovehibachi.aichatbot.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.service.SignatureVerifier;
import com.lovehibachi.aichatbot.service.WebChatService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MissiveCustomChannelWebhookControllerTest {
    @Test
    void acceptsOfficialMissiveCustomChannelOutboundPayload() {
        String payload = "{\"message\":{\"id\":\"message-1\",\"body\":\"Hello\"},"
                + "\"conversation\":{\"id\":\"conversation-1\"}}";
        SignatureVerifier signatures = mock(SignatureVerifier.class);
        when(signatures.isValid(anyString(), anyString(), anyString())).thenReturn(true);
        WebChatService webChatService = mock(WebChatService.class);
        BridgeProperties properties = new BridgeProperties();
        properties.getMissive().setCustomChannelWebhookSecret("test-secret");
        MissiveCustomChannelWebhookController controller = new MissiveCustomChannelWebhookController(
                signatures, properties, new ObjectMapper(), webChatService);

        assertEquals(HttpStatus.ACCEPTED, controller.outbound("signature", payload).getStatusCode());
        verify(webChatService).receiveOutboundCustomChannelMessage(
                eq("conversation-1"), eq("custom:message-1"), eq("Hello"), any(java.time.Instant.class));
    }
}
