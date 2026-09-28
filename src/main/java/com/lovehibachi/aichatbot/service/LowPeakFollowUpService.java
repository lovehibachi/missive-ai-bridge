package com.lovehibachi.aichatbot.service;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.domain.ConversationState;
import com.lovehibachi.aichatbot.domain.FinSession;
import com.lovehibachi.aichatbot.repository.FinSessionRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Sends one booking prompt when a visitor leaves an active Fin turn unanswered. */
@Service
public class LowPeakFollowUpService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LowPeakFollowUpService.class);
    private static final String AWAITING_USER_REPLY = "awaiting_user_reply";
    private final FinSessionRepository sessionRepository;
    private final MissiveClient missiveClient;
    private final BridgeProperties properties;

    public LowPeakFollowUpService(FinSessionRepository sessionRepository,
                                  MissiveClient missiveClient,
                                  BridgeProperties properties) {
        this.sessionRepository = sessionRepository;
        this.missiveClient = missiveClient;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 10000L)
    public void sendDueFollowUps() {
        BridgeProperties.Promotions promotion = properties.getPromotions();
        if (!promotion.isLowPeakFollowUpEnabled()) { return; }

        Instant now = Instant.now();
        Instant due = now.minusSeconds(promotion.getLowPeakFollowUpDelaySeconds());
        Instant earliest = now.minusSeconds(promotion.getLowPeakFollowUpMaxAgeSeconds());
        List<FinSession> dueSessions = sessionRepository
                .findTop50ByStatusAndFirstReplySentAtBetweenAndLowPeakFollowUpSentAtIsNullAndConversation_StateOrderByFirstReplySentAtAsc(
                        AWAITING_USER_REPLY, earliest, due, ConversationState.AI_HANDLING);
        for (FinSession session : dueSessions) {
            sendFollowUpIfStillDue(session, earliest, due, now);
        }
    }

    private void sendFollowUpIfStillDue(FinSession session, Instant earliest, Instant due, Instant claimedAt) {
        int claimed = sessionRepository.claimLowPeakFollowUp(session.getId(), earliest, due, claimedAt);
        if (claimed == 0) { return; }
        try {
            LOGGER.info("Sending low-peak booking follow-up: finConversationId={}, missiveConversationId={}",
                    session.getFinConversationId(), session.getConversation().getMissiveConversationId());
            missiveClient.sendLowPeakFollowUp(session.getConversation());
            LOGGER.info("Sent low-peak booking follow-up: finConversationId={}, missiveConversationId={}",
                    session.getFinConversationId(), session.getConversation().getMissiveConversationId());
        } catch (Exception exception) {
            // Release the claim so a transient Missive error can retry before the
            // short freshness window expires. A successful send remains claimed.
            sessionRepository.releaseLowPeakFollowUp(session.getId(), claimedAt);
            LOGGER.warn("Low-peak booking follow-up failed: finConversationId={}, missiveConversationId={}, errorType={}",
                    session.getFinConversationId(), session.getConversation().getMissiveConversationId(),
                    exception.getClass().getSimpleName());
        }
    }
}
