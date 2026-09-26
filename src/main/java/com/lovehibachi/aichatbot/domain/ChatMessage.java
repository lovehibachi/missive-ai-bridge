package com.lovehibachi.aichatbot.domain;

import java.time.Instant;
import java.util.UUID;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Entity
@Table(name = "chat_messages", uniqueConstraints = @UniqueConstraint(name = "uk_chat_message_external", columnNames = "external_message_id"))
public class ChatMessage {
    @Id
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "chat_conversation_id", nullable = false)
    private ChatConversation conversation;

    @Column(name = "external_message_id", nullable = false, length = 255)
    private String externalMessageId;

    @Column(nullable = false, length = 16)
    private String author;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) { id = UUID.randomUUID().toString(); }
        if (createdAt == null) { createdAt = Instant.now(); }
    }

    public ChatConversation getConversation() { return conversation; }
    public void setConversation(ChatConversation value) { this.conversation = value; }
    public String getExternalMessageId() { return externalMessageId; }
    public void setExternalMessageId(String value) { this.externalMessageId = value; }
    public String getAuthor() { return author; }
    public void setAuthor(String value) { this.author = value; }
    public String getBody() { return body; }
    public void setBody(String value) { this.body = value; }
    public Instant getCreatedAt() { return createdAt; }
    public String getId() { return id; }
}
