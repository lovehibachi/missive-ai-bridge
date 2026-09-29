package com.lovehibachi.aichatbot.service;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.ConversationState;
import com.lovehibachi.aichatbot.repository.ChatConversationRepository;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends a measured acknowledgement when an escalated visitor has not yet heard
 * from a person. The timer starts at handoff and is restarted only by a later
 * visitor message; automated messages never move it.
 */
@Service
public class HandoffWaitingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HandoffWaitingService.class);
    static final String WAITING_REMINDER_HTML =
            "<p>Thank you for your patience. Our team is reviewing your question and will reply as soon as possible.</p>";
    static final String CONTACT_PROMPT_HTML =
            "<p>To help us get back to you as quickly as possible, you can leave a valid email address or US phone number.</p>";
    static final String CONTACT_RETRY_HTML =
            "<p>We couldn’t verify that contact information. Please send a valid email address or US phone number so our team can reach you.</p>";
    static final String CONTACT_THANK_YOU_HTML =
            "<p>Thank you for your patience. We’ll contact you as soon as possible.</p>";
    private static final String HANDOFF_ACK_TEXT = "We’re connecting you with a member of our team. Please hold on.";
    private static final String WAITING_REMINDER_TEXT =
            "Thank you for your patience. Our team is reviewing your question and will reply as soon as possible.";
    private static final String CONTACT_PROMPT_TEXT =
            "To help us get back to you as quickly as possible, you can leave a valid email address or US phone number.";
    private static final String CONTACT_RETRY_TEXT =
            "We couldn’t verify that contact information. Please send a valid email address or US phone number so our team can reach you.";
    private static final String CONTACT_THANK_YOU_TEXT =
            "Thank you for your patience. We’ll contact you as soon as possible.";
    // This verifies format only. It cannot prove the mailbox exists or that a
    // telephone number is currently assigned, which would require verification.
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@"
            + "[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+\\b");
    // NANP format: a US/Canada country prefix is optional, but the ten-digit
    // number must have valid non-zero area and exchange prefixes.
    private static final Pattern US_PHONE = Pattern.compile("(?<!\\d)(?:\\+?1[ .-]?)?"
            + "(?:\\([2-9]\\d{2}\\)|[2-9]\\d{2})[ .-]?[2-9]\\d{2}[ .-]?\\d{4}(?!\\d)");

    private final ChatConversationRepository conversationRepository;
    private final MissiveClient missiveClient;
    private final BridgeProperties properties;

    public HandoffWaitingService(ChatConversationRepository conversationRepository,
                                 MissiveClient missiveClient,
                                 BridgeProperties properties) {
        this.conversationRepository = conversationRepository;
        this.missiveClient = missiveClient;
        this.properties = properties;
    }

    /** Initializes a fresh timer when the conversation first enters human handoff. */
    public void beginWaiting(ChatConversation conversation, Instant now) {
        conversation.setHandoffWaitingSince(now);
        conversation.setHandoffWaitReminderSentAt(null);
        conversation.setHandoffContactPromptSentAt(null);
        conversation.setHandoffContactAttempts(0);
        conversation.setHandoffContactCompletedAt(null);
        conversation.setHandoffHumanReplyAt(null);
    }

    /** A new visitor message during the wait restarts both thresholds. */
    public void resetWaitingForVisitorMessage(ChatConversation conversation, Instant now) {
        if (conversation.getState() != ConversationState.HUMAN_NEEDED
                || conversation.getHandoffHumanReplyAt() != null) {
            return;
        }
        beginWaiting(conversation, now);
        conversationRepository.save(conversation);
        LOGGER.info("Reset human-handoff wait timer after visitor message: missiveConversationId={}",
                conversation.getMissiveConversationId());
    }

    /**
     * Handles the two customer messages after the five-minute contact prompt.
     * A valid first response is acknowledged immediately. An invalid first
     * response receives one correction; its next response is acknowledged
     * without further validation so the visitor is never stuck in a loop.
     */
    public void handleVisitorMessage(ChatConversation conversation, String body, Instant now) {
        if (conversation.getState() != ConversationState.HUMAN_NEEDED
                || conversation.getHandoffHumanReplyAt() != null) {
            return;
        }
        if (conversation.getHandoffContactPromptSentAt() == null) {
            resetWaitingForVisitorMessage(conversation, now);
            return;
        }
        if (conversation.getHandoffContactCompletedAt() != null) { return; }

        int attempts = conversation.getHandoffContactAttempts();
        if (attempts == 0 && hasValidContact(body)) {
            conversation.setHandoffContactAttempts(1);
            conversation.setHandoffContactCompletedAt(now);
            conversationRepository.save(conversation);
            missiveClient.sendHandoffContactThankYou(conversation);
            LOGGER.info("Accepted visitor contact information: missiveConversationId={}",
                    conversation.getMissiveConversationId());
            return;
        }
        if (attempts == 0) {
            conversation.setHandoffContactAttempts(1);
            conversationRepository.save(conversation);
            missiveClient.sendHandoffContactRetry(conversation);
            LOGGER.info("Requested corrected visitor contact information: missiveConversationId={}",
                    conversation.getMissiveConversationId());
            return;
        }
        conversation.setHandoffContactAttempts(attempts + 1);
        conversation.setHandoffContactCompletedAt(now);
        conversationRepository.save(conversation);
        missiveClient.sendHandoffContactThankYou(conversation);
        LOGGER.info("Completed second visitor contact attempt without further validation: missiveConversationId={}",
                conversation.getMissiveConversationId());
    }

    /**
     * Custom Channel forwards every outgoing message, including our own prompts.
     * Only an outgoing body that is not bridge-generated can end the wait.
     */
    public void recordPotentialHumanReply(ChatConversation conversation, String body, Instant messageCreatedAt) {
        if (conversation.getState() != ConversationState.HUMAN_NEEDED
                || conversation.getHandoffHumanReplyAt() != null
                || conversation.getHandoffWaitingSince() == null
                || isBridgeGeneratedHandoffMessage(body)
                // Missive reports created_at with second precision, while the
                // handoff timestamp retains milliseconds. Allow the same second
                // so a staff reply sent immediately after handoff is not missed.
                || messageCreatedAt.plusSeconds(1).isBefore(conversation.getHandoffWaitingSince())) {
            return;
        }
        conversation.setHandoffHumanReplyAt(messageCreatedAt);
        conversation.setState(ConversationState.HUMAN_HANDLING);
        conversationRepository.save(conversation);
        LOGGER.info("Human reply ended handoff wait reminders: missiveConversationId={}",
                conversation.getMissiveConversationId());
    }

    @Scheduled(fixedDelay = 30000L)
    public void sendDuePrompts() {
        BridgeProperties.HandoffWaiting waiting = properties.getHandoffWaiting();
        if (!waiting.isEnabled()) { return; }
        Instant now = Instant.now();
        sendWaitingReminders(now, now.minusSeconds(waiting.getReminderDelaySeconds()));
        sendContactPrompts(now, now.minusSeconds(waiting.getContactPromptDelaySeconds()));
    }

    private void sendWaitingReminders(Instant now, Instant due) {
        List<ChatConversation> candidates = conversationRepository
                .findTop50ByStateAndHandoffHumanReplyAtIsNullAndHandoffWaitingSinceBeforeAndHandoffWaitReminderSentAtIsNullOrderByHandoffWaitingSinceAsc(
                        ConversationState.HUMAN_NEEDED, due);
        for (ChatConversation conversation : candidates) {
            sendIfClaimed(conversation, conversation.getHandoffWaitingSince(), due, now, true);
        }
    }

    private void sendContactPrompts(Instant now, Instant due) {
        List<ChatConversation> candidates = conversationRepository
                .findTop50ByStateAndHandoffHumanReplyAtIsNullAndHandoffWaitingSinceBeforeAndHandoffContactPromptSentAtIsNullOrderByHandoffWaitingSinceAsc(
                        ConversationState.HUMAN_NEEDED, due);
        for (ChatConversation conversation : candidates) {
            sendIfClaimed(conversation, conversation.getHandoffWaitingSince(), due, now, false);
        }
    }

    private void sendIfClaimed(ChatConversation conversation, Instant waitingSince, Instant due, Instant claimedAt,
                               boolean reminder) {
        if (waitingSince == null) { return; }
        int claimed = reminder
                ? conversationRepository.claimHandoffWaitReminder(conversation.getId(), waitingSince, due, claimedAt)
                : conversationRepository.claimHandoffContactPrompt(conversation.getId(), waitingSince, due, claimedAt);
        if (claimed == 0) { return; }
        try {
            if (reminder) {
                LOGGER.info("Sending human-handoff waiting reminder: missiveConversationId={}",
                        conversation.getMissiveConversationId());
                missiveClient.sendHandoffWaitingReminder(conversation);
            } else {
                LOGGER.info("Sending human-handoff contact prompt: missiveConversationId={}",
                        conversation.getMissiveConversationId());
                missiveClient.sendHandoffContactPrompt(conversation);
            }
        } catch (Exception exception) {
            if (reminder) {
                conversationRepository.releaseHandoffWaitReminder(conversation.getId(), claimedAt);
            } else {
                conversationRepository.releaseHandoffContactPrompt(conversation.getId(), claimedAt);
            }
            LOGGER.warn("Human-handoff waiting prompt failed: type={}, missiveConversationId={}, errorType={}",
                    reminder ? "reminder" : "contact", conversation.getMissiveConversationId(),
                    exception.getClass().getSimpleName());
        }
    }

    static boolean isBridgeGeneratedHandoffMessage(String htmlBody) {
        String text = htmlBody == null ? "" : Jsoup.parseBodyFragment(htmlBody).text().trim();
        return HANDOFF_ACK_TEXT.equals(text) || WAITING_REMINDER_TEXT.equals(text) || CONTACT_PROMPT_TEXT.equals(text)
                || CONTACT_RETRY_TEXT.equals(text) || CONTACT_THANK_YOU_TEXT.equals(text);
    }

    private boolean hasValidContact(String body) {
        String value = body == null ? "" : Jsoup.parseBodyFragment(body).text();
        return EMAIL.matcher(value).find() || US_PHONE.matcher(value).find();
    }
}
