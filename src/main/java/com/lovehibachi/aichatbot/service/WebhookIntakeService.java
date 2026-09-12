package com.lovehibachi.aichatbot.service;

import com.lovehibachi.aichatbot.domain.EventStatus;
import com.lovehibachi.aichatbot.domain.WebhookEvent;
import com.lovehibachi.aichatbot.repository.WebhookEventRepository;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WebhookIntakeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookIntakeService.class);
    private final WebhookEventRepository eventRepository;
    private final WebhookEventProcessor processor;

    public WebhookIntakeService(WebhookEventRepository eventRepository, WebhookEventProcessor processor) {
        this.eventRepository = eventRepository;
        this.processor = processor;
    }

    public void accept(String provider, String externalEventId, String eventType, String payload) {
        Optional<WebhookEvent> existing = eventRepository.findByProviderAndExternalEventId(provider, externalEventId);
        if (existing.isPresent()) {
            LOGGER.info("Ignored duplicate webhook event: provider={}, externalEventId={}, eventType={}",
                    provider, externalEventId, eventType);
            return;
        }
        WebhookEvent event = new WebhookEvent();
        event.setProvider(provider);
        event.setExternalEventId(externalEventId);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setStatus(EventStatus.RECEIVED);
        try {
            eventRepository.saveAndFlush(event);
            LOGGER.info("Queued webhook event: provider={}, eventId={}, externalEventId={}, eventType={}",
                    provider, event.getId(), externalEventId, eventType);
            processor.processAsync(event.getId());
        } catch (DataIntegrityViolationException duplicate) {
            // Concurrent webhook retries may race; the unique constraint is the final idempotency guard.
            LOGGER.info("Ignored concurrently duplicated webhook event: provider={}, externalEventId={}, eventType={}",
                    provider, externalEventId, eventType);
        }
    }
}
