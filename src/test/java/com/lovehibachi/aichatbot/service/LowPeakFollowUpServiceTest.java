package com.lovehibachi.aichatbot.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.ConversationState;
import com.lovehibachi.aichatbot.domain.FinSession;
import com.lovehibachi.aichatbot.repository.FinSessionRepository;
import java.time.Instant;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LowPeakFollowUpServiceTest {
    @Mock private FinSessionRepository sessionRepository;
    @Mock private MissiveClient missiveClient;

    @Test
    void sendsOneFollowUpAfterAtomicallyClaimingTheCustomerTurn() {
        FinSession session = session();
        when(sessionRepository
                .findTop50ByStatusAndFirstReplySentAtBetweenAndLowPeakFollowUpSentAtIsNullAndConversation_StateOrderByFirstReplySentAtAsc(
                        eq("awaiting_user_reply"), any(Instant.class), any(Instant.class), eq(ConversationState.AI_HANDLING)))
                .thenReturn(Collections.singletonList(session));
        when(sessionRepository.claimLowPeakFollowUp(eq("session-1"), any(Instant.class), any(Instant.class),
                any(Instant.class), eq(ConversationState.AI_HANDLING))).thenReturn(1);

        service(true).sendDueFollowUps();

        verify(missiveClient).sendLowPeakFollowUp(session.getConversation());
        verify(sessionRepository, never()).releaseLowPeakFollowUp(eq("session-1"), any(Instant.class));
    }

    @Test
    void doesNothingWhenThePromotionIsDisabled() {
        service(false).sendDueFollowUps();

        verifyNoInteractions(sessionRepository, missiveClient);
    }

    private LowPeakFollowUpService service(boolean enabled) {
        BridgeProperties properties = new BridgeProperties();
        properties.getPromotions().setLowPeakFollowUpEnabled(enabled);
        properties.getPromotions().setLowPeakFollowUpDelaySeconds(60L);
        properties.getPromotions().setLowPeakFollowUpMaxAgeSeconds(300L);
        return new LowPeakFollowUpService(sessionRepository, missiveClient, properties);
    }

    private FinSession session() {
        ChatConversation conversation = new ChatConversation();
        conversation.setMissiveConversationId("missive-1");
        FinSession session = new FinSession();
        session.setConversation(conversation);
        session.setFinConversationId("fin-1");
        ReflectionTestUtils.setField(session, "id", "session-1");
        return session;
    }
}
