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
}
