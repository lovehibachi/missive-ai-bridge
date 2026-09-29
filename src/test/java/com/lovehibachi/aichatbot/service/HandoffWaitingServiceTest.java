package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.ConversationState;
import com.lovehibachi.aichatbot.repository.ChatConversationRepository;
import java.time.Instant;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class HandoffWaitingServiceTest {
    @Test
    void visitorFollowUpRestartsBothThresholds() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        HandoffWaitingService service = service(conversations, Mockito.mock(MissiveClient.class));
        ChatConversation conversation = conversation();
        conversation.setHandoffWaitReminderSentAt(Instant.now().minusSeconds(1));
        conversation.setHandoffContactPromptSentAt(Instant.now().minusSeconds(1));
        Instant resetAt = Instant.parse("2026-09-29T03:00:00Z");

        service.resetWaitingForVisitorMessage(conversation, resetAt);

        assertEquals(resetAt, conversation.getHandoffWaitingSince());
        assertNull(conversation.getHandoffWaitReminderSentAt());
        assertNull(conversation.getHandoffContactPromptSentAt());
        verify(conversations).save(conversation);
    }

    @Test
    void bridgeGeneratedPromptDoesNotEndTheWaitButHumanReplyDoes() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        HandoffWaitingService service = service(conversations, Mockito.mock(MissiveClient.class));
        ChatConversation conversation = conversation();
        Instant waitingSince = Instant.parse("2026-09-29T03:00:00Z");
        conversation.setHandoffWaitingSince(waitingSince);

        service.recordPotentialHumanReply(conversation, HandoffWaitingService.WAITING_REMINDER_HTML,
                waitingSince.plusSeconds(120));
        assertEquals(ConversationState.HUMAN_NEEDED, conversation.getState());
        verify(conversations, never()).save(conversation);

        Instant humanReplyAt = waitingSince.plusSeconds(121);
        service.recordPotentialHumanReply(conversation, "<p>I can help with that.</p>", humanReplyAt);
        assertEquals(ConversationState.HUMAN_HANDLING, conversation.getState());
        assertEquals(humanReplyAt, conversation.getHandoffHumanReplyAt());
        verify(conversations).save(conversation);
    }

    @Test
    void schedulerClaimsAndSendsOnlyTheDueReminder() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        MissiveClient missive = Mockito.mock(MissiveClient.class);
        HandoffWaitingService service = service(conversations, missive);
        ChatConversation conversation = conversation();
        ReflectionTestUtils.setField(conversation, "id", "conversation-1");
        conversation.setHandoffWaitingSince(Instant.now().minusSeconds(121));
        when(conversations.findTop50ByStateAndHandoffHumanReplyAtIsNullAndHandoffWaitingSinceBeforeAndHandoffWaitReminderSentAtIsNullOrderByHandoffWaitingSinceAsc(
                eq(ConversationState.HUMAN_NEEDED), any(Instant.class))).thenReturn(Collections.singletonList(conversation));
        when(conversations.findTop50ByStateAndHandoffHumanReplyAtIsNullAndHandoffWaitingSinceBeforeAndHandoffContactPromptSentAtIsNullOrderByHandoffWaitingSinceAsc(
                eq(ConversationState.HUMAN_NEEDED), any(Instant.class))).thenReturn(Collections.emptyList());
        when(conversations.claimHandoffWaitReminder(eq("conversation-1"), eq(conversation.getHandoffWaitingSince()),
                any(Instant.class), any(Instant.class))).thenReturn(1);

        service.sendDuePrompts();

        verify(missive).sendHandoffWaitingReminder(conversation);
        verify(missive, never()).sendHandoffContactPrompt(any(ChatConversation.class));
    }

    @Test
    void acceptsValidEmailOnTheFirstContactResponse() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        MissiveClient missive = Mockito.mock(MissiveClient.class);
        HandoffWaitingService service = service(conversations, missive);
        ChatConversation conversation = contactPromptedConversation();
        Instant now = Instant.parse("2026-09-29T03:05:00Z");

        service.handleVisitorMessage(conversation, "Please reach me at jane@example.com", now);

        assertEquals(1, conversation.getHandoffContactAttempts());
        assertEquals(now, conversation.getHandoffContactCompletedAt());
        verify(missive).sendHandoffContactThankYou(conversation);
        verify(missive, never()).sendHandoffContactRetry(any(ChatConversation.class));
    }

    @Test
    void retriesOnceForInvalidContactThenThanksOnSecondAttempt() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        MissiveClient missive = Mockito.mock(MissiveClient.class);
        HandoffWaitingService service = service(conversations, missive);
        ChatConversation conversation = contactPromptedConversation();
        Instant first = Instant.parse("2026-09-29T03:05:00Z");

        service.handleVisitorMessage(conversation, "call me later", first);
        assertEquals(1, conversation.getHandoffContactAttempts());
        assertNull(conversation.getHandoffContactCompletedAt());
        verify(missive).sendHandoffContactRetry(conversation);

        Instant second = first.plusSeconds(30);
        service.handleVisitorMessage(conversation, "still no contact details", second);
        assertEquals(2, conversation.getHandoffContactAttempts());
        assertEquals(second, conversation.getHandoffContactCompletedAt());
        verify(missive).sendHandoffContactThankYou(conversation);
    }

    @Test
    void acceptsUsPhoneNumberOnTheFirstContactResponse() {
        ChatConversationRepository conversations = Mockito.mock(ChatConversationRepository.class);
        MissiveClient missive = Mockito.mock(MissiveClient.class);
        HandoffWaitingService service = service(conversations, missive);
        ChatConversation conversation = contactPromptedConversation();

        service.handleVisitorMessage(conversation, "My number is +1 (415) 555-0123", Instant.now());

        verify(missive).sendHandoffContactThankYou(conversation);
    }

    private HandoffWaitingService service(ChatConversationRepository conversations, MissiveClient missive) {
        BridgeProperties properties = new BridgeProperties();
        properties.getHandoffWaiting().setReminderDelaySeconds(120L);
        properties.getHandoffWaiting().setContactPromptDelaySeconds(300L);
        return new HandoffWaitingService(conversations, missive, properties);
    }

    private ChatConversation conversation() {
        ChatConversation conversation = new ChatConversation();
        conversation.setMissiveConversationId("missive-1");
        conversation.setState(ConversationState.HUMAN_NEEDED);
        return conversation;
    }

    private ChatConversation contactPromptedConversation() {
        ChatConversation conversation = conversation();
        conversation.setHandoffWaitingSince(Instant.parse("2026-09-29T03:00:00Z"));
        conversation.setHandoffContactPromptSentAt(Instant.parse("2026-09-29T03:05:00Z"));
        return conversation;
    }
}
