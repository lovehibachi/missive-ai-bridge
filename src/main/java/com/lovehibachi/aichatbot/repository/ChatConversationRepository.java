package com.lovehibachi.aichatbot.repository;

import com.lovehibachi.aichatbot.domain.ChatConversation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, String> {
    Optional<ChatConversation> findByMissiveConversationId(String missiveConversationId);
    Optional<ChatConversation> findByWebChatSessionToken(String webChatSessionToken);
}
