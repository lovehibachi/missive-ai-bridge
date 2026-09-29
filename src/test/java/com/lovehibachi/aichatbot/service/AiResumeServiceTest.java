package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.ConversationState;
import com.lovehibachi.aichatbot.domain.FinSession;
import com.lovehibachi.aichatbot.repository.ChatConversationRepository;
import com.lovehibachi.aichatbot.repository.FinSessionRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiResumeServiceTest {
    @Mock private ChatConversationRepository conversationRepository;
    @Mock private FinSessionRepository sessionRepository;
    @Mock private MissiveClient missiveClient;

    @Test
    void resumesAiAndSupersedesAllPriorFinSessions() {
        ChatConversation conversation = new ChatConversation();
        conversation.setMissiveConversationId("missive-1");
        conversation.setState(ConversationState.HUMAN_NEEDED);
        conversation.setEscalationReason("Customer asked for a person");
        conversation.setHandoffWaitingSince(Instant.now());
        conversation.setHandoffWaitReminderSentAt(Instant.now());
        conversation.setHandoffContactPromptSentAt(Instant.now());
        conversation.setHandoffHumanReplyAt(Instant.now());
        FinSession newest = session(conversation, "awaiting_user_reply");
        FinSession older = session(conversation, "escalated");
        when(conversationRepository.findByMissiveConversationId("missive-1")).thenReturn(Optional.of(conversation));
        when(sessionRepository.findByConversation_IdOrderByCycleNumberDesc(any()))
                .thenReturn(Arrays.asList(newest, older));

        AiResumeService.Result result = service().resume("missive-1");

        assertEquals(AiResumeService.Result.RESUMED, result);
        assertEquals(ConversationState.AI_HANDLING, conversation.getState());
        assertNull(conversation.getEscalationReason());
        assertNull(conversation.getHandoffWaitingSince());
        assertNull(conversation.getHandoffWaitReminderSentAt());
        assertNull(conversation.getHandoffContactPromptSentAt());
        assertNull(conversation.getHandoffHumanReplyAt());
        assertSuperseded(newest);
        assertSuperseded(older);
        verify(missiveClient).resumeAiHandling(conversation);
        verify(sessionRepository).saveAll(Arrays.asList(newest, older));
        verify(conversationRepository).save(conversation);
    }

    @Test
    void doesNotCallMissiveWhenAlreadyAiHandled() {
        ChatConversation conversation = new ChatConversation();
        conversation.setMissiveConversationId("missive-1");
        conversation.setState(ConversationState.AI_HANDLING);
        when(conversationRepository.findByMissiveConversationId("missive-1")).thenReturn(Optional.of(conversation));

        assertEquals(AiResumeService.Result.ALREADY_AI_HANDLING, service().resume("missive-1"));

        verifyNoInteractions(missiveClient, sessionRepository);
        verify(conversationRepository, never()).save(conversation);
    }

    private void assertSuperseded(FinSession session) {
        assertEquals("superseded", session.getStatus());
        assertNull(session.getReplyBuffer());
        assertNull(session.getReplyReceivedAt());
        assertNull(session.getFirstReplySentAt());
        assertNull(session.getLowPeakFollowUpSentAt());
    }

    private FinSession session(ChatConversation conversation, String status) {
        FinSession session = new FinSession();
        session.setConversation(conversation);
        session.setStatus(status);
        session.setReplyBuffer("partial answer");
        session.setReplyReceivedAt(Instant.now());
        session.setFirstReplySentAt(Instant.now());
        session.setLowPeakFollowUpSentAt(Instant.now());
        return session;
    }

    private AiResumeService service() {
        return new AiResumeService(conversationRepository, sessionRepository, missiveClient);
    }
}
