package com.lovehibachi.aichatbot.repository;

import com.lovehibachi.aichatbot.domain.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {
    boolean existsByExternalMessageId(String externalMessageId);

    List<ChatMessage> findTop10ByConversation_IdAndExternalMessageIdNotOrderByCreatedAtDesc(
            String conversationId, String excludedExternalMessageId);
}
