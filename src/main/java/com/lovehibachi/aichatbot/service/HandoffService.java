package com.lovehibachi.aichatbot.service;

import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.ConversationState;
import com.lovehibachi.aichatbot.repository.ChatConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HandoffService {
    private final ChatConversationRepository conversationRepository;
    private final MissiveClient missiveClient;

    public HandoffService(ChatConversationRepository conversationRepository, MissiveClient missiveClient) {
        this.conversationRepository = conversationRepository;
        this.missiveClient = missiveClient;
    }

    @Transactional
    public void requestHuman(ChatConversation conversation, String reason) {
        if (conversation.getState() == ConversationState.HUMAN_NEEDED
                || conversation.getState() == ConversationState.HUMAN_HANDLING
                || conversation.getState() == ConversationState.CLOSED) {
            return;
        }
        conversation.setState(ConversationState.HUMAN_NEEDED);
        conversation.setEscalationReason(reason);
        conversationRepository.save(conversation);
        missiveClient.createHandoffPost(conversation, reason);
    }
}
