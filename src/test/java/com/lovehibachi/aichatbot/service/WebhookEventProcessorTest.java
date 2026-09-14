package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import java.util.Collections;
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
    void ignoresUnsolicitedReplyAfterFinSignalsAwaitingUserReply() {
        ChatConversation conversation = new ChatConversation();
        conversation.setMissiveConversationId("missive-1");
        FinSession session = new FinSession();
        session.setConversation(conversation);
        session.setFinConversationId("fin-1");
        session.setStatus("thinking");

        WebhookEvent completedReply = finEvent("fin-1", "awaiting_user_reply", "Closing message");
        WebhookEvent unsolicitedReply = finEvent("fin-1", "replying", "Follow-up question");
        when(eventRepository.findById("completed")).thenReturn(Optional.of(completedReply));
        when(eventRepository.findById("unsolicited")).thenReturn(Optional.of(unsolicitedReply));
        when(sessionRepository.findByFinConversationId("fin-1")).thenReturn(Optional.of(session));
        when(messageRepository.existsByExternalMessageId(anyString())).thenReturn(false);

        processor().process("completed");

        assertNotNull(session.getReplyCycleCompletedAt());
        verify(missiveClient).sendFinReply(conversation, "Closing message");

        processor().process("unsolicited");

        verify(missiveClient, times(1)).sendFinReply(eq(conversation), anyString());
    }

    @Test
    void customerMessageReopensCompletedFinReplyCycle() throws Exception {
        ChatConversation conversation = new ChatConversation();
        conversation.setMissiveConversationId("missive-1");
        FinSession session = new FinSession();
        session.setConversation(conversation);
        session.setFinConversationId("fin-1");
        session.setStatus("awaiting_user_reply");
        session.setReplyCycleCompletedAt(java.time.Instant.now());

        WebhookEvent customerMessage = new WebhookEvent();
        customerMessage.setProvider("missive");
        customerMessage.setExternalEventId("message-1");
        customerMessage.setEventType("message_created");
        customerMessage.setPayload("{}");
        customerMessage.setStatus(EventStatus.RECEIVED);
        MissiveInboundMessage.Snapshot snapshot = new MissiveInboundMessage.Snapshot(
                "missive-1", "message-1", "visitor-1", "Another question", "[]", "account-1");

        when(eventRepository.findById("customer")).thenReturn(Optional.of(customerMessage));
        when(inboundMessage.parse("{}")).thenReturn(snapshot);
        when(conversationRepository.findByMissiveConversationId("missive-1")).thenReturn(Optional.of(conversation));
        when(sessionRepository.findByConversation_IdOrderByCycleNumberDesc(isNull())).thenReturn(Collections.singletonList(session));
        when(hardRuleEngine.matchingRule("Another question")).thenReturn(null);
        when(messageRepository.existsByExternalMessageId("message-1")).thenReturn(false);

        processor().process("customer");

        assertNull(session.getReplyCycleCompletedAt());
        verify(finClient).reply(session, conversation, "Another question");
    }

    private WebhookEventProcessor processor() {
        return new WebhookEventProcessor(eventRepository, conversationRepository, messageRepository, sessionRepository,
                inboundMessage, hardRuleEngine, finClient, missiveClient, handoffService, new ObjectMapper());
    }

    private WebhookEvent finEvent(String conversationId, String status, String body) {
        WebhookEvent event = new WebhookEvent();
        event.setProvider("fin");
        event.setExternalEventId(UUID.randomUUID().toString());
        event.setEventType("fin_replied");
        event.setPayload("{\"event_name\":\"fin_replied\",\"conversation_id\":\"" + conversationId
                + "\",\"status\":\"" + status + "\",\"message\":{\"body\":\"" + body + "\"}}");
        event.setStatus(EventStatus.RECEIVED);
        return event;
    }
}
