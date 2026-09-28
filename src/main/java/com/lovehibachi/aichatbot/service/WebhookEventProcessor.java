package com.lovehibachi.aichatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lovehibachi.aichatbot.config.BridgeProperties;
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
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookEventProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookEventProcessor.class);
    /**
     * Deliberately opaque protocol value returned by Fin Guidance. It must be
     * compared before rendering so the visitor never sees an internal control
     * message. Do not use a substring match: normal support text could otherwise
     * accidentally take a customer out of the AI flow.
     */
    private static final String FIN_GUIDANCE_HANDOFF_MARKER = "[[LH_HUMAN_HANDOFF]]";
    private final WebhookEventRepository eventRepository;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final FinSessionRepository sessionRepository;
    private final MissiveInboundMessage inboundMessage;
    private final HardRuleEngine hardRuleEngine;
    private final FinClient finClient;
    private final MissiveClient missiveClient;
    private final HandoffService handoffService;
    private final FinReplyTurnGate finReplyTurnGate;
    private final FinReplyRenderer finReplyRenderer;
    private final WebChatGreetingService webChatGreetingService;
    private final ObjectMapper objectMapper;
    private final BridgeProperties properties;

    public WebhookEventProcessor(WebhookEventRepository eventRepository,
                                 ChatConversationRepository conversationRepository,
                                 ChatMessageRepository messageRepository,
                                 FinSessionRepository sessionRepository,
                                 MissiveInboundMessage inboundMessage,
                                 HardRuleEngine hardRuleEngine,
                                 FinClient finClient,
                                 MissiveClient missiveClient,
                                 HandoffService handoffService,
                                 FinReplyTurnGate finReplyTurnGate,
                                 FinReplyRenderer finReplyRenderer,
                                 WebChatGreetingService webChatGreetingService,
                                 ObjectMapper objectMapper,
                                 BridgeProperties properties) {
        this.eventRepository = eventRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.inboundMessage = inboundMessage;
        this.hardRuleEngine = hardRuleEngine;
        this.finClient = finClient;
        this.missiveClient = missiveClient;
        this.handoffService = handoffService;
        this.finReplyTurnGate = finReplyTurnGate;
        this.finReplyRenderer = finReplyRenderer;
        this.webChatGreetingService = webChatGreetingService;
        this.objectMapper = objectMapper;
        this.properties = properties;
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
            newSession.setFinConversationId(finConversationId(conversation.getMissiveConversationId()));
            newSession.setStatus("thinking");
            newSession.setGreetingHtml(webChatGreetingService.greetingHtml(
                    inbound.getWebChatGreetingKind(), conversation.getWebChatTimezone()));
            sessionRepository.save(newSession);
            finReplyTurnGate.reset(newSession.getFinConversationId());
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
            active.setLowPeakFollowUpSentAt(null);
            active.setGreetingHtml(webChatGreetingService.greetingHtml(
                    inbound.getWebChatGreetingKind(), conversation.getWebChatTimezone()));
            sessionRepository.save(active);
            finReplyTurnGate.reset(active.getFinConversationId());
            LOGGER.info("Continuing Fin session: eventId={}, missiveConversationId={}, finConversationId={}",
                    event.getId(), conversation.getMissiveConversationId(), active.getFinConversationId());
            finClient.reply(active, conversation, inbound.getBody());
            return;
        }
        throw new DeferredMessageException("Fin is still " + active.getStatus() + " for this conversation");
    }

    private String finConversationId(String missiveConversationId) {
        String prefix = properties.getFin().getConversationIdPrefix();
        if (prefix == null || prefix.trim().isEmpty()) {
            throw new IllegalStateException("Missing FIN_CONVERSATION_ID_PREFIX");
        }
        return prefix + ":" + missiveConversationId + ":cycle:" + UUID.randomUUID().toString();
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
        if ("superseded".equals(session.getStatus())) {
            // An operator resumed AI handling after a handoff. The next customer
            // message starts a new Fin conversation, so never deliver delayed
            // callbacks from the retired conversation into the restored chat.
            event.setStatus(EventStatus.IGNORED);
            LOGGER.info("Ignored Fin event for superseded session: eventId={}, finConversationId={}, eventName={}",
                    event.getId(), finConversationId, eventName);
            return;
        }
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
            String rawAnswer = root.path("message").path("body").asText();
            if (isFinGuidanceHandoffMarker(rawAnswer)) {
                /*
                 * Fin Guidance returns this exact value when a customer explicitly
                 * asks for a human. It is an internal bridge protocol, not a
                 * customer-facing answer: raise the existing Missive handoff instead
                 * of rendering or delivering the marker to the visitor.
                 */
                LOGGER.info("Fin Guidance requested human handoff: eventId={}, finConversationId={}, missiveConversationId={}",
                        event.getId(), finConversationId, conversation.getMissiveConversationId());
                handoffService.requestHuman(conversation, "Fin Guidance requested human handoff");
                session.setStatus("escalated");
                session.setCompletedAt(Instant.now());
                sessionRepository.save(session);
                return;
            }
            String answer = finReplyRenderer.render(rawAnswer);
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
                sendReplyImmediately(session, conversation, prependGreeting(session, answer));
                session.setGreetingHtml(null);
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
        return "complete".equals(status) || "escalated".equals(status) || "resolved".equals(status)
                || "superseded".equals(status);
    }
    private boolean isFinGuidanceHandoffMarker(String replyBody) {
        /*
         * Fin currently returns its reply body as HTML, even where Guidance is
         * instructed to return an exact text marker (for example,
         * <p>[[LH_HUMAN_HANDOFF]]</p>). Compare rendered text rather than the raw
         * transport body. Fin may also add a newline or short explanation despite
         * Guidance, so recognize the complete opaque marker anywhere in that text.
         * This deliberately does not use natural-language keyword matching.
         */
        String plainText = replyBody == null ? "" : Jsoup.parseBodyFragment(replyBody).text().trim();
        return plainText.contains(FIN_GUIDANCE_HANDOFF_MARKER);
    }
    private void sendReplyImmediately(FinSession session, ChatConversation conversation, String reply) {
        if (conversation.getState() != ConversationState.AI_HANDLING) { return; }
        LOGGER.info("Sending Fin reply to Missive immediately: finConversationId={}, missiveConversationId={}, bodyLength={}",
                session.getFinConversationId(), conversation.getMissiveConversationId(), reply.length());
        // Do not generate a per-reply handoff token or CTA here. The current
        // Missive Live Chat widget can only render it as a browser link, which
        // breaks the in-chat experience. Fin Guidance and hard-rule handoff
        // remain active; a future first-party chat client will call the
        // handoff flow with its own authenticated chat-session token instead.
        missiveClient.sendFinReply(conversation, reply);
        // The Custom Channel callback is the source of truth for browser delivery
        // and persistence. Recording here as well would show every Fin message twice.
        if (!missiveClient.usesCustomChannel(conversation)) {
            recordMessage(conversation, "fin:" + session.getFinConversationId() + ":" + UUID.randomUUID().toString(), "fin", reply);
        }
    }
    private String prependGreeting(FinSession session, String reply) {
        String greeting = session.getGreetingHtml();
        return greeting == null || greeting.trim().isEmpty() ? reply : greeting + reply;
    }
    private void flushReply(FinSession session, ChatConversation conversation) {
        String reply = finReplyRenderer.render(session.getReplyBuffer());
        if (conversation.getState() == ConversationState.AI_HANDLING && reply != null && !reply.trim().isEmpty()) {
            LOGGER.info("Sending buffered Fin reply to Missive: finConversationId={}, missiveConversationId={}, bodyLength={}",
                    session.getFinConversationId(), conversation.getMissiveConversationId(), reply.length());
            missiveClient.sendFinReply(conversation, prependGreeting(session, reply));
            session.setGreetingHtml(null);
            if (!missiveClient.usesCustomChannel(conversation)) {
                recordMessage(conversation, "fin:" + session.getFinConversationId() + ":" + UUID.randomUUID().toString(), "fin", reply);
            }
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
