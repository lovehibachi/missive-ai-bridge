package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.HandoffLink;
import com.lovehibachi.aichatbot.repository.HandoffLinkRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandoffLinkServiceTest {
    @Mock private HandoffLinkRepository linkRepository;
    @Mock private HandoffService handoffService;

    @Test
    void appendsMissiveTextHumanLinkWithoutPersistingTheRawToken() {
        ChatConversation conversation = conversation();
        HandoffLinkService service = service();

        String rendered = service.appendToFinReply(conversation, "<p>Answer</p>");

        assertTrue(rendered.startsWith("<p>Answer</p><p><br></p><p>Need more help? {{ link:"));
        assertTrue(rendered.contains("https://aiservices.letsgohibachi.com/handoff/"));
        assertTrue(rendered.endsWith(" Talk to a human }}</p>"));
        assertFalse(rendered.contains("<a "));
        ArgumentCaptor<HandoffLink> captured = ArgumentCaptor.forClass(HandoffLink.class);
        verify(linkRepository).save(captured.capture());
        assertEquals(conversation, captured.getValue().getConversation());
        assertEquals(64, captured.getValue().getTokenHash().length());
    }

    @Test
    void appendsHtmlHumanLinkForCustomChannelWithoutPersistingTheRawToken() {
        ChatConversation conversation = conversation();
        conversation.setLiveChatAccountId("custom-account-1");
        HandoffLinkService service = service();

        String rendered = service.appendToFinReply(conversation, "<p>Answer</p>");

        assertTrue(rendered.startsWith("<p>Answer</p><p>Need more help? <a href=\"https://aiservices.letsgohibachi.com/handoff/"));
        assertTrue(rendered.endsWith("\">Talk to a human</a></p>"));
        assertFalse(rendered.contains("{{ link:"));
        ArgumentCaptor<HandoffLink> captured = ArgumentCaptor.forClass(HandoffLink.class);
        verify(linkRepository).save(captured.capture());
        assertEquals(conversation, captured.getValue().getConversation());
        assertEquals(64, captured.getValue().getTokenHash().length());
    }

    @Test
    void confirmationConsumesTokenAndUsesExistingHandoffFlow() {
        ChatConversation conversation = conversation();
        HandoffLink link = new HandoffLink();
        link.setConversation(conversation);
        link.setTokenHash(sha256("valid-token"));
        link.setExpiresAt(Instant.now().plusSeconds(60));
        when(linkRepository.findByTokenHashForUpdate(eq(sha256("valid-token")))).thenReturn(Optional.of(link));

        HandoffLinkService.LinkState result = service().confirm("valid-token");

        assertEquals(HandoffLinkService.LinkState.CONFIRMED, result);
        verify(linkRepository).save(link);
        verify(handoffService).requestHuman(conversation, "Visitor clicked human handoff link");
    }

    private HandoffLinkService service() {
        BridgeProperties properties = new BridgeProperties();
        properties.getHandoffLinks().setPublicBaseUrl("https://aiservices.letsgohibachi.com");
        properties.getHandoffLinks().setTtlMinutes(60L);
        properties.getMissive().setCustomChannelAccountId("custom-account-1");
        return new HandoffLinkService(linkRepository, handoffService, properties);
    }

    private ChatConversation conversation() {
        ChatConversation conversation = new ChatConversation();
        conversation.setMissiveConversationId("missive-1");
        return conversation;
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte part : hash) { result.append(String.format("%02x", part & 0xff)); }
            return result.toString();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
