package com.lovehibachi.aichatbot.service;

import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.ConversationState;
import com.lovehibachi.aichatbot.repository.ChatConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HandoffService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HandoffService.class);
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
            LOGGER.info("Skipped duplicate human handoff: missiveConversationId={}, state={}",
                    conversation.getMissiveConversationId(), conversation.getState());
            return;
        }
        LOGGER.info("Requesting human handoff: missiveConversationId={}, priorState={}",
                conversation.getMissiveConversationId(), conversation.getState());
        conversation.setState(ConversationState.HUMAN_NEEDED);
        conversation.setEscalationReason(reason);
        conversationRepository.save(conversation);
        missiveClient.createHandoffPost(conversation, reason);
        missiveClient.sendHumanHandoffAcknowledgement(conversation);
        LOGGER.info("Human handoff posted to Missive: missiveConversationId={}", conversation.getMissiveConversationId());
    }
}
