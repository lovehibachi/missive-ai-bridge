package com.lovehibachi.aichatbot.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.service.FinReplyTurnGate;
import com.lovehibachi.aichatbot.service.FinWebhookRelay;
import com.lovehibachi.aichatbot.service.SignatureVerifier;
import com.lovehibachi.aichatbot.service.WebhookIntakeService;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WebhookControllerTest {
    @Test
    void relaysVerifiedTestConversationBeforeProductionIntakeOrReplyGate() throws Exception {
        String payload = "{\"event_name\":\"fin_replied\",\"id\":\"event-1\","
                + "\"conversation_id\":\"fin:test:missive-1\"}";
        BridgeProperties properties = new BridgeProperties();
        properties.getFin().setWebhookSecret("fin-secret");
        WebhookIntakeService intakeService = mock(WebhookIntakeService.class);
        FinReplyTurnGate replyGate = mock(FinReplyTurnGate.class);
        FinWebhookRelay relay = mock(FinWebhookRelay.class);
        when(relay.routes("fin:test:missive-1")).thenReturn(true);
        WebhookController controller = new WebhookController(new SignatureVerifier(), intakeService, replyGate,
                relay, properties, new ObjectMapper());

        assertEquals(HttpStatus.ACCEPTED, controller.fin(signature("fin-secret", payload), null, null,
                mock(HttpServletRequest.class), payload).getStatusCode());

        verify(relay).relay("fin:test:missive-1", signature("fin-secret", payload), payload);
        verify(replyGate, never()).claimFirstReply(anyString());
        verify(intakeService, never()).accept(anyString(), anyString(), anyString(), anyString());
    }

    private String signature(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) { hex.append(String.format("%02x", value & 0xff)); }
        return hex.toString();
    }
}
