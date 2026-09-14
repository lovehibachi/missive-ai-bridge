package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.EventStatus;
import com.lovehibachi.aichatbot.domain.FinSession;
import com.lovehibachi.aichatbot.domain.WebhookEvent;
import com.lovehibachi.aichatbot.repository.ChatConversationRepository;
import com.lovehibachi.aichatbot.repository.ChatMessageRepository;
import com.lovehibachi.aichatbot.repository.FinSessionRepository;
import com.lovehibachi.aichatbot.repository.WebhookEventRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebhookEventProcessorTest {
    @Mock private WebhookEventRepository eventRepository;
    @Mock private ChatConversationRepository conversationRepository;
    @Mock private ChatMessageRepository messageRepository;
    @Mock private FinSessionRepository sessionRepository;
    @Mock private MissiveInboundMessage inboundMessage;
    @Mock private HardRuleEngine hardRuleEngine;
    @Mock private FinClient finClient;
    @Mock private MissiveClient missiveClient;
    @Mock private HandoffService handoffService;

    @Test
    void sendsBufferedReplyOnlyAfterV216CompletionStatus() {
        ChatConversation conversation = new ChatConversation();
        conversation.setMissiveConversationId("missive-1");
        FinSession session = new FinSession();
        session.setConversation(conversation);
        session.setFinConversationId("fin-1");
        session.setStatus("thinking");
        WebhookEvent reply = finReply("replying", "Part one");
        WebhookEvent completed = finStatus("awaiting_user_reply");
        when(eventRepository.findById("reply")).thenReturn(Optional.of(reply));
        when(eventRepository.findById("completed")).thenReturn(Optional.of(completed));
        when(sessionRepository.findByFinConversationId("fin-1")).thenReturn(Optional.of(session));
        when(messageRepository.existsByExternalMessageId(anyString())).thenReturn(false);

        processor().process("reply");

        assertEquals("Part one", session.getReplyBuffer());
        verifyNoInteractions(missiveClient);

        processor().process("completed");

        verify(missiveClient).sendFinReply(conversation, "Part one");
    }

    private WebhookEventProcessor processor() {
        return new WebhookEventProcessor(eventRepository, conversationRepository, messageRepository, sessionRepository,
                inboundMessage, hardRuleEngine, finClient, missiveClient, handoffService, new ObjectMapper());
    }

    private WebhookEvent finReply(String status, String body) {
        return finEvent("{\"event_name\":\"fin_replied\",\"conversation_id\":\"fin-1\",\"status\":\""
                + status + "\",\"message\":{\"body\":\"" + body + "\"}}");
    }

    private WebhookEvent finStatus(String status) {
        return finEvent("{\"event_name\":\"fin_status_updated\",\"conversation_id\":\"fin-1\",\"status\":\""
                + status + "\"}");
    }

    private WebhookEvent finEvent(String payload) {
        WebhookEvent event = new WebhookEvent();
        event.setProvider("fin");
        event.setExternalEventId(UUID.randomUUID().toString());
        event.setEventType("fin");
        event.setPayload(payload);
        event.setStatus(EventStatus.RECEIVED);
        return event;
    }
}
