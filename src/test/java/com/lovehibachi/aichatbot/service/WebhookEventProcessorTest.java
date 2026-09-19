package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
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
import java.time.Instant;
import java.util.Collections;
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
    @Mock private FinReplyTurnGate finReplyTurnGate;

    @Test
    void sendsOnlyFirstFinReplyRegardlessOfLaterReplyStatus() {
        ChatConversation conversation = conversation();
        FinSession session = session(conversation);
        WebhookEvent firstReply = finReply("replying", "First answer");
        WebhookEvent secondReply = finReply("awaiting_user_reply", "Second answer");
        when(eventRepository.findById("first")).thenReturn(Optional.of(firstReply));
        when(eventRepository.findById("second")).thenReturn(Optional.of(secondReply));
        when(sessionRepository.findByFinConversationId("fin-1")).thenReturn(Optional.of(session));
        when(messageRepository.existsByExternalMessageId(anyString())).thenReturn(false);

        processor().process("first");

        assertNotNull(session.getFirstReplySentAt());
        verify(missiveClient).sendFinReply(conversation, "<p>First answer</p>");

        processor().process("second");

        verify(missiveClient, times(1)).sendFinReply(eq(conversation), anyString());
    }

    @Test
    void removesFinNumberedSourceCitationBeforeSendingReply() {
        ChatConversation conversation = conversation();
        FinSession session = session(conversation);
        WebhookEvent reply = finReply("replying",
                "Final price depends on your address. [1 <https://intercom.help/example/pricing>]");
        when(eventRepository.findById("citation")).thenReturn(Optional.of(reply));
        when(sessionRepository.findByFinConversationId("fin-1")).thenReturn(Optional.of(session));
        when(messageRepository.existsByExternalMessageId(anyString())).thenReturn(false);

        processor().process("citation");

        verify(missiveClient).sendFinReply(conversation, "<p>Final price depends on your address.</p>");
    }

    @Test
    void finGuidanceHandoffMarkerRequestsHumanWithoutSendingItToVisitor() {
        ChatConversation conversation = conversation();
        FinSession session = session(conversation);
        // Fin wraps replies in HTML and may add text despite Guidance.
        WebhookEvent reply = finReply("replying", "<p>I will hand this over.</p><p>[[LH_HUMAN_HANDOFF]]</p>");
        when(eventRepository.findById("handoff-marker")).thenReturn(Optional.of(reply));
        when(sessionRepository.findByFinConversationId("fin-1")).thenReturn(Optional.of(session));

        processor().process("handoff-marker");

        verify(handoffService).requestHuman(conversation, "Fin Guidance requested human handoff");
        verifyNoInteractions(missiveClient);
        assertEquals("escalated", session.getStatus());
        assertNotNull(session.getCompletedAt());
        assertNull(session.getFirstReplySentAt());
    }

    @Test
    void removesFinCitationWhenUrlIsWrappedInMarkdown() {
        ChatConversation conversation = conversation();
        FinSession session = session(conversation);
        WebhookEvent reply = finReply("replying",
                "* The final time is confirmed later. [2 <[https://intercom.help/example/booking](https://intercom.help/example/booking)>]");
        when(eventRepository.findById("markdown-citation")).thenReturn(Optional.of(reply));
        when(sessionRepository.findByFinConversationId("fin-1")).thenReturn(Optional.of(session));
        when(messageRepository.existsByExternalMessageId(anyString())).thenReturn(false);

        processor().process("markdown-citation");

        verify(missiveClient).sendFinReply(conversation, "<p>• The final time is confirmed later.</p>");
    }

    @Test
    void removesIntercomHtmlInlineCitationBeforeSendingReply() {
        ChatConversation conversation = conversation();
        FinSession session = session(conversation);
        WebhookEvent reply = finReply("replying",
                "The final time is confirmed later. [<a data-inline-citation=\"\" href=\"https://intercom.help/example/booking\">2</a>]");
        when(eventRepository.findById("html-citation")).thenReturn(Optional.of(reply));
        when(sessionRepository.findByFinConversationId("fin-1")).thenReturn(Optional.of(session));
        when(messageRepository.existsByExternalMessageId(anyString())).thenReturn(false);

        processor().process("html-citation");

        verify(missiveClient).sendFinReply(conversation, "<p>The final time is confirmed later.</p>");
    }

    @Test
    void customerMessageReopensFirstReplyGuard() throws Exception {
        ChatConversation conversation = conversation();
        FinSession session = session(conversation);
        session.setStatus("awaiting_user_reply");
        session.setFirstReplySentAt(Instant.now());
        session.setLowPeakFollowUpSentAt(Instant.now());
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

        assertNull(session.getFirstReplySentAt());
        assertNull(session.getLowPeakFollowUpSentAt());
        verify(finReplyTurnGate).reset("fin-1");
        verify(finClient).reply(session, conversation, "Another question");
    }

    @Test
    void customerMessageAfterHumanHandoffNeverTriggersAnotherAiReplyOrLink() throws Exception {
        ChatConversation conversation = conversation();
        conversation.setState(com.lovehibachi.aichatbot.domain.ConversationState.HUMAN_NEEDED);
        WebhookEvent customerMessage = new WebhookEvent();
        customerMessage.setProvider("missive");
        customerMessage.setExternalEventId("message-after-handoff");
        customerMessage.setEventType("message_created");
        customerMessage.setPayload("{}");
        customerMessage.setStatus(EventStatus.RECEIVED);
        MissiveInboundMessage.Snapshot snapshot = new MissiveInboundMessage.Snapshot(
                "missive-1", "message-after-handoff", "visitor-1", "Are you there?", "[]", "account-1");
        when(eventRepository.findById("after-handoff")).thenReturn(Optional.of(customerMessage));
        when(inboundMessage.parse("{}")).thenReturn(snapshot);
        when(conversationRepository.findByMissiveConversationId("missive-1")).thenReturn(Optional.of(conversation));
        when(messageRepository.existsByExternalMessageId("message-after-handoff")).thenReturn(false);

        processor().process("after-handoff");

        verifyNoInteractions(finClient, missiveClient);
    }

    @Test
    void ignoresLateFinReplyFromSupersededSessionAfterAiResume() {
        ChatConversation conversation = conversation();
        FinSession session = session(conversation);
        session.setStatus("superseded");
        WebhookEvent reply = finReply("replying", "A delayed old answer");
        when(eventRepository.findById("late-reply")).thenReturn(Optional.of(reply));
        when(sessionRepository.findByFinConversationId("fin-1")).thenReturn(Optional.of(session));

        processor().process("late-reply");

        assertEquals(EventStatus.IGNORED, reply.getStatus());
        verifyNoInteractions(missiveClient, handoffService);
    }

    private ChatConversation conversation() {
        ChatConversation conversation = new ChatConversation();
        conversation.setMissiveConversationId("missive-1");
        return conversation;
    }

    private FinSession session(ChatConversation conversation) {
        FinSession session = new FinSession();
        session.setConversation(conversation);
        session.setFinConversationId("fin-1");
        session.setStatus("thinking");
        return session;
    }

    private WebhookEventProcessor processor() {
        return new WebhookEventProcessor(eventRepository, conversationRepository, messageRepository, sessionRepository,
                inboundMessage, hardRuleEngine, finClient, missiveClient, handoffService, finReplyTurnGate,
                new FinReplyRenderer(), new ObjectMapper());
    }

    private WebhookEvent finReply(String status, String body) {
        WebhookEvent event = new WebhookEvent();
        event.setProvider("fin");
        event.setExternalEventId(UUID.randomUUID().toString());
        event.setEventType("fin_replied");
        event.setPayload("{\"event_name\":\"fin_replied\",\"conversation_id\":\"fin-1\",\"status\":\""
                + status + "\",\"message\":{\"body\":\"" + escapeJson(body) + "\"}}");
        event.setStatus(EventStatus.RECEIVED);
        return event;
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
