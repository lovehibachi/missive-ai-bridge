package com.lovehibachi.aichatbot.domain;

import java.time.Instant;
import java.util.UUID;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Entity
@Table(name = "webhook_events", uniqueConstraints = @UniqueConstraint(name = "uk_webhook_provider_event", columnNames = {"provider", "external_event_id"}))
public class WebhookEvent {
    @Id
    private String id;
    @Column(nullable = false, length = 32)
    private String provider;
    @Column(name = "external_event_id", nullable = false, length = 255)
    private String externalEventId;
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EventStatus status = EventStatus.RECEIVED;
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void prePersist() {
        if (id == null) { id = UUID.randomUUID().toString(); }
        createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getExternalEventId() { return externalEventId; }
    public void setExternalEventId(String value) { externalEventId = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public String getPayload() { return payload; }
    public void setPayload(String value) { payload = value; }
    public EventStatus getStatus() { return status; }
    public void setStatus(EventStatus value) { status = value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant value) { processedAt = value; }
}
