package com.lovehibachi.aichatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.ChatMessage;
import com.lovehibachi.aichatbot.domain.ConversationState;
import com.lovehibachi.aichatbot.domain.EventStatus;
import com.lovehibachi.aichatbot.domain.FinSession;
import com.lovehibachi.aichatbot.domain.WebhookEvent;
import com.lovehibachi.aichatbot.repository.ChatConversationRepository;
import com.lovehibachi.aichatbot.repository.ChatMessageRepository;
import com.lovehibachi.aichatbot.repository.FinSessionRepository;
import com.lovehibachi.aichatbot.repository.WebhookEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookEventProcessor {
    private final WebhookEventRepository eventRepository;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final FinSessionRepository sessionRepository;
    private final MissiveInboundMessage inboundMessage;
    private final HardRuleEngine hardRuleEngine;
    private final FinClient finClient;
    private final MissiveClient missiveClient;
    private final HandoffService handoffService;
    private final ObjectMapper objectMapper;

    public WebhookEventProcessor(WebhookEventRepository eventRepository,
                                 ChatConversationRepository conversationRepository,
                                 ChatMessageRepository messageRepository,
                                 FinSessionRepository sessionRepository,
                                 MissiveInboundMessage inboundMessage,
                                 HardRuleEngine hardRuleEngine,
                                 FinClient finClient,
                                 MissiveClient missiveClient,
                                 HandoffService handoffService,
                                 ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.inboundMessage = inboundMessage;
        this.hardRuleEngine = hardRuleEngine;
        this.finClient = finClient;
        this.missiveClient = missiveClient;
        this.handoffService = handoffService;
        this.objectMapper = objectMapper;
    }

    @Async("bridgeExecutor")
    public void processAsync(String eventId) {
        process(eventId);
    }

    @Scheduled(fixedDelay = 10000L)
    public void retryReceivedEvents() {
        for (WebhookEvent event : eventRepository.findTop50ByStatusOrderByCreatedAtAsc(EventStatus.RECEIVED)) {
            process(event.getId());
        }
        for (WebhookEvent event : eventRepository.findTop50ByStatusOrderByCreatedAtAsc(EventStatus.RETRY)) {
            process(event.getId());
        }
    }

    @Transactional
    public void process(String eventId) {
        WebhookEvent event = eventRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() == EventStatus.COMPLETED || event.getStatus() == EventStatus.IGNORED) { return; }
        event.setStatus(EventStatus.PROCESSING);
        eventRepository.save(event);
        try {
            if ("missive".equals(event.getProvider())) {
                processMissive(event);
            } else if ("fin".equals(event.getProvider())) {
                processFin(event);
            } else {
                event.setStatus(EventStatus.IGNORED);
                return;
            }
            if (event.getStatus() != EventStatus.IGNORED) {
                event.setStatus(EventStatus.COMPLETED);
                event.setProcessedAt(Instant.now());
                event.setErrorMessage(null);
            }
        } catch (DeferredMessageException deferred) {
            event.setStatus(EventStatus.RETRY);
            event.setErrorMessage(deferred.getMessage());
        } catch (Exception exception) {
            event.setStatus(EventStatus.RETRY);
            event.setErrorMessage(shortMessage(exception));
        }
        eventRepository.save(event);
    }

    private void processMissive(WebhookEvent event) throws Exception {
        MissiveInboundMessage.Snapshot inbound = inboundMessage.parse(event.getPayload());
        ChatConversation conversation = conversationRepository.findByMissiveConversationId(inbound.getConversationId()).orElseGet(ChatConversation::new);
        if (conversation.getMissiveConversationId() == null) {
            conversation.setMissiveConversationId(inbound.getConversationId());
            conversation.setLiveChatAccountId(inbound.getAccountId());
            conversation.setFinVisitorId(inbound.getVisitorId());
            conversation.setVisitorToFields(inbound.getToFieldsJson());
            conversation.setState(ConversationState.AI_HANDLING);
        } else {
            conversation.setVisitorToFields(inbound.getToFieldsJson());
        }
        conversation.setLastMissiveMessageId(inbound.getMessageId());
        conversationRepository.save(conversation);
        recordMessage(conversation, inbound.getMessageId(), "user", inbound.getBody());

        if (conversation.getState() != ConversationState.AI_HANDLING) {
            return;
        }
        String hardRule = hardRuleEngine.matchingRule(inbound.getBody());
        if (hardRule != null) {
            handoffService.requestHuman(conversation, hardRule);
            return;
        }

        FinSession active = latestSession(conversation);
        if (active == null || isTerminal(active.getStatus())) {
            FinSession newSession = new FinSession();
            newSession.setConversation(conversation);
            newSession.setCycleNumber(active == null ? 1 : active.getCycleNumber() + 1);
            newSession.setFinConversationId("fin:missive:" + conversation.getMissiveConversationId() + ":cycle:" + UUID.randomUUID().toString());
            newSession.setStatus("thinking");
            sessionRepository.save(newSession);
            finClient.start(newSession, conversation, inbound.getBody(), historyBeforeCurrent(conversation, inbound.getMessageId()));
            return;
        }
        if ("awaiting_user_reply".equals(active.getStatus())) {
            active.setStatus("thinking");
            sessionRepository.save(active);
            finClient.reply(active, conversation, inbound.getBody());
            return;
        }
        throw new DeferredMessageException("Fin is still " + active.getStatus() + " for this conversation");
    }

    private void processFin(WebhookEvent event) throws Exception {
        JsonNode root = objectMapper.readTree(event.getPayload());
        String finConversationId = required(root.path("conversation_id"), "conversation_id");
        Optional<FinSession> found = sessionRepository.findByFinConversationId(finConversationId);
        if (!found.isPresent()) { event.setStatus(EventStatus.IGNORED); return; }
        FinSession session = found.get();
        ChatConversation conversation = session.getConversation();
        String eventName = root.path("event_name").asText();
        if ("fin_replied".equals(eventName)) {
            String answer = root.path("message").path("body").asText();
            if (!answer.trim().isEmpty()) {
                session.setReplyBuffer(appendReplyPart(session.getReplyBuffer(), answer));
            }
            String replyStatus = root.path("status").asText();
            if (!replyStatus.isEmpty()) { session.setStatus(replyStatus); }
            if ("awaiting_user_reply".equals(replyStatus)) {
                flushReply(session, conversation);
            }
            sessionRepository.save(session);
            return;
        }
        if ("fin_status_updated".equals(eventName)) {
            String status = required(root.path("status"), "status");
            session.setStatus(status);
            if ("complete".equals(status) || "escalated".equals(status)) { session.setCompletedAt(Instant.now()); }
            if ("awaiting_user_reply".equals(status) || "complete".equals(status) || "resolved".equals(status)) {
                flushReply(session, conversation);
            }
            sessionRepository.save(session);
            if ("escalated".equals(status)) {
                handoffService.requestHuman(conversation, root.path("reason").asText("Fin escalated"));
            }
            return;
        }
        event.setStatus(EventStatus.IGNORED);
    }

    private FinSession latestSession(ChatConversation conversation) {
        List<FinSession> sessions = sessionRepository.findByConversation_IdOrderByCycleNumberDesc(conversation.getId());
        return sessions.isEmpty() ? null : sessions.get(0);
    }
    private List<ChatMessage> historyBeforeCurrent(ChatConversation conversation, String currentMessageId) {
        return messageRepository.findTop10ByConversation_IdAndExternalMessageIdNotOrderByCreatedAtDesc(
                conversation.getId(), currentMessageId);
    }
    private void recordMessage(ChatConversation conversation, String externalMessageId, String author, String body) {
        ChatMessage message = new ChatMessage();
        message.setConversation(conversation);
        message.setExternalMessageId(externalMessageId);
        message.setAuthor(author);
        message.setBody(body);
        messageRepository.save(message);
    }

    private boolean isTerminal(String status) {
        return "complete".equals(status) || "escalated".equals(status) || "resolved".equals(status);
    }
    private String appendReplyPart(String existing, String part) {
        return existing == null || existing.trim().isEmpty() ? part : existing + "\n" + part;
    }
    private void flushReply(FinSession session, ChatConversation conversation) {
        String reply = session.getReplyBuffer();
        if (conversation.getState() == ConversationState.AI_HANDLING && reply != null && !reply.trim().isEmpty()) {
            missiveClient.sendFinReply(conversation, reply);
            recordMessage(conversation, "fin:" + session.getFinConversationId() + ":" + UUID.randomUUID().toString(), "fin", reply);
        }
        session.setReplyBuffer(null);
    }
    private String required(JsonNode value, String field) {
        if (value == null || value.asText().trim().isEmpty()) { throw new IllegalArgumentException("Missing Fin " + field); }
        return value.asText();
    }
    private String shortMessage(Exception exception) {
        String value = exception.getMessage();
        return value == null ? exception.getClass().getSimpleName() : value.substring(0, Math.min(value.length(), 1000));
    }
    private static class DeferredMessageException extends RuntimeException {
        DeferredMessageException(String message) { super(message); }
    }
}
