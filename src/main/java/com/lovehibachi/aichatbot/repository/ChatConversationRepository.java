package com.lovehibachi.aichatbot.repository;

import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.ConversationState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, String> {
    Optional<ChatConversation> findByMissiveConversationId(String missiveConversationId);
    Optional<ChatConversation> findByWebChatSessionToken(String webChatSessionToken);

    List<ChatConversation> findTop50ByStateAndHandoffHumanReplyAtIsNullAndHandoffWaitingSinceBeforeAndHandoffWaitReminderSentAtIsNullOrderByHandoffWaitingSinceAsc(
            ConversationState state, Instant due);

    List<ChatConversation> findTop50ByStateAndHandoffHumanReplyAtIsNullAndHandoffWaitingSinceBeforeAndHandoffContactPromptSentAtIsNullOrderByHandoffWaitingSinceAsc(
            ConversationState state, Instant due);

    /** Claim each reminder before making the external Missive call to prevent duplicate scheduler sends. */
    @Modifying
    @Transactional
    @Query("update ChatConversation c set c.handoffWaitReminderSentAt = :claimedAt "
            + "where c.id = :conversationId and c.state = 'HUMAN_NEEDED' "
            + "and c.handoffHumanReplyAt is null and c.handoffWaitingSince = :waitingSince and c.handoffWaitingSince <= :due "
            + "and c.handoffWaitReminderSentAt is null")
    int claimHandoffWaitReminder(@Param("conversationId") String conversationId,
                                 @Param("waitingSince") Instant waitingSince,
                                 @Param("due") Instant due,
                                 @Param("claimedAt") Instant claimedAt);

    @Modifying
    @Transactional
    @Query("update ChatConversation c set c.handoffContactPromptSentAt = :claimedAt "
            + "where c.id = :conversationId and c.state = 'HUMAN_NEEDED' "
            + "and c.handoffHumanReplyAt is null and c.handoffWaitingSince = :waitingSince and c.handoffWaitingSince <= :due "
            + "and c.handoffContactPromptSentAt is null")
    int claimHandoffContactPrompt(@Param("conversationId") String conversationId,
                                  @Param("waitingSince") Instant waitingSince,
                                  @Param("due") Instant due,
                                  @Param("claimedAt") Instant claimedAt);

    @Modifying
    @Transactional
    @Query("update ChatConversation c set c.handoffWaitReminderSentAt = null "
            + "where c.id = :conversationId and c.handoffWaitReminderSentAt = :claimedAt")
    int releaseHandoffWaitReminder(@Param("conversationId") String conversationId,
                                   @Param("claimedAt") Instant claimedAt);

    @Modifying
    @Transactional
    @Query("update ChatConversation c set c.handoffContactPromptSentAt = null "
            + "where c.id = :conversationId and c.handoffContactPromptSentAt = :claimedAt")
    int releaseHandoffContactPrompt(@Param("conversationId") String conversationId,
                                    @Param("claimedAt") Instant claimedAt);
}
