package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class FinWebhookRelayTest {
    @Test
    void relaysOnlyTheExplicitTestPrefixAndPreservesFinSignature() {
        BridgeProperties properties = new BridgeProperties();
        properties.getFin().getWebhookRelay().setConversationIdPrefix("fin:test:");
        properties.getFin().getWebhookRelay().setTargetUrl("http://127.0.0.1:8081/webhooks/fin");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        String payload = "{\"event_name\":\"fin_replied\",\"conversation_id\":\"fin:test:missive-1\",\"text\":\"\u4f60\u597d\"}";
        server.expect(once(), requestTo("http://127.0.0.1:8081/webhooks/fin"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Fin-Agent-API-Webhook-Signature", "signature"))
                .andExpect(content().string(payload))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        FinWebhookRelay relay = new FinWebhookRelay(restTemplate, properties);
        relay.validateConfiguration();
        assertTrue(relay.routes("fin:test:missive-1"));
        assertFalse(relay.routes("fin:missive:production-1"));
        relay.relay("fin:test:missive-1", "signature", payload);

        server.verify();
    }

    @Test
    void rejectsAConfigThatCouldForwardProductionTraffic() {
        BridgeProperties properties = new BridgeProperties();
        properties.getFin().getWebhookRelay().setConversationIdPrefix("fin:");
        properties.getFin().getWebhookRelay().setTargetUrl("http://127.0.0.1:8081/webhooks/fin");

        FinWebhookRelay relay = new FinWebhookRelay(new RestTemplate(), properties);

        assertThrows(IllegalStateException.class, relay::validateConfiguration);
    }
}
