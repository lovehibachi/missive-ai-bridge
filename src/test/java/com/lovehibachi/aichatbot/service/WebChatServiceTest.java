package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.ChatMessage;
import com.lovehibachi.aichatbot.repository.ChatConversationRepository;
import com.lovehibachi.aichatbot.repository.ChatMessageRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class WebChatServiceTest {
    @Test
    void marksReturningGreetingOnlyAfterThreeHoursOfInactivity() {
        ChatConversationRepository conversations = mock(ChatConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        MissiveClient missive = mock(MissiveClient.class);
        WebhookIntakeService intake = mock(WebhookIntakeService.class);
        ChatConversation conversation = new ChatConversation();
        ReflectionTestUtils.setField(conversation, "id", "conversation-1");
        conversation.setMissiveConversationId("missive-1");
        ChatMessage prior = new ChatMessage();
        ReflectionTestUtils.setField(prior, "createdAt", Instant.now().minusSeconds(3 * 60 * 60 + 1));
        when(conversations.findByWebChatSessionToken(anyString())).thenReturn(Optional.of(conversation));
        when(messages.findTopByConversation_IdOrderByCreatedAtDesc("conversation-1")).thenReturn(Optional.of(prior));
        when(missive.receiveCustomChannelMessage(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MissiveClient.CustomChannelMessageReceipt("message-1", "missive-1"));
        when(messages.existsByExternalMessageId(anyString())).thenReturn(false);

        service(conversations, messages, missive, intake).receiveVisitorMessage(
                "12345678901234567890123456789012", "client-1", "Hello", "America/Los_Angeles");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(intake).accept(anyString(), anyString(), anyString(), payload.capture());
        assertTrue(payload.getValue().contains("\"greeting_kind\":\"returning\""));
        assertTrue(payload.getValue().contains("\"greeting_language\":\"en\""));
    }

    @Test
    void marksGreetingAsChineseOnlyWhenVisitorMessageContainsHanCharacters() {
        ChatConversationRepository conversations = mock(ChatConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        MissiveClient missive = mock(MissiveClient.class);
        WebhookIntakeService intake = mock(WebhookIntakeService.class);
        ChatConversation conversation = new ChatConversation();
        ReflectionTestUtils.setField(conversation, "id", "conversation-1");
        conversation.setMissiveConversationId("missive-1");
        when(conversations.findByWebChatSessionToken(anyString())).thenReturn(Optional.of(conversation));
        when(messages.findTopByConversation_IdOrderByCreatedAtDesc("conversation-1")).thenReturn(Optional.empty());
        when(missive.receiveCustomChannelMessage(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MissiveClient.CustomChannelMessageReceipt("message-1", "missive-1"));
        when(messages.existsByExternalMessageId(anyString())).thenReturn(false);

        service(conversations, messages, missive, intake).receiveVisitorMessage(
                "12345678901234567890123456789012", "client-1", "你们的菜单是什么？", "America/Los_Angeles");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(intake).accept(anyString(), anyString(), anyString(), payload.capture());
        assertTrue(payload.getValue().contains("\"greeting_language\":\"zh\""));
    }

    @Test
    void skipsGreetingWhenTheLastChatMessageIsRecent() {
        ChatConversationRepository conversations = mock(ChatConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        MissiveClient missive = mock(MissiveClient.class);
        WebhookIntakeService intake = mock(WebhookIntakeService.class);
        ChatConversation conversation = new ChatConversation();
        ReflectionTestUtils.setField(conversation, "id", "conversation-1");
        conversation.setMissiveConversationId("missive-1");
        ChatMessage prior = new ChatMessage();
        ReflectionTestUtils.setField(prior, "createdAt", Instant.now().minusSeconds(60));
        when(conversations.findByWebChatSessionToken(anyString())).thenReturn(Optional.of(conversation));
        when(messages.findTopByConversation_IdOrderByCreatedAtDesc("conversation-1")).thenReturn(Optional.of(prior));
        when(missive.receiveCustomChannelMessage(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MissiveClient.CustomChannelMessageReceipt("message-1", "missive-1"));
        when(messages.existsByExternalMessageId(anyString())).thenReturn(false);

        service(conversations, messages, missive, intake).receiveVisitorMessage(
                "12345678901234567890123456789012", "client-1", "Hello", "America/Los_Angeles");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(intake).accept(anyString(), anyString(), anyString(), payload.capture());
        assertFalse(payload.getValue().contains("greeting_kind"));
    }

    private WebChatService service(ChatConversationRepository conversations, ChatMessageRepository messages,
                                   MissiveClient missive, WebhookIntakeService intake) {
        return new WebChatService(conversations, messages, missive, mock(HandoffService.class),
                mock(HandoffWaitingService.class), intake, mock(WebChatNotifier.class),
                new ObjectMapper(), new BridgeProperties());
    }
}
