package com.lovehibachi.aichatbot.repository;

import com.lovehibachi.aichatbot.domain.EventStatus;
import com.lovehibachi.aichatbot.domain.WebhookEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {
    Optional<WebhookEvent> findByProviderAndExternalEventId(String provider, String externalEventId);
    List<WebhookEvent> findTop50ByStatusOrderByCreatedAtAsc(EventStatus status);
}
