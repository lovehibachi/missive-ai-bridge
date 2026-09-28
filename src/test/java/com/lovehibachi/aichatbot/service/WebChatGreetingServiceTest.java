package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebChatGreetingServiceTest {
    private final WebChatGreetingService greetings = new WebChatGreetingService(new BridgeProperties());

    @Test
    void usesTheVisitorsTimezoneForAFirstChatGreeting() {
        String value = greetings.greetingHtml("first", "America/Los_Angeles", Instant.parse("2026-09-29T15:00:00Z"));

        assertEquals("<p>Good morning! I'm Love Hibachi's AI assistant. How can I help with your event?</p>", value);
    }

    @Test
    void makesReturningGreetingShortAfterAThreeHourGap() {
        String value = greetings.greetingHtml("returning", "America/New_York", Instant.parse("2026-09-29T16:00:00Z"));

        assertTrue(value.contains("Welcome back!"));
    }

    @Test
    void skipsOvernightGreeting() {
        assertEquals(null, greetings.greetingHtml("first", "America/Los_Angeles", Instant.parse("2026-09-29T10:00:00Z")));
    }
}
