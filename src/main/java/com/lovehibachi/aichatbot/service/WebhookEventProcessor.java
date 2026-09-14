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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookEventProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookEventProcessor.class);
    /*
     * Fin sometimes emits this stock follow-up as a separate fin_replied event.
     * It is not a customer answer, so it must neither be displayed nor consume
     * this turn's first-reply slot. Keep this narrow and explicit: do not try to
     * infer intent from arbitrary Fin-generated answers.
    */
    private static final Pattern GENERIC_FIN_FOLLOW_UP = Pattern.compile(
            "^\\s*is\\s+that\\s+what\\s+you(?:\\s+were|\\s+are|'re|’re)\\s+looking\\s+for\\?\\s*$",
            Pattern.CASE_INSENSITIVE);
    /* Fin source citations are useful internally, but should not appear in the live-chat reply. */
    private static final Pattern FIN_SOURCE_CITATION = Pattern.compile(
            "\\[\\d+\\s*<https?://[^>\\]\\s]+>\\]");
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
        LOGGER.info("Processing webhook event: eventId={}, provider={}, externalEventId={}, eventType={}, priorStatus={}",
                event.getId(), event.getProvider(), event.getExternalEventId(), event.getEventType(), event.getStatus());
        event.setStatus(EventStatus.PROCESSING);
        eventRepository.save(event);
        try {
            if ("missive".equals(event.getProvider())) {
                processMissive(event);
            } else if ("fin".equals(event.getProvider())) {
                processFin(event);
            } else {
                event.setStatus(EventStatus.IGNORED);
                LOGGER.info("Ignored webhook event with unsupported provider: eventId={}, provider={}",
                        event.getId(), event.getProvider());
                return;
            }
            if (event.getStatus() != EventStatus.IGNORED) {
                event.setStatus(EventStatus.COMPLETED);
                event.setProcessedAt(Instant.now());
                event.setErrorMessage(null);
                LOGGER.info("Completed webhook event: eventId={}, provider={}, externalEventId={}, eventType={}",
                        event.getId(), event.getProvider(), event.getExternalEventId(), event.getEventType());
            }
        } catch (DeferredMessageException deferred) {
            event.setStatus(EventStatus.RETRY);
            event.setErrorMessage(deferred.getMessage());
            LOGGER.info("Deferred webhook event for retry: eventId={}, provider={}, externalEventId={}, reason={}",
                    event.getId(), event.getProvider(), event.getExternalEventId(), deferred.getMessage());
        } catch (Exception exception) {
            event.setStatus(EventStatus.RETRY);
            event.setErrorMessage(shortMessage(exception));
            LOGGER.warn("Webhook event failed and will retry: eventId={}, provider={}, externalEventId={}, eventType={}, errorType={}, reason={}",
                    event.getId(), event.getProvider(), event.getExternalEventId(), event.getEventType(),
                    exception.getClass().getSimpleName(), shortMessage(exception));
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
            LOGGER.info("Ignored Missive message because conversation is not AI-handled: eventId={}, missiveConversationId={}, state={}",
                    event.getId(), conversation.getMissiveConversationId(), conversation.getState());
            return;
        }
        String hardRule = hardRuleEngine.matchingRule(inbound.getBody());
        if (hardRule != null) {
            LOGGER.info("Escalating Missive conversation by hard rule: eventId={}, missiveConversationId={}, rule={}",
                    event.getId(), conversation.getMissiveConversationId(), hardRule);
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
            LOGGER.info("Starting Fin session: eventId={}, missiveConversationId={}, finConversationId={}, cycle={}",
                    event.getId(), conversation.getMissiveConversationId(), newSession.getFinConversationId(),
                    newSession.getCycleNumber());
            finClient.start(newSession, conversation, inbound.getBody(), historyBeforeCurrent(conversation, inbound.getMessageId()));
            return;
        }
        if ("awaiting_user_reply".equals(active.getStatus())) {
            active.setStatus("thinking");
            // A real customer message starts a new display turn.
            active.setFirstReplySentAt(null);
            sessionRepository.save(active);
            LOGGER.info("Continuing Fin session: eventId={}, missiveConversationId={}, finConversationId={}",
                    event.getId(), conversation.getMissiveConversationId(), active.getFinConversationId());
            finClient.reply(active, conversation, inbound.getBody());
            return;
        }
        throw new DeferredMessageException("Fin is still " + active.getStatus() + " for this conversation");
    }

    private void processFin(WebhookEvent event) throws Exception {
        JsonNode root = objectMapper.readTree(event.getPayload());
        String finConversationId = required(root.path("conversation_id"), "conversation_id");
        Optional<FinSession> found = sessionRepository.findByFinConversationId(finConversationId);
        if (!found.isPresent()) {
            event.setStatus(EventStatus.IGNORED);
            LOGGER.info("Ignored Fin event with unknown conversation: eventId={}, finConversationId={}, eventName={}",
                    event.getId(), finConversationId, root.path("event_name").asText());
            return;
        }
        FinSession session = found.get();
        ChatConversation conversation = session.getConversation();
        String eventName = root.path("event_name").asText();
        LOGGER.info("Processing Fin event: eventId={}, eventName={}, finConversationId={}, missiveConversationId={}",
                event.getId(), eventName, finConversationId, conversation.getMissiveConversationId());
        if ("fin_replied".equals(eventName)) {
            if (session.getFirstReplySentAt() != null) {
                /*
                 * Workaround experiment: treat the first non-empty Fin reply after
                 * each real customer message as the entire answer. Do not inspect
                 * fin_replied status (including legacy awaiting_user_reply), because
                 * this branch deliberately tests whether client-side suppression
                 * alone can prevent repeated follow-up messages.
                 */
                LOGGER.info("Ignored additional Fin reply for current customer turn: eventId={}, finConversationId={}, missiveConversationId={}",
                        event.getId(), finConversationId, conversation.getMissiveConversationId());
                return;
            }
            String answer = removeFinSourceCitations(root.path("message").path("body").asText());
            if (isGenericFinFollowUp(answer)) {
                LOGGER.info("Ignored generic Fin follow-up: eventId={}, finConversationId={}, missiveConversationId={}",
                        event.getId(), finConversationId, conversation.getMissiveConversationId());
                return;
            }
            if (!answer.trim().isEmpty()) {
                /*
                 * Fin's API documentation describes a later fin_status_updated event as
                 * the end of a reply cycle. In this workspace, verified production-like
                 * webhook traffic has only delivered fin_replied events, despite using
                 * the documented API version. Do not withhold a customer-visible answer
                 * while waiting for that undocumented-in-practice terminal notification.
                 *
                 * If Fin starts consistently delivering terminal events again, reassess
                 * whether multi-part replies should be aggregated before sending.
                */
                sendReplyImmediately(session, conversation, answer);
                session.setFirstReplySentAt(Instant.now());
            }
            // The immediate customer reply begins the next turn, regardless of Fin's
            // intermediate "replying" status in this webhook.
            session.setStatus("awaiting_user_reply");
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
                LOGGER.info("Fin escalated conversation: eventId={}, finConversationId={}, missiveConversationId={}",
                        event.getId(), finConversationId, conversation.getMissiveConversationId());
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
        if (messageRepository.existsByExternalMessageId(externalMessageId)) {
            LOGGER.info("Skipped duplicate chat message persistence: missiveConversationId={}, externalMessageId={}, author={}",
                    conversation.getMissiveConversationId(), externalMessageId, author);
            return;
        }
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
    private boolean isGenericFinFollowUp(String reply) {
        return reply != null && GENERIC_FIN_FOLLOW_UP.matcher(reply).matches();
    }
    private String removeFinSourceCitations(String reply) {
        if (reply == null || reply.isEmpty()) { return reply; }
        /*
         * Fin emits citations such as "[1 <https://example.com/article>]".
         * Strip only that exact numbered-citation format, so ordinary markdown
         * links and customer-facing URLs remain intact.
         */
        return FIN_SOURCE_CITATION.matcher(reply).replaceAll("");
    }
    private void sendReplyImmediately(FinSession session, ChatConversation conversation, String reply) {
        if (conversation.getState() != ConversationState.AI_HANDLING) { return; }
        LOGGER.info("Sending Fin reply to Missive immediately: finConversationId={}, missiveConversationId={}, bodyLength={}",
                session.getFinConversationId(), conversation.getMissiveConversationId(), reply.length());
        missiveClient.sendFinReply(conversation, reply);
        recordMessage(conversation, "fin:" + session.getFinConversationId() + ":" + UUID.randomUUID().toString(), "fin", reply);
    }
    private void flushReply(FinSession session, ChatConversation conversation) {
        String reply = removeFinSourceCitations(session.getReplyBuffer());
        if (conversation.getState() == ConversationState.AI_HANDLING && reply != null && !reply.trim().isEmpty()) {
            LOGGER.info("Sending buffered Fin reply to Missive: finConversationId={}, missiveConversationId={}, bodyLength={}",
                    session.getFinConversationId(), conversation.getMissiveConversationId(), reply.length());
            missiveClient.sendFinReply(conversation, reply);
            recordMessage(conversation, "fin:" + session.getFinConversationId() + ":" + UUID.randomUUID().toString(), "fin", reply);
        }
        session.setReplyBuffer(null);
        session.setReplyReceivedAt(null);
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
