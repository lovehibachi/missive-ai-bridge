package com.lovehibachi.aichatbot.repository;

import com.lovehibachi.aichatbot.domain.FinSession;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinSessionRepository extends JpaRepository<FinSession, String> {
    Optional<FinSession> findByFinConversationId(String finConversationId);
    List<FinSession> findByConversation_IdOrderByCycleNumberDesc(String conversationId);
    List<FinSession> findTop50ByStatusAndReplyBufferIsNotNullAndReplyReceivedAtBeforeOrderByReplyReceivedAtAsc(
            String status, Instant cutoff);
}
