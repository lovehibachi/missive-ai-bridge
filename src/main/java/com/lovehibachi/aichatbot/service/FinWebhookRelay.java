package com.lovehibachi.aichatbot.service;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import java.nio.charset.StandardCharsets;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Routes selected, already-verified Fin callbacks to a colocated bridge instance.
 * The raw UTF-8 body and Fin signature are preserved so the destination verifies
 * the original Fin request independently.
 */
@Service
public class FinWebhookRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinWebhookRelay.class);

    private final RestTemplate restTemplate;
    private final BridgeProperties properties;

    public FinWebhookRelay(@Qualifier("finWebhookRelayRestTemplate") RestTemplate restTemplate,
                           BridgeProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /** Fail closed: this relay is intentionally only for our colocated test bridge. */
    @PostConstruct
    public void validateConfiguration() {
        BridgeProperties.Fin.WebhookRelay relay = properties.getFin().getWebhookRelay();
        String prefix = relay == null ? null : relay.getConversationIdPrefix();
        String target = relay == null ? null : relay.getTargetUrl();
        if (isBlank(prefix) && isBlank(target)) { return; }
        if (!"fin:test:".equals(prefix)
                || !"http://127.0.0.1:8081/webhooks/fin".equals(target)) {
            throw new IllegalStateException("Fin webhook relay must use fin:test: and the local test bridge endpoint");
        }
    }

    public boolean routes(String finConversationId) {
        BridgeProperties.Fin.WebhookRelay relay = properties.getFin().getWebhookRelay();
        return !isBlank(finConversationId)
                && relay != null
                && !isBlank(relay.getConversationIdPrefix())
                && !isBlank(relay.getTargetUrl())
                && finConversationId.startsWith(relay.getConversationIdPrefix());
    }

    public void relay(String finConversationId, String signature, String payload) {
        if (isBlank(signature)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Cannot relay a Fin callback without its Fin signature");
        }
        BridgeProperties.Fin.WebhookRelay relay = properties.getFin().getWebhookRelay();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        headers.set("X-Fin-Agent-API-Webhook-Signature", signature);
        try {
            ResponseEntity<Void> response = restTemplate.exchange(relay.getTargetUrl(), HttpMethod.POST,
                    new HttpEntity<String>(payload, headers), Void.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Test bridge rejected the relayed Fin callback");
            }
            LOGGER.info("Relayed verified Fin callback: finConversationId={}, target={}",
                    finConversationId, relay.getTargetUrl());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RestClientException exception) {
            LOGGER.warn("Failed to relay verified Fin callback: finConversationId={}, target={}, errorType={}",
                    finConversationId, relay.getTargetUrl(), exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Unable to relay Fin callback to the test bridge", exception);
        }
    }

    private boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
