package com.lovehibachi.aichatbot.service;

import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.ConversationState;
import com.lovehibachi.aichatbot.domain.FinSession;
import com.lovehibachi.aichatbot.repository.ChatConversationRepository;
import com.lovehibachi.aichatbot.repository.FinSessionRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Performs the complete, reversible transition from human handling to Fin AI. */
@Service
public class AiResumeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiResumeService.class);
    private final ChatConversationRepository conversationRepository;
    private final FinSessionRepository sessionRepository;
    private final MissiveClient missiveClient;

    public AiResumeService(ChatConversationRepository conversationRepository,
                           FinSessionRepository sessionRepository,
                           MissiveClient missiveClient) {
        this.conversationRepository = conversationRepository;
        this.sessionRepository = sessionRepository;
        this.missiveClient = missiveClient;
    }

    @Transactional
    public Result resume(String missiveConversationId) {
        ChatConversation conversation = conversationRepository.findByMissiveConversationId(missiveConversationId)
                .orElseThrow(() -> new ConversationNotFoundException(missiveConversationId));
        if (conversation.getState() == ConversationState.AI_HANDLING) {
            LOGGER.info("AI resume skipped because conversation is already AI-handled: missiveConversationId={}",
                    missiveConversationId);
            return Result.ALREADY_AI_HANDLING;
        }

        LOGGER.info("Resuming AI handling: missiveConversationId={}, priorState={}",
                missiveConversationId, conversation.getState());
        // Move the visible Missive conversation first. If this API call fails we
        // deliberately retain the local human state, preventing a split state.
        missiveClient.resumeAiHandling(conversation);

        Instant now = Instant.now();
        List<FinSession> sessions = sessionRepository.findByConversation_IdOrderByCycleNumberDesc(conversation.getId());
        for (FinSession session : sessions) {
            // Preserve historic records, but retire every old Fin conversation.
            // A delayed old callback must not reply after the operator restores AI.
            session.setStatus("superseded");
            session.setCompletedAt(now);
            session.setReplyBuffer(null);
            session.setReplyReceivedAt(null);
            session.setFirstReplySentAt(null);
            session.setLowPeakFollowUpSentAt(null);
        }
        sessionRepository.saveAll(sessions);
        conversation.setState(ConversationState.AI_HANDLING);
        conversation.setEscalationReason(null);
        conversationRepository.save(conversation);
        LOGGER.info("AI handling resumed: missiveConversationId={}, supersededFinSessions={}",
                missiveConversationId, sessions.size());
        return Result.RESUMED;
    }

    public enum Result { RESUMED, ALREADY_AI_HANDLING }

    public static class ConversationNotFoundException extends RuntimeException {
        public ConversationNotFoundException(String missiveConversationId) {
            super("Unknown Missive conversation: " + missiveConversationId);
        }
    }
}
