package com.lovehibachi.aichatbot.repository;

import com.lovehibachi.aichatbot.domain.FinSession;
import com.lovehibachi.aichatbot.domain.ConversationState;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FinSessionRepository extends JpaRepository<FinSession, String> {
    Optional<FinSession> findByFinConversationId(String finConversationId);
    List<FinSession> findByConversation_IdOrderByCycleNumberDesc(String conversationId);
    List<FinSession> findTop50ByStatusAndReplyBufferIsNotNullAndReplyReceivedAtBeforeOrderByReplyReceivedAtAsc(
            String status, Instant cutoff);
    List<FinSession> findTop50ByStatusAndFirstReplySentAtBetweenAndLowPeakFollowUpSentAtIsNullAndConversation_StateOrderByFirstReplySentAtAsc(
            String status, Instant earliest, Instant due, ConversationState conversationState);

    /**
     * Claim the one-time follow-up before calling Missive so scheduler runs and
     * process restarts cannot duplicate a promotion for the same customer turn.
     */
    @Modifying
    @Transactional
    @Query("update FinSession f set f.lowPeakFollowUpSentAt = :claimedAt "
            + "where f.id = :sessionId and f.status = 'awaiting_user_reply' "
            + "and f.firstReplySentAt between :earliest and :due "
            // PostgreSQL cannot execute Hibernate's UPDATE ... CROSS JOIN SQL
            // generated for an association predicate here. The candidate query
            // already restricts this scheduler to AI_HANDLING conversations.
            + "and f.lowPeakFollowUpSentAt is null")
    int claimLowPeakFollowUp(@Param("sessionId") String sessionId,
                             @Param("earliest") Instant earliest,
                             @Param("due") Instant due,
                             @Param("claimedAt") Instant claimedAt);

    @Modifying
    @Transactional
    @Query("update FinSession f set f.lowPeakFollowUpSentAt = null "
            + "where f.id = :sessionId and f.lowPeakFollowUpSentAt = :claimedAt")
    int releaseLowPeakFollowUp(@Param("sessionId") String sessionId,
                               @Param("claimedAt") Instant claimedAt);
}
