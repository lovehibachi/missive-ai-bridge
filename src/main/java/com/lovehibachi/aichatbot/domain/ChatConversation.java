package com.lovehibachi.aichatbot.domain;

import java.time.Instant;
import java.util.UUID;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Entity
@Table(name = "chat_conversations", uniqueConstraints = @UniqueConstraint(name = "uk_chat_conversation_missive", columnNames = "missive_conversation_id"))
public class ChatConversation {
    @Id
    private String id;

    @Column(name = "missive_conversation_id", nullable = false, length = 128)
    private String missiveConversationId;

    @Column(name = "live_chat_account_id", nullable = false, length = 128)
    private String liveChatAccountId;

    @Column(name = "fin_visitor_id", nullable = false, length = 255)
    private String finVisitorId;

    @Column(name = "visitor_to_fields", nullable = false, columnDefinition = "text")
    private String visitorToFields;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConversationState state = ConversationState.AI_HANDLING;

    @Column(name = "escalation_reason", columnDefinition = "text")
    private String escalationReason;

    @Column(name = "last_missive_message_id", length = 128)
    private String lastMissiveMessageId;

    /** Opaque browser token used only by a Custom Channel conversation. */
    @Column(name = "web_chat_session_token", length = 128, unique = true)
    private String webChatSessionToken;

    /** IANA time zone reported by the first-party browser chat, used only for greeting copy. */
    @Column(name = "web_chat_timezone", length = 64)
    private String webChatTimezone;

    /**
     * The current timer anchor while the visitor is waiting for a human. It is
     * set at handoff and reset only by a later visitor message.
     */
    @Column(name = "handoff_waiting_since")
    private Instant handoffWaitingSince;

    @Column(name = "handoff_wait_reminder_sent_at")
    private Instant handoffWaitReminderSentAt;

    @Column(name = "handoff_contact_prompt_sent_at")
    private Instant handoffContactPromptSentAt;

    /** Number of visitor messages handled after the contact-information prompt. */
    @Column(name = "handoff_contact_attempts", nullable = false)
    private int handoffContactAttempts;

    /** Set after a valid first contact or after the unconditional second acknowledgement. */
    @Column(name = "handoff_contact_completed_at")
    private Instant handoffContactCompletedAt;

    /** First actual staff reply received after the handoff; it cancels waiting prompts. */
    @Column(name = "handoff_human_reply_at")
    private Instant handoffHumanReplyAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) { id = UUID.randomUUID().toString(); }
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    public String getId() { return id; }
    public String getMissiveConversationId() { return missiveConversationId; }
    public void setMissiveConversationId(String value) { this.missiveConversationId = value; }
    public String getLiveChatAccountId() { return liveChatAccountId; }
    public void setLiveChatAccountId(String value) { this.liveChatAccountId = value; }
    public String getFinVisitorId() { return finVisitorId; }
    public void setFinVisitorId(String value) { this.finVisitorId = value; }
    public String getVisitorToFields() { return visitorToFields; }
    public void setVisitorToFields(String value) { this.visitorToFields = value; }
    public ConversationState getState() { return state; }
    public void setState(ConversationState value) { this.state = value; }
    public String getEscalationReason() { return escalationReason; }
    public void setEscalationReason(String value) { this.escalationReason = value; }
    public String getLastMissiveMessageId() { return lastMissiveMessageId; }
    public void setLastMissiveMessageId(String value) { this.lastMissiveMessageId = value; }
    public String getWebChatSessionToken() { return webChatSessionToken; }
    public void setWebChatSessionToken(String value) { this.webChatSessionToken = value; }
    public String getWebChatTimezone() { return webChatTimezone; }
    public void setWebChatTimezone(String value) { this.webChatTimezone = value; }
    public Instant getHandoffWaitingSince() { return handoffWaitingSince; }
    public void setHandoffWaitingSince(Instant value) { handoffWaitingSince = value; }
    public Instant getHandoffWaitReminderSentAt() { return handoffWaitReminderSentAt; }
    public void setHandoffWaitReminderSentAt(Instant value) { handoffWaitReminderSentAt = value; }
    public Instant getHandoffContactPromptSentAt() { return handoffContactPromptSentAt; }
    public void setHandoffContactPromptSentAt(Instant value) { handoffContactPromptSentAt = value; }
    public int getHandoffContactAttempts() { return handoffContactAttempts; }
    public void setHandoffContactAttempts(int value) { handoffContactAttempts = value; }
    public Instant getHandoffContactCompletedAt() { return handoffContactCompletedAt; }
    public void setHandoffContactCompletedAt(Instant value) { handoffContactCompletedAt = value; }
    public Instant getHandoffHumanReplyAt() { return handoffHumanReplyAt; }
    public void setHandoffHumanReplyAt(Instant value) { handoffHumanReplyAt = value; }
}
